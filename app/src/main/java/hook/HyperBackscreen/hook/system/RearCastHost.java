package hook.HyperBackscreen.hook.system;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.common.RearCastPolicy;
import hook.HyperBackscreen.common.RearCastPolicy.Op;
import hook.HyperBackscreen.common.RearCastPolicy.TaskEntry;
import hook.HyperBackscreen.hook.ModuleMain;

/**
 * 「应用投放到背屏 / 拉回主屏」在 system_server 里的执行端。
 *
 * <p>时机：Hook {@code ActivityManagerService#systemReady}，原方法返回之后（系统服务都已就绪）
 * 起一个 HandlerThread，在系统 Context 上注册导出的接收器，回调全部在这条线程上跑，
 * 不占 system_server 主线程，也不在任何系统锁内。
 *
 * <p>鉴权：发送方必须用 {@code BroadcastOptions#setShareIdentityEnabled(true)} 公开身份；
 * 这里用 {@link BroadcastReceiver#getSentFromPackage()} / {@link BroadcastReceiver#getSentFromUid()}
 * 取到包名与 uid，包名只认背屏中心与模块 App，且 uid 必须与该包的实际 uid 一致；
 * 拿不到身份就拒绝。
 *
 * <p>执行：反射 {@code ActivityTaskManager.getService()}（IActivityTaskManager），用
 * {@code getAllRootTaskInfosOnDisplay} 读两块屏的根任务，{@code moveRootTaskToDisplay} 移动；
 * 只移动已经在跑的任务，不在背屏上启动 Activity（那条路径会被 MIUI 的 handleSubScreen 拦成 102）。
 *
 * <p>稳妥性：system_server 最敏感 —— 安装、注册、每次请求都 try/catch 全包，异常只记日志；
 * 任一环节失败只让本功能不可用（发送方收到对应结果码），不影响开机与其它 Hook。
 */
public final class RearCastHost {
    private static final String TAG = Constants.LOG_TAG;
    private static final String LOG_LABEL = "Rear cast";
    /** 单次请求耗时告警阈值。 */
    private static final long SLOW_REQUEST_MS = 500L;
    /** UserHandle.PER_USER_RANGE（隐藏常量）：uid % 它 = appId，跨用户比较用。 */
    private static final int PER_USER_UID_RANGE = 100_000;

    private static final Object LOCK = new Object();
    private static volatile boolean hookInstalled;
    private static volatile boolean receiverRegistered;

    // 反射成员按需查找后缓存（只在 HandlerThread 上访问）。
    private static Object sTaskService;
    private static Method sGetRootTasksOnDisplay;
    private static Method sMoveRootTaskToDisplay;
    private static Class<?> sTaskInfoClass;
    private static Field sTaskIdField;
    private static Field sTopActivityField;
    private static Field sBaseActivityField;
    private static Field sVisibleField;
    private static Method sIsVisibleMethod;
    private static Method sActivityTypeMethod;
    private static Method sWindowingModeMethod;

    private RearCastHost() {
    }

    /**
     * 挂 systemReady。用传进来的 ClassLoader 找不到 AMS 时静默返回，等下一次（换 ClassLoader）再试；
     * 装成功一次之后重复调用直接返回。
     */
    public static void install(@NonNull ModuleMain module, @Nullable ClassLoader classLoader) {
        if (hookInstalled || classLoader == null) return;
        try {
            synchronized (LOCK) {
                if (hookInstalled) return;
                Class<?> amsClass;
                try {
                    amsClass = Class.forName(Constants.SYSTEM_ACTIVITY_MANAGER_SERVICE_CLASS, false, classLoader);
                } catch (Throwable notFound) {
                    return;
                }
                Method systemReady = null;
                for (Method method : amsClass.getDeclaredMethods()) {
                    if (Constants.SYSTEM_READY_METHOD.equals(method.getName())) {
                        systemReady = method;
                        break;
                    }
                }
                boolean installed = module.hookMethodIfPresent(
                        systemReady,
                        Constants.SYSTEM_ACTIVITY_MANAGER_SERVICE_CLASS + "#" + Constants.SYSTEM_READY_METHOD,
                        chain -> {
                            Object result = chain.proceed();
                            try {
                                registerReceiver(module, chain.getThisObject());
                            } catch (Throwable error) {
                                module.log(Log.ERROR, TAG, LOG_LABEL + ": receiver registration failed", error);
                            }
                            return result;
                        });
                // 找到了 AMS 就不再重试：方法缺失是 ROM 差异，换 ClassLoader 也一样。
                hookInstalled = true;
                if (!installed) {
                    module.log(Log.WARN, TAG, LOG_LABEL + ": systemReady hook unavailable, feature disabled");
                }
            }
        } catch (Throwable error) {
            module.log(Log.ERROR, TAG, LOG_LABEL + ": install failed", error);
        }
    }

