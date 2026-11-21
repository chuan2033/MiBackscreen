package hook.HyperBackscreen.hook.system;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import hook.HyperBackscreen.common.RearCastPolicy;
import hook.HyperBackscreen.common.RearCastPolicy.TaskEntry;

/**
 * system_server 里用到的隐藏系统接口，全部走反射。只在投放 HandlerThread 上调用（不在任何系统锁内）。
 *
 * <p>任务接口：{@code ActivityTaskManager.getService()}（IActivityTaskManager）的
 * {@code getAllRootTaskInfosOnDisplay}、{@code moveRootTaskToDisplay}、{@code removeTask}。
 * 用户接口：当前用户、用户解锁状态、按用户创建 Context、按用户启动 Activity 与发送广播。
 */
final class SystemApi {
    private static Object sTaskService;
    private static Method sGetRootTasksOnDisplay;
    private static Method sMoveRootTaskToDisplay;
    private static Method sRemoveTask;
    private static Class<?> sTaskInfoClass;
    private static Field sTaskIdField;
    private static Field sTopActivityField;
    private static Field sBaseActivityField;
    private static Field sVisibleField;
    private static Method sIsVisibleMethod;
    private static Method sActivityTypeMethod;
    private static Method sWindowingModeMethod;

    private SystemApi() {
    }

    /** 系统任务接口调用 / 字段读取失败。 */
    static final class TaskApiException extends Exception {
        TaskApiException(@NonNull String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    /** 反射拿 IActivityTaskManager 与读、移两个必需方法；只在首次成功时查找。缺失时返回原因，成功返回 null。 */
    @Nullable
    static String resolveTaskApi() {
        if (sTaskService != null && sGetRootTasksOnDisplay != null && sMoveRootTaskToDisplay != null) {
            return null;
        }
        try {
            Class<?> atm = Class.forName("android.app.ActivityTaskManager");
            Object service = atm.getMethod("getService").invoke(null);
            if (service == null) return "IActivityTaskManager unavailable";
            Method getInfos = findPublicMethod(service.getClass(), "getAllRootTaskInfosOnDisplay", int.class);
            Method move = findPublicMethod(service.getClass(), "moveRootTaskToDisplay", int.class, int.class);
            if (getInfos == null || move == null) {
                return "task api missing, getInfos=" + (getInfos != null) + " move=" + (move != null);
            }
            sRemoveTask = findPublicMethod(service.getClass(), "removeTask", int.class);
            sGetRootTasksOnDisplay = getInfos;
            sMoveRootTaskToDisplay = move;
            sTaskService = service;
            return null;
        } catch (Throwable error) {
            return "task api lookup failed: " + error;
        }
    }

    /** 读出某块屏上的根任务（系统按 z 序自上而下返回）。读不出关键字段时抛 TaskApiException。 */
    @NonNull
    static List<TaskEntry> readRootTasks(int displayId) throws TaskApiException {
        Object value;
        try {
            value = sGetRootTasksOnDisplay.invoke(sTaskService, displayId);
        } catch (Throwable error) {
            throw new TaskApiException("getAllRootTaskInfosOnDisplay(" + displayId + ") failed", error);
        }
        List<TaskEntry> entries = new ArrayList<>();
        if (!(value instanceof List<?>)) return entries;
        for (Object info : (List<?>) value) {
            if (info == null) continue;
            entries.add(toEntry(info));
        }
        return entries;
    }

    /** 移动根任务；系统拒绝时抛出原因。 */
    static void moveRootTask(int rootTaskId, int displayId) throws Throwable {
        try {
            sMoveRootTaskToDisplay.invoke(sTaskService, rootTaskId, displayId);
        } catch (InvocationTargetException error) {
            throw error.getCause() != null ? error.getCause() : error;
        }
    }

    /** 结束任务；接口缺失或系统拒绝时返回 false。 */
    static boolean removeTask(int taskId) {
        if (sRemoveTask == null || sTaskService == null) return false;
        try {
            return Boolean.TRUE.equals(sRemoveTask.invoke(sTaskService, taskId));
        } catch (Throwable error) {
            return false;
        }
    }

    @NonNull
    private static TaskEntry toEntry(@NonNull Object info) throws TaskApiException {
        resolveTaskInfoMembers(info.getClass());
        try {
            int taskId = sTaskIdField.getInt(info);
            String packageName = packageOf(sTopActivityField.get(info));
            if (packageName == null && sBaseActivityField != null) {
                packageName = packageOf(sBaseActivityField.get(info));
            }
            int activityType = ((Number) sActivityTypeMethod.invoke(info)).intValue();
            int windowingMode = sWindowingModeMethod == null
                    ? RearCastPolicy.UNKNOWN
                    : ((Number) sWindowingModeMethod.invoke(info)).intValue();
            boolean visible;
            if (sVisibleField != null) {
                visible = sVisibleField.getBoolean(info);
            } else if (sIsVisibleMethod != null) {
                visible = Boolean.TRUE.equals(sIsVisibleMethod.invoke(info));
            } else {
                visible = false;
            }
            return new TaskEntry(taskId, packageName, activityType, windowingMode, visible);
        } catch (Throwable error) {
            throw new TaskApiException("RootTaskInfo read failed", error);
        }
    }

    @Nullable
    private static String packageOf(@Nullable Object component) {
        return component instanceof ComponentName ? ((ComponentName) component).getPackageName() : null;
    }

    /**
     * RootTaskInfo（继承 TaskInfo）的成员：taskId / topActivity 是公开字段，getActivityType /
     * getWindowingMode / isVisible 是隐藏方法，visible 是 RootTaskInfo 自己的字段。
     * 必需项（taskId、topActivity、getActivityType）缺失时抛异常，按「系统接口不可用」处理。
     */
    private static void resolveTaskInfoMembers(@NonNull Class<?> type) throws TaskApiException {
        if (sTaskInfoClass == type) return;
        try {
            Field taskId = type.getField("taskId");
            Field topActivity = type.getField("topActivity");
            Method activityType = type.getMethod("getActivityType");
            sBaseActivityField = optionalField(type, "baseActivity");
            sVisibleField = optionalField(type, "visible");
            if (sVisibleField != null && sVisibleField.getType() != boolean.class) sVisibleField = null;
            sIsVisibleMethod = optionalMethod(type, "isVisible");
            sWindowingModeMethod = optionalMethod(type, "getWindowingMode");
            sTaskIdField = taskId;
            sTopActivityField = topActivity;
            sActivityTypeMethod = activityType;
            sTaskInfoClass = type;
        } catch (Throwable error) {
            sTaskInfoClass = null;
            throw new TaskApiException("RootTaskInfo members missing on " + type.getName(), error);
        }
    }

    // ---- 用户 ----

    /** ActivityManager.getCurrentUser()；取不到返回 {@link RearCastPolicy#UNKNOWN}。 */
    static int currentUserId() {
        try {
            Object value = Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null);
            return value instanceof Integer ? (Integer) value : RearCastPolicy.UNKNOWN;
        } catch (Throwable error) {
            return RearCastPolicy.UNKNOWN;
        }
    }

