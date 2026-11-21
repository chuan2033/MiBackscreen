package hook.HyperBackscreen.common;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * 「应用投放到背屏 / 拉回主屏」与背屏桌面会话的纯逻辑：解析请求、按操作检查来源、
 * 从根任务列表里挑目标、映射结果码。
 *
 * <p>不碰任何 Android 运行时对象，system_server 侧（RearCastHost）把 RootTaskInfo 反射成
 * {@link TaskEntry} 再交给这里；发送方（背屏面板、控制中心磁贴）用 {@link #outcomeOf} 解读结果。
 */
public final class RearCastPolicy {
    /** WindowConfiguration.ACTIVITY_TYPE_STANDARD；home = 2、recents = 3、assistant = 4、dream = 5。 */
    public static final int ACTIVITY_TYPE_STANDARD = 1;
    public static final int ACTIVITY_TYPE_HOME = 2;
    /** WindowConfiguration.WINDOWING_MODE_FULLSCREEN；0 为 undefined，更大的值是画中画 / 自由窗 / 分屏。 */
    public static final int WINDOWING_MODE_FULLSCREEN = 1;
    /** 反射读不到的整数字段。 */
    public static final int UNKNOWN = -1;
    /** UserHandle.PER_USER_RANGE（隐藏常量）：uid / 它 = userId。 */
    public static final int PER_USER_UID_RANGE = 100_000;

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
        TO_REAR, TO_MAIN, TOGGLE, QUERY,
        /** 背屏桌面会话：启动、查询与四个控制动作，只接受背屏中心。 */
        PANEL_LAUNCH, PANEL_QUERY, PANEL_BACK, PANEL_HOME, PANEL_TO_MAIN, PANEL_EXIT,
        /** 密度档位同步后刷新会话密度，只接受模块 App。 */
        DENSITY_REFRESH;

        /** 背屏桌面会话相关的操作（交给 RearDesktopHost）。 */
        public boolean isDesktopOp() {
            return isPanelOp() || this == DENSITY_REFRESH;
        }

        public boolean isPanelOp() {
            return this == PANEL_LAUNCH || this == PANEL_QUERY || isSessionControl();
        }

        /** 只对当前会话任务生效的控制动作。 */
        public boolean isSessionControl() {
            return this == PANEL_BACK || this == PANEL_HOME || this == PANEL_TO_MAIN || this == PANEL_EXIT;
        }

        /** 执行后会话结束的控制动作。 */
        public boolean endsSession() {
            return this == PANEL_HOME || this == PANEL_TO_MAIN || this == PANEL_EXIT;
        }

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
                case Constants.REAR_CAST_OP_PANEL_LAUNCH:
                    return PANEL_LAUNCH;
                case Constants.REAR_CAST_OP_PANEL_QUERY:
                    return PANEL_QUERY;
                case Constants.REAR_CAST_OP_PANEL_BACK:
                    return PANEL_BACK;
                case Constants.REAR_CAST_OP_PANEL_HOME:
                    return PANEL_HOME;
                case Constants.REAR_CAST_OP_PANEL_TO_MAIN:
                    return PANEL_TO_MAIN;
                case Constants.REAR_CAST_OP_PANEL_EXIT:
                    return PANEL_EXIT;
                case Constants.REAR_CAST_OP_DENSITY_REFRESH:
                    return DENSITY_REFRESH;
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
        /** 设备已锁定，拒绝把应用放到背屏。 */
        LOCKED,
        REAR_HAS_APP,
        REAR_EMPTY,
        /** 控制操作、查询或密度刷新已执行。 */
        DONE,
        /** 启动请求已受理，进展经状态通知送达。 */
        ACCEPTED,
        /** ROM 不允许该应用显示在背屏。 */
        ROM_DENIED,
        /** 组件不可启动。 */
        BAD_COMPONENT,
        /** 会话不存在或已结束。 */
        NO_SESSION,
        /** 另一个会话正在启动或结束。 */
        BUSY,
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
            case Constants.REAR_CAST_RESULT_DONE:
                return Outcome.DONE;
            case Constants.REAR_CAST_RESULT_ACCEPTED:
                return Outcome.ACCEPTED;
            case Constants.REAR_CAST_RESULT_ROM_DENIED:
                return Outcome.ROM_DENIED;
            case Constants.REAR_CAST_RESULT_BAD_COMPONENT:
                return Outcome.BAD_COMPONENT;
            case Constants.REAR_CAST_RESULT_NO_SESSION:
                return Outcome.NO_SESSION;
            case Constants.REAR_CAST_RESULT_BUSY:
                return Outcome.BUSY;
            case Constants.REAR_CAST_RESULT_NOT_HANDLED:
                return Outcome.NOT_ACTIVE;
            case Constants.REAR_CAST_RESULT_TIMEOUT:
                return Outcome.TIMEOUT;
            default:
                return Outcome.UNKNOWN;
        }
    }

    /** 通道级来源：只接受背屏中心与模块 App 自己；拿不到身份（null）一律拒绝。 */
    public static boolean isAllowedSender(@Nullable String packageName) {
        return Constants.TARGET_PACKAGE.equals(packageName)
                || Constants.MODULE_PACKAGE.equals(packageName);
    }

    /**
     * 操作级来源：常规投放沿用通道规则；面板会话的启动、查询和控制只接受背屏中心；
     * 密度刷新只接受模块 App。
     */
    public static boolean isAllowedSenderFor(@NonNull Op op, @Nullable String packageName) {
        if (op.isPanelOp()) return Constants.TARGET_PACKAGE.equals(packageName);
        if (op == Op.DENSITY_REFRESH) return Constants.MODULE_PACKAGE.equals(packageName);
        return isAllowedSender(packageName);
    }

    /** uid 所属用户；身份缺失（负数）返回 {@link #UNKNOWN}。 */
    public static int userIdOf(int uid) {
        return uid < 0 ? UNKNOWN : uid / PER_USER_UID_RANGE;
    }

    /**
     * 发送方身份核对：包名在发送者所属用户下解析出的 uid 必须与发送方 uid 完全一致，
     * 且该用户就是当前可用（前台、已解锁）的目标用户。
     */
    public static boolean isSenderIdentityAccepted(int senderUid, int packageUidInSenderUser,
                                                   int currentUserId, boolean senderUserUnlocked) {
        if (senderUid < 0 || packageUidInSenderUser < 0) return false;
        if (senderUid != packageUidInSenderUser) return false;
        return userIdOf(senderUid) == currentUserId && senderUserUnlocked;
    }

    /** 这些包的任务永远不移动、不从面板启动：主屏桌面、系统界面、背屏中心与模块自己。 */
    public static boolean isExcludedPackage(@Nullable String packageName) {
        return packageName == null
                || packageName.isEmpty()
                || Constants.TARGET_PACKAGE.equals(packageName)
                || Constants.MODULE_PACKAGE.equals(packageName)
                || Constants.SYSTEM_UI_PACKAGE.equals(packageName)
                || Constants.MAIN_LAUNCHER_PACKAGE.equals(packageName);
    }

    /** 系统侧在目标用户下重新解析出的组件信息。 */
    public static final class LaunchTarget {
        public final boolean resolved;
        @Nullable
        public final String packageName;
        public final boolean exported;
        /** Activity 与所属应用都已启用。 */
        public final boolean enabled;
        /** 该组件出现在目标用户的 ACTION_MAIN / CATEGORY_LAUNCHER 查询结果里。 */
        public final boolean launcherEntry;
        /** Activity 要求的权限；null 表示不要求。 */
        @Nullable
        public final String requiredPermission;
        /** 发送方（背屏中心）是否持有 {@link #requiredPermission}。 */
        public final boolean senderHoldsPermission;

        public LaunchTarget(boolean resolved, @Nullable String packageName, boolean exported, boolean enabled,
                            boolean launcherEntry, @Nullable String requiredPermission,
                            boolean senderHoldsPermission) {
            this.resolved = resolved;
            this.packageName = packageName;
            this.exported = exported;
            this.enabled = enabled;
            this.launcherEntry = launcherEntry;
            this.requiredPermission = requiredPermission;
            this.senderHoldsPermission = senderHoldsPermission;
        }
    }

    /** 组件规则：已导出、已启用的桌面入口，不在排除名单里，面板持有它要求的权限。 */
    public static boolean isLaunchable(@Nullable LaunchTarget target) {
        if (target == null || !target.resolved) return false;
        if (isExcludedPackage(target.packageName)) return false;
        if (!target.exported || !target.enabled || !target.launcherEntry) return false;
        return target.requiredPermission == null || target.senderHoldsPermission;
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
     * 背屏 → 主屏的目标：背屏上最上层的普通类型应用任务。不要求可见 —— 用户回到背屏首页后，
     * 被投放的应用还压在首页下面，同样要能拉回来。
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
        return findTask(entries, rootTaskId) != null;
    }

    /** 按根任务编号取任务；会话控制只认会话绑定的任务，与常规投放挑中的目标无关。 */
    @Nullable
    public static TaskEntry findTask(@Nullable List<TaskEntry> entries, int rootTaskId) {
        if (entries == null || rootTaskId < 0) return null;
        for (TaskEntry entry : entries) {
            if (entry != null && entry.rootTaskId == rootTaskId) return entry;
        }
        return null;
    }

    /** 背屏首页（背屏中心或 home 类型的任务）。 */
    public static boolean isRearHome(@Nullable TaskEntry entry) {
        return entry != null && (entry.activityType == ACTIVITY_TYPE_HOME
                || Constants.TARGET_PACKAGE.equals(entry.packageName));
    }

    /** 会话任务当前所处的位置。 */
    public enum Presence {
        /** 在背屏上，且是背屏最上层的可见任务。 */
        VISIBLE,
        /** 在背屏上，背屏首页盖在它上面。 */
        HOME_ON_TOP,
        /** 在背屏上但看不到（被其它任务或锁屏遮挡、背屏熄灭）。 */
        HIDDEN,
        ON_MAIN,
        GONE
    }

    @NonNull
    public static Presence presenceOf(@Nullable List<TaskEntry> rearTopFirst,
                                      @Nullable List<TaskEntry> mainTopFirst,
                                      int rootTaskId) {
        if (findTask(rearTopFirst, rootTaskId) == null) {
            return findTask(mainTopFirst, rootTaskId) != null ? Presence.ON_MAIN : Presence.GONE;
        }
        TaskEntry topVisible = firstVisible(rearTopFirst);
        if (topVisible == null) return Presence.HIDDEN;
        if (topVisible.rootTaskId == rootTaskId) return Presence.VISIBLE;
        return isRearHome(topVisible) ? Presence.HOME_ON_TOP : Presence.HIDDEN;
    }

    @Nullable
    private static TaskEntry firstVisible(@Nullable List<TaskEntry> topFirst) {
        if (topFirst == null) return null;
        for (TaskEntry entry : topFirst) {
            if (entry != null && entry.visible) return entry;
        }
        return null;
    }

    /** 启动后观察到的结果。 */
    public static final class LaunchObservation {
        @NonNull
        public final Presence presence;
        public final int rootTaskId;
        /** 该任务在发起启动前就已存在（会话不持有它）。 */
        public final boolean preexisting;

        public LaunchObservation(@NonNull Presence presence, int rootTaskId, boolean preexisting) {
            this.presence = presence;
            this.rootTaskId = rootTaskId;
            this.preexisting = preexisting;
        }
    }

    /**
     * 找本次启动打开的任务：背屏最上层可见的普通任务属于目标包时算已在背屏显示；
     * 目标包的任务在启动后成了主屏最上层可见任务（新建的任务，或启动前不在主屏最上层的已有任务）
     * 时算被改派到主屏；其它情况仍在等待（返回 GONE）。
     */
    @NonNull
    public static LaunchObservation observeLaunch(@Nullable List<TaskEntry> rearTopFirst,
                                                  @Nullable List<TaskEntry> mainTopFirst,
                                                  @NonNull String packageName,
                                                  @NonNull Collection<Integer> preexistingTaskIds,
                                                  int mainTopTaskIdBeforeLaunch) {
        TaskEntry rearTop = firstVisible(rearTopFirst);
        if (rearTop != null && rearTop.activityType == ACTIVITY_TYPE_STANDARD
                && packageName.equals(rearTop.packageName)) {
            return new LaunchObservation(Presence.VISIBLE, rearTop.rootTaskId,
                    preexistingTaskIds.contains(rearTop.rootTaskId));
        }
        TaskEntry mainTop = firstVisible(mainTopFirst);
        if (mainTop != null && packageName.equals(mainTop.packageName)
                && mainTop.rootTaskId != mainTopTaskIdBeforeLaunch) {
            return new LaunchObservation(Presence.ON_MAIN, mainTop.rootTaskId,
                    preexistingTaskIds.contains(mainTop.rootTaskId));
        }
        return new LaunchObservation(Presence.GONE, UNKNOWN, false);
    }

    /** 列表里最上层可见任务的编号；没有时返回 {@link #UNKNOWN}。 */
    public static int topVisibleTaskId(@Nullable List<TaskEntry> topFirst) {
        TaskEntry entry = firstVisible(topFirst);
        return entry == null ? UNKNOWN : entry.rootTaskId;
    }
}