    /** systemReady 返回后调用；只注册一次。 */
    private static void registerReceiver(@NonNull ModuleMain module, @Nullable Object activityManagerService) {
        synchronized (LOCK) {
            if (receiverRegistered) return;
            Context context = resolveSystemContext(activityManagerService);
            if (context == null) {
                module.log(Log.WARN, TAG, LOG_LABEL + ": system context unavailable, feature disabled");
                return;
            }
            HandlerThread thread = new HandlerThread("MiBackscreenRearCast", Process.THREAD_PRIORITY_DEFAULT);
            thread.start();
            Handler handler = new Handler(thread.getLooper());
            IntentFilter filter = new IntentFilter(Constants.ACTION_REAR_CAST);
            try {
                context.registerReceiver(new CastReceiver(module), filter, null, handler,
                        Context.RECEIVER_EXPORTED);
            } catch (Throwable error) {
                thread.quitSafely();
                throw error;
            }
            receiverRegistered = true;
            module.log(Log.INFO, TAG, LOG_LABEL + ": receiver registered");
        }
    }

    @Nullable
    private static Context resolveSystemContext(@Nullable Object activityManagerService) {
        Object value = readField(activityManagerService, "mContext");
        if (value instanceof Context) return (Context) value;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getDeclaredMethod("currentActivityThread").invoke(null);
            Object context = thread == null
                    ? null
                    : activityThread.getDeclaredMethod("getSystemContext").invoke(thread);
            return context instanceof Context ? (Context) context : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 接收器本体；onReceive 在 MiBackscreenRearCast 线程上执行。 */
    private static final class CastReceiver extends BroadcastReceiver {
        private final ModuleMain module;

        CastReceiver(@NonNull ModuleMain module) {
            this.module = module;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            int code = Constants.REAR_CAST_RESULT_MOVE_FAILED;
            String data = null;
            long startedAt = SystemClock.elapsedRealtime();
            String sender = null;
            String opValue = null;
            try {
                sender = getSentFromPackage();
                int senderUid = getSentFromUid();
                opValue = intent == null ? null : intent.getStringExtra(Constants.EXTRA_REAR_CAST_OP);
                if (!RearCastPolicy.isAllowedSender(sender) || !uidOwnsPackage(context, senderUid, sender)) {
                    code = Constants.REAR_CAST_RESULT_DENIED;
                    module.log(Log.WARN, TAG, LOG_LABEL + ": request denied, sender=" + sender
                            + " uid=" + senderUid);
                } else {
                    Op op = Op.fromExtra(opValue);
                    if (op == null) {
                        code = Constants.REAR_CAST_RESULT_BAD_REQUEST;
                    } else {
                        Result result = execute(module, context, op);
                        code = result.code;
                        data = result.packageName;
                    }
                }
            } catch (Throwable error) {
                code = Constants.REAR_CAST_RESULT_MOVE_FAILED;
                module.log(Log.WARN, TAG, LOG_LABEL + ": request failed", error);
            }
            long cost = SystemClock.elapsedRealtime() - startedAt;
            if (code != Constants.REAR_CAST_RESULT_REAR_HAS_APP
                    && code != Constants.REAR_CAST_RESULT_REAR_EMPTY) {
                module.log(Log.INFO, TAG, LOG_LABEL + ": op=" + opValue + " from " + sender
                        + " -> code=" + code + (data == null ? "" : " " + data) + " in " + cost + " ms");
            } else if (cost >= SLOW_REQUEST_MS) {
                module.log(Log.WARN, TAG, LOG_LABEL + ": query took " + cost + " ms");
            }
            try {
                if (isOrderedBroadcast()) setResult(code, data, null);
            } catch (Throwable error) {
                module.log(Log.WARN, TAG, LOG_LABEL + ": set result failed", error);
            }
        }
    }

    /** 包名对应的 uid 与发送方 uid 是同一个应用（appId 相同，允许不同用户）。 */
    private static boolean uidOwnsPackage(@NonNull Context context, int uid, @Nullable String packageName) {
        if (packageName == null || uid < 0) return false;
        long token = Binder.clearCallingIdentity();
        try {
            int packageUid = context.getPackageManager()
                    .getPackageUid(packageName, PackageManager.PackageInfoFlags.of(0));
            return packageUid % PER_USER_UID_RANGE == uid % PER_USER_UID_RANGE;
        } catch (Throwable ignored) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static final class Result {
        final int code;
        @Nullable
        final String packageName;

        Result(int code, @Nullable String packageName) {
            this.code = code;
            this.packageName = packageName;
        }
    }

    @NonNull
    private static Result execute(@NonNull ModuleMain module, @NonNull Context context, @NonNull Op op) {
        if (!resolveTaskApi(module)) {
            return new Result(Constants.REAR_CAST_RESULT_API_UNAVAILABLE, null);
        }
        long token = Binder.clearCallingIdentity();
        try {
            List<TaskEntry> rear = readRootTasks(Constants.SYSTEM_SECONDARY_DISPLAY_ID);
            TaskEntry rearTarget = RearCastPolicy.pickRearToMain(rear);
            if (op == Op.QUERY) {
                return rearTarget == null
                        ? new Result(Constants.REAR_CAST_RESULT_REAR_EMPTY, null)
                        : new Result(Constants.REAR_CAST_RESULT_REAR_HAS_APP, rearTarget.packageName);
            }
            Op effective = op == Op.TOGGLE ? RearCastPolicy.resolveToggle(rearTarget != null) : op;
            if (effective == Op.TO_MAIN) {
                if (rearTarget == null) return new Result(Constants.REAR_CAST_RESULT_NO_APP_ON_REAR, null);
                return move(module, rearTarget, Constants.SYSTEM_MAIN_DISPLAY_ID,
                        Constants.REAR_CAST_RESULT_MOVED_TO_MAIN);
            }
            // 锁屏（需要凭据）时主屏任务仍可能报告为可见；移到背屏会绕过锁屏显示内容，直接拒绝。
            // 拉回主屏不受影响：回到主屏后仍在锁屏之下。
            if (isDeviceLocked(module, context)) return new Result(Constants.REAR_CAST_RESULT_LOCKED, null);
            TaskEntry mainTarget = RearCastPolicy.pickMainToRear(readRootTasks(Constants.SYSTEM_MAIN_DISPLAY_ID));
            if (mainTarget == null) return new Result(Constants.REAR_CAST_RESULT_NO_APP_ON_MAIN, null);
            Result result = move(module, mainTarget, Constants.SYSTEM_SECONDARY_DISPLAY_ID,
                    Constants.REAR_CAST_RESULT_MOVED_TO_REAR);
            if (result.code == Constants.REAR_CAST_RESULT_MOVED_TO_REAR) {
                // 背屏可能正熄着：移过去之后点亮，让用户立刻看到。失败只记日志，不影响结果。
                RearScreenScheduleHost.requestRearWake(module, "MiBackscreenRearCast", LOG_LABEL);
            }
            return result;
        } catch (TaskApiException error) {
            module.log(Log.WARN, TAG, LOG_LABEL + ": task api failed", error);
            return new Result(Constants.REAR_CAST_RESULT_API_UNAVAILABLE, null);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /** 设备是否处于需要凭据解锁的锁定状态；查询失败时按已锁定处理（宁可不投放）。 */
    private static boolean isDeviceLocked(@NonNull ModuleMain module, @NonNull Context context) {
        try {
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            return keyguard == null || keyguard.isDeviceLocked();
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, LOG_LABEL + ": keyguard state unavailable", error);
            return true;
        }
    }

    @NonNull
    private static Result move(@NonNull ModuleMain module, @NonNull TaskEntry target, int displayId, int successCode) {
        try {
            sMoveRootTaskToDisplay.invoke(sTaskService, target.rootTaskId, displayId);
        } catch (InvocationTargetException error) {
            module.log(Log.WARN, TAG, LOG_LABEL + ": move " + target + " to display " + displayId
                    + " rejected", error.getCause() != null ? error.getCause() : error);
            return new Result(Constants.REAR_CAST_RESULT_MOVE_FAILED, target.packageName);
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, LOG_LABEL + ": move " + target + " failed", error);
            return new Result(Constants.REAR_CAST_RESULT_MOVE_FAILED, target.packageName);
        }
        // 方法本身不返回结果：再读一次目标屏，确认任务确实过去了。
        try {
            if (!RearCastPolicy.containsTask(readRootTasks(displayId), target.rootTaskId)) {
                module.log(Log.WARN, TAG, LOG_LABEL + ": " + target + " not found on display "
                        + displayId + " after move");
                return new Result(Constants.REAR_CAST_RESULT_MOVE_FAILED, target.packageName);
            }
        } catch (Throwable error) {
            // 读不到不代表没移成功；以 move 未抛异常为准。
            module.log(Log.DEBUG, TAG, LOG_LABEL + ": verify after move skipped: " + error);
        }
        module.log(Log.INFO, TAG, LOG_LABEL + ": moved " + target + " to display " + displayId);
        return new Result(successCode, target.packageName);
    }

    /** 反射拿 IActivityTaskManager 与两个方法；只在首次成功时查找。 */
    private static boolean resolveTaskApi(@NonNull ModuleMain module) {
        if (sTaskService != null && sGetRootTasksOnDisplay != null && sMoveRootTaskToDisplay != null) {
            return true;
        }
        try {
            Class<?> atm = Class.forName("android.app.ActivityTaskManager");
            Object service = atm.getMethod("getService").invoke(null);
            if (service == null) {
                module.log(Log.WARN, TAG, LOG_LABEL + ": IActivityTaskManager unavailable");
                return false;
            }
            Method getInfos = findPublicMethod(service.getClass(), "getAllRootTaskInfosOnDisplay", int.class);
            Method move = findPublicMethod(service.getClass(), "moveRootTaskToDisplay", int.class, int.class);
            if (getInfos == null || move == null) {
                module.log(Log.WARN, TAG, LOG_LABEL + ": task api missing, getInfos=" + (getInfos != null)
                        + " move=" + (move != null));
                return false;
            }
            sGetRootTasksOnDisplay = getInfos;
            sMoveRootTaskToDisplay = move;
            sTaskService = service;
            return true;
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, LOG_LABEL + ": task api lookup failed", error);
            return false;
        }
    }

    /** 读出某块屏上的根任务（系统按 z 序自上而下返回）。读不出关键字段时抛 TaskApiException。 */
    @NonNull
    private static List<TaskEntry> readRootTasks(int displayId) throws TaskApiException {
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

    @Nullable
    private static Field optionalField(@NonNull Class<?> type, @NonNull String name) {
        try {
            Field field = type.getField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Method optionalMethod(@NonNull Class<?> type, @NonNull String name) {
        try {
            Method method = type.getMethod(name);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Method findPublicMethod(@NonNull Class<?> type, @NonNull String name, @NonNull Class<?>... params) {
        try {
            Method method = type.getMethod(name, params);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Object readField(@Nullable Object target, @NonNull String name) {
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

    /** 系统任务接口调用 / 字段读取失败。 */
    private static final class TaskApiException extends Exception {
        TaskApiException(@NonNull String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }
}
