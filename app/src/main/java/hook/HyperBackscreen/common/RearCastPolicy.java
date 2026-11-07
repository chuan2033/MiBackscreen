package hook.HyperBackscreen.common;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * 「应用投放到背屏 / 拉回主屏」的纯逻辑：从根任务列表里挑目标、解析请求、映射结果码。
 *
 * <p>不碰任何 Android 运行时对象，system_server 侧（RearCastHost）把 RootTaskInfo 反射成
 * {@link TaskEntry} 再交给这里；发送方（背屏面板、控制中心磁贴）用 {@link #outcomeOf} 解读结果。
 */
public final class RearCastPolicy {
    /** WindowConfiguration.ACTIVITY_TYPE_STANDARD；home = 2、recents = 3、assistant = 4、dream = 5。 */
    public static final int ACTIVITY_TYPE_STANDARD = 1;
    /** WindowConfiguration.WINDOWING_MODE_FULLSCREEN；0 为 undefined，更大的值是画中画 / 自由窗 / 分屏。 */
    public static final int WINDOWING_MODE_FULLSCREEN = 1;
    /** 反射读不到的整数字段。 */
    public static final int UNKNOWN = -1;

    private RearCastPolicy() {
    }

    /** 一个根任务的快照（RootTaskInfo 里本功能关心的几项）。 */
    public static final class TaskEntry {
        public final int rootTaskId;
        @Nullable
        public final String packageName;
        public final int activityType;
        public final int windowingMode;
        public final boolean visible;

        public TaskEntry(int rootTaskId, @Nullable String packageName, int activityType,
                         int windowingMode, boolean visible) {
            this.rootTaskId = rootTaskId;
            this.packageName = packageName;
            this.activityType = activityType;
            this.windowingMode = windowingMode;
            this.visible = visible;
        }

        @NonNull
        @Override
        public String toString() {
            return "#" + rootTaskId + " " + packageName + " type=" + activityType
                    + " mode=" + windowingMode + " visible=" + visible;
        }
    }

    /** 请求动作。 */
    public enum Op {
        TO_REAR, TO_MAIN, TOGGLE, QUERY;

        /** 解析 {@link Constants#EXTRA_REAR_CAST_OP}；不认识的取值返回 null（按坏请求拒绝）。 */
        @Nullable
        public static Op fromExtra(@Nullable String value) {
            if (value == null) return null;
            switch (value) {
                case Constants.REAR_CAST_OP_TO_REAR:
                    return TO_REAR;
                case Constants.REAR_CAST_OP_TO_MAIN:
                    return TO_MAIN;
                case Constants.REAR_CAST_OP_TOGGLE:
                    return TOGGLE;
                case Constants.REAR_CAST_OP_QUERY:
                    return QUERY;
                default:
                    return null;
            }
        }
    }

    /** 发送方看到的结果。 */
    public enum Outcome {
        MOVED_TO_REAR,
        MOVED_TO_MAIN,
        NO_APP_ON_MAIN,
        NO_APP_ON_REAR,
        API_UNAVAILABLE,
        MOVE_FAILED,
        DENIED,
        BAD_REQUEST,
        /** 设备已锁定（需要凭据），拒绝投放。 */
        LOCKED,
        REAR_HAS_APP,
        REAR_EMPTY,
        /** 没有接收器处理：system_server 里的 Hook 没生效（未勾作用域 / 未重启）。 */
        NOT_ACTIVE,
        TIMEOUT,
        UNKNOWN;

        public boolean isSuccess() {
            return this == MOVED_TO_REAR || this == MOVED_TO_MAIN;
        }

        /** 查询结果：true = 背屏有投放的应用，false = 没有，null = 查不到。 */
        @Nullable
        public Boolean rearHasApp() {
            if (this == REAR_HAS_APP || this == MOVED_TO_REAR) return Boolean.TRUE;
            if (this == REAR_EMPTY || this == NO_APP_ON_REAR) return Boolean.FALSE;
            return null;
        }
    }

    @NonNull
    public static Outcome outcomeOf(int resultCode) {
        switch (resultCode) {
            case Constants.REAR_CAST_RESULT_MOVED_TO_REAR:
                return Outcome.MOVED_TO_REAR;
            case Constants.REAR_CAST_RESULT_MOVED_TO_MAIN:
                return Outcome.MOVED_TO_MAIN;
            case Constants.REAR_CAST_RESULT_NO_APP_ON_MAIN:
                return Outcome.NO_APP_ON_MAIN;
            case Constants.REAR_CAST_RESULT_NO_APP_ON_REAR:
                return Outcome.NO_APP_ON_REAR;
            case Constants.REAR_CAST_RESULT_API_UNAVAILABLE:
                return Outcome.API_UNAVAILABLE;
            case Constants.REAR_CAST_RESULT_MOVE_FAILED:
                return Outcome.MOVE_FAILED;
            case Constants.REAR_CAST_RESULT_DENIED:
                return Outcome.DENIED;
            case Constants.REAR_CAST_RESULT_BAD_REQUEST:
                return Outcome.BAD_REQUEST;
            case Constants.REAR_CAST_RESULT_LOCKED:
                return Outcome.LOCKED;
            case Constants.REAR_CAST_RESULT_REAR_HAS_APP:
                return Outcome.REAR_HAS_APP;
            case Constants.REAR_CAST_RESULT_REAR_EMPTY:
                return Outcome.REAR_EMPTY;
            case Constants.REAR_CAST_RESULT_NOT_HANDLED:
                return Outcome.NOT_ACTIVE;
            case Constants.REAR_CAST_RESULT_TIMEOUT:
                return Outcome.TIMEOUT;
            default:
                return Outcome.UNKNOWN;
        }
    }

    /** 只接受背屏中心与模块 App 自己；拿不到身份（null）一律拒绝。 */
    public static boolean isAllowedSender(@Nullable String packageName) {
        return Constants.TARGET_PACKAGE.equals(packageName)
                || Constants.MODULE_PACKAGE.equals(packageName);
    }

    /** 这些包的任务永远不移动：主屏桌面、系统界面、背屏中心自己。 */
    public static boolean isExcludedPackage(@Nullable String packageName) {
        return packageName == null
                || packageName.isEmpty()
                || Constants.TARGET_PACKAGE.equals(packageName)
                || Constants.SYSTEM_UI_PACKAGE.equals(packageName)
                || Constants.MAIN_LAUNCHER_PACKAGE.equals(packageName);
    }

    /**
     * 主屏 → 背屏的目标：按 z 序（列表自上而下）取第一个可见、普通类型、全屏的应用任务。
     * 桌面 / 最近任务 / 助手等非 standard 类型、分屏 / 小窗 / 画中画、排除名单里的包都跳过。
     */
    @Nullable
    public static TaskEntry pickMainToRear(@Nullable List<TaskEntry> topFirst) {
        if (topFirst == null) return null;
        for (TaskEntry entry : topFirst) {
            if (entry == null || !entry.visible) continue;
            if (entry.activityType != ACTIVITY_TYPE_STANDARD) continue;
            if (entry.windowingMode > WINDOWING_MODE_FULLSCREEN) continue;
            if (isExcludedPackage(entry.packageName)) continue;
            return entry;
        }
        return null;
    }

    /**
     * 背屏 → 主屏的目标：背屏上最上层的普通类型应用任务。不要求可见 —— 用户回到背屏桌面后，
     * 被投放的应用还压在桌面下面，同样要能拉回来。
     */
    @Nullable
    public static TaskEntry pickRearToMain(@Nullable List<TaskEntry> topFirst) {
        if (topFirst == null) return null;
        for (TaskEntry entry : topFirst) {
            if (entry == null) continue;
            if (entry.activityType != ACTIVITY_TYPE_STANDARD) continue;
            if (isExcludedPackage(entry.packageName)) continue;
            return entry;
        }
        return null;
    }

    /** 切换动作落到哪一边。 */
    @NonNull
    public static Op resolveToggle(boolean rearHasApp) {
        return rearHasApp ? Op.TO_MAIN : Op.TO_REAR;
    }

    /** 在列表里找指定根任务（移动后用来确认确实到了目标屏）。 */
    public static boolean containsTask(@Nullable List<TaskEntry> entries, int rootTaskId) {
        if (entries == null) return false;
        for (TaskEntry entry : entries) {
            if (entry != null && entry.rootTaskId == rootTaskId) return true;
        }
        return false;
    }
}