    @NonNull
    static UserHandle userHandle(int userId) throws Exception {
        return (UserHandle) UserHandle.class.getMethod("of", int.class).invoke(null, userId);
    }

    /** 用户的凭据加密存储已解锁（开机首次凭据验证之后）。 */
    static boolean isUserUnlocked(@NonNull Context context, int userId) {
        try {
            UserManager manager = context.getSystemService(UserManager.class);
            if (manager == null) return false;
            Object value = UserManager.class.getMethod("isUserUnlocked", UserHandle.class)
                    .invoke(manager, userHandle(userId));
            return Boolean.TRUE.equals(value);
        } catch (Throwable error) {
            return false;
        }
    }

    /** 设备上只有系统用户。查询失败时按多用户处理。 */
    static boolean onlySystemUser(@NonNull Context context) {
        try {
            UserManager manager = context.getSystemService(UserManager.class);
            if (manager == null) return false;
            Object value = UserManager.class.getMethod("getUserCount").invoke(manager);
            return value instanceof Integer && (Integer) value == 1;
        } catch (Throwable error) {
            return false;
        }
    }

    /** Context.createContextAsUser(UserHandle, int)。 */
    @NonNull
    static Context contextAsUser(@NonNull Context context, int userId) throws Exception {
        return (Context) Context.class.getMethod("createContextAsUser", UserHandle.class, int.class)
                .invoke(context, userHandle(userId), 0);
    }

    /** Context.startActivityAsUser(Intent, Bundle, UserHandle)。 */
    static void startActivityAsUser(@NonNull Context context, @NonNull Intent intent,
                                    @Nullable Bundle options, int userId) throws Throwable {
        try {
            Context.class.getMethod("startActivityAsUser", Intent.class, Bundle.class, UserHandle.class)
                    .invoke(context, intent, options, userHandle(userId));
        } catch (InvocationTargetException error) {
            throw error.getCause() != null ? error.getCause() : error;
        }
    }

    /**
     * 按用户发送广播并公开发送方身份（Context.sendBroadcastAsUser(Intent, UserHandle, String, Bundle)）；
     * 该重载缺失时退回不带选项的公开重载。
     */
    static void sendBroadcastAsUser(@NonNull Context context, @NonNull Intent intent, int userId,
                                    @Nullable Bundle options) throws Exception {
        UserHandle user = userHandle(userId);
        try {
            Context.class.getMethod("sendBroadcastAsUser", Intent.class, UserHandle.class, String.class, Bundle.class)
                    .invoke(context, intent, user, null, options);
        } catch (NoSuchMethodException missing) {
            context.sendBroadcastAsUser(intent, user);
        }
    }

    // ---- 反射工具 ----

    @Nullable
    static Field optionalField(@NonNull Class<?> type, @NonNull String name) {
        try {
            Field field = type.getField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    static Method optionalMethod(@NonNull Class<?> type, @NonNull String name) {
        try {
            Method method = type.getMethod(name);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    static Method findPublicMethod(@NonNull Class<?> type, @NonNull String name, @NonNull Class<?>... params) {
        try {
            Method method = type.getMethod(name, params);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    static Object readField(@Nullable Object target, @NonNull String name) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }
}
