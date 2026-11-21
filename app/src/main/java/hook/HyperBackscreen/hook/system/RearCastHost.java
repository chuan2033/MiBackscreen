package hook.HyperBackscreen.hook.system;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.List;

import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.common.RearCastPolicy;
import hook.HyperBackscreen.common.RearCastPolicy.Op;
import hook.HyperBackscreen.common.RearCastPolicy.TaskEntry;
import hook.HyperBackscreen.hook.ModuleMain;

/**
 * 「应用投放到背屏 / 拉回主屏」与背屏桌面会话在 system_server 里的接收端。
 *
 * <p>时机：Hook {@code ActivityManagerService#systemReady}，原方法返回之后（系统服务都已就绪）
 * 起一个 HandlerThread，在系统 Context 上注册导出的接收器，回调全部在这条线程上跑，
 * 不占 system_server 主线程，也不在任何系统锁内。
 *
 * <p>鉴权：发送方必须用 {@code BroadcastOptions#setShareIdentityEnabled(true)} 公开身份；
 * 这里用 {@link BroadcastReceiver#getSentFromPackage()} / {@link BroadcastReceiver#getSentFromUid()}
 * 取到包名与 uid。包名先按通道规则（背屏中心与模块 App），再按操作类型检查；该包在发送者所属用户下的 uid
 * 必须与发送方 uid 一致，且发送者所属用户是当前已解锁的前台用户。拿不到身份就拒绝。
 *
 * <p>执行：常规投放用 {@link SystemApi} 读两块屏的根任务并移动已经在跑的任务；背屏桌面会话的操作转给
 * {@link RearDesktopHost}。
 *
 * <p>稳妥性：system_server 最敏感 —— 安装、注册、每次请求都 try/catch 全包，异常只记日志；
 * 任一环节失败只让本功能不可用（发送方收到对应结果码），不影响开机与其它 Hook。
 */
public final class RearCastHost {
    private static final String TAG = Constants.LOG_TAG;
    private static final String LOG_LABEL = "Rear cast";
    /** 单次请求耗时告警阈值。 */
    private static final long SLOW_REQUEST_MS = 500L;

    private static final Object LOCK = new Object();
    private static volatile boolean hookInstalled;
    private static volatile boolean receiverRegistered;

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
            // 会话管理：恢复上次没正常结束的背屏显示参数，注册用户、显示与屏幕状态监听。
            handler.post(() -> RearDesktopHost.start(module, context, handler));
        }
    }

    @Nullable
    private static Context resolveSystemContext(@Nullable Object activityManagerService) {
        Object value = SystemApi.readField(activityManagerService, "mContext");
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

    /** 已验证的发送方。 */
    static final class Sender {
        @NonNull
        final String packageName;
        final int uid;
        final int userId;

        Sender(@NonNull String packageName, int uid) {
            this.packageName = packageName;
            this.uid = uid;
            this.userId = RearCastPolicy.userIdOf(uid);
        }
    }

    /** 一次请求的结果：结果码、resultData 与 resultExtras。 */
    static final class Reply {
        final int code;
        @Nullable
        final String data;
        @Nullable
        final Bundle extras;

        Reply(int code, @Nullable String data, @Nullable Bundle extras) {
            this.code = code;
            this.data = data;
            this.extras = extras;
        }

        static Reply of(int code) {
            return new Reply(code, null, null);
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
            Reply reply = Reply.of(Constants.REAR_CAST_RESULT_MOVE_FAILED);
            long startedAt = SystemClock.elapsedRealtime();
            String senderPackage = null;
            String opValue = null;
            try {
                senderPackage = getSentFromPackage();
                int senderUid = getSentFromUid();
                opValue = intent == null ? null : intent.getStringExtra(Constants.EXTRA_REAR_CAST_OP);
                Op op = Op.fromExtra(opValue);
                if (!RearCastPolicy.isAllowedSender(senderPackage)
                        || (op != null && !RearCastPolicy.isAllowedSenderFor(op, senderPackage))
                        || !identityAccepted(context, senderUid, senderPackage)) {
                    reply = Reply.of(Constants.REAR_CAST_RESULT_DENIED);
                    module.log(Log.WARN, TAG, LOG_LABEL + ": request denied, op=" + opValue
                            + " sender=" + senderPackage + " uid=" + senderUid);
                } else if (op == null) {
                    reply = Reply.of(Constants.REAR_CAST_RESULT_BAD_REQUEST);
                } else if (op.isDesktopOp()) {
                    reply = RearDesktopHost.handle(op, intent, new Sender(senderPackage, senderUid));
                } else {
                    reply = execute(module, context, op);
                }
            } catch (Throwable error) {
                reply = Reply.of(Constants.REAR_CAST_RESULT_MOVE_FAILED);
                module.log(Log.WARN, TAG, LOG_LABEL + ": request failed", error);
            }
            long cost = SystemClock.elapsedRealtime() - startedAt;
            boolean quietQuery = reply.code == Constants.REAR_CAST_RESULT_REAR_HAS_APP
                    || reply.code == Constants.REAR_CAST_RESULT_REAR_EMPTY
                    || Constants.REAR_CAST_OP_PANEL_QUERY.equals(opValue);
            if (!quietQuery) {
                module.log(Log.INFO, TAG, LOG_LABEL + ": op=" + opValue + " from " + senderPackage
                        + " -> code=" + reply.code + (reply.data == null ? "" : " " + reply.data)
                        + " in " + cost + " ms");
            } else if (cost >= SLOW_REQUEST_MS) {
                module.log(Log.WARN, TAG, LOG_LABEL + ": query took " + cost + " ms");
            }
            try {
                if (isOrderedBroadcast()) setResult(reply.code, reply.data, reply.extras);
            } catch (Throwable error) {
                module.log(Log.WARN, TAG, LOG_LABEL + ": set result failed", error);
            }
        }
    }

    /**
     * 发送方 uid 与该包在发送者所属用户下的 uid 完全一致，发送者所属用户是当前已解锁的前台用户。
     */
    private static boolean identityAccepted(@NonNull Context context, int senderUid, @Nullable String packageName) {
        if (packageName == null || senderUid < 0) return false;
        long token = Binder.clearCallingIdentity();
        try {
            int userId = RearCastPolicy.userIdOf(senderUid);
            int packageUid;
            try {
                packageUid = SystemApi.contextAsUser(context, userId).getPackageManager()
                        .getPackageUid(packageName, PackageManager.PackageInfoFlags.of(0));
            } catch (Throwable notFound) {
                packageUid = -1;
            }
            return RearCastPolicy.isSenderIdentityAccepted(senderUid, packageUid,
                    SystemApi.currentUserId(), SystemApi.isUserUnlocked(context, userId));
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @NonNull
    private static Reply execute(@NonNull ModuleMain module, @NonNull Context context, @NonNull Op op) {
        String missing = SystemApi.resolveTaskApi();
        if (missing != null) {
            module.log(Log.WARN, TAG, LOG_LABEL + ": " + missing);
            return Reply.of(Constants.REAR_CAST_RESULT_API_UNAVAILABLE);
        }
        long token = Binder.clearCallingIdentity();
        try {
            List<TaskEntry> rear = SystemApi.readRootTasks(Constants.SYSTEM_SECONDARY_DISPLAY_ID);
            TaskEntry rearTarget = RearCastPolicy.pickRearToMain(rear);
            if (op == Op.QUERY) {
                return rearTarget == null
                        ? Reply.of(Constants.REAR_CAST_RESULT_REAR_EMPTY)
                        : new Reply(Constants.REAR_CAST_RESULT_REAR_HAS_APP, rearTarget.packageName, null);
            }
            Op effective = op == Op.TOGGLE ? RearCastPolicy.resolveToggle(rearTarget != null) : op;
            if (effective == Op.TO_MAIN) {
                if (rearTarget == null) return Reply.of(Constants.REAR_CAST_RESULT_NO_APP_ON_REAR);
                return move(module, rearTarget, Constants.SYSTEM_MAIN_DISPLAY_ID,
                        Constants.REAR_CAST_RESULT_MOVED_TO_MAIN);
            }
            // 锁屏（需要凭据）时主屏任务仍可能报告为可见；移到背屏会绕过锁屏显示内容，直接拒绝。
            // 拉回主屏不受影响：回到主屏后仍在锁屏之下。
            if (isDeviceLocked(module, context)) return Reply.of(Constants.REAR_CAST_RESULT_LOCKED);
            TaskEntry mainTarget = RearCastPolicy.pickMainToRear(SystemApi.readRootTasks(Constants.SYSTEM_MAIN_DISPLAY_ID));
            if (mainTarget == null) return Reply.of(Constants.REAR_CAST_RESULT_NO_APP_ON_MAIN);
            Reply result = move(module, mainTarget, Constants.SYSTEM_SECONDARY_DISPLAY_ID,
                    Constants.REAR_CAST_RESULT_MOVED_TO_REAR);
            if (result.code == Constants.REAR_CAST_RESULT_MOVED_TO_REAR) {
                // 背屏可能正熄着：移过去之后点亮，让用户立刻看到。失败只记日志，不影响结果。
                RearScreenScheduleHost.requestRearWake(module, "MiBackscreenRearCast", LOG_LABEL);
            }
            return result;
        } catch (SystemApi.TaskApiException error) {
            module.log(Log.WARN, TAG, LOG_LABEL + ": task api failed", error);
            return Reply.of(Constants.REAR_CAST_RESULT_API_UNAVAILABLE);
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
    private static Reply move(@NonNull ModuleMain module, @NonNull TaskEntry target, int displayId, int successCode) {
        try {
            SystemApi.moveRootTask(target.rootTaskId, displayId);
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, LOG_LABEL + ": move " + target + " to display " + displayId
                    + " rejected", error);
            return new Reply(Constants.REAR_CAST_RESULT_MOVE_FAILED, target.packageName, null);
        }
        // 方法本身不返回结果：再读一次目标屏，确认任务确实过去了。
        try {
            if (!RearCastPolicy.containsTask(SystemApi.readRootTasks(displayId), target.rootTaskId)) {
                module.log(Log.WARN, TAG, LOG_LABEL + ": " + target + " not found on display "
                        + displayId + " after move");
                return new Reply(Constants.REAR_CAST_RESULT_MOVE_FAILED, target.packageName, null);
            }
        } catch (Throwable error) {
            // 读不到不代表没移成功；以 move 未抛异常为准。
            module.log(Log.DEBUG, TAG, LOG_LABEL + ": verify after move skipped: " + error);
        }
        module.log(Log.INFO, TAG, LOG_LABEL + ": moved " + target + " to display " + displayId);
        return new Reply(successCode, target.packageName, null);
    }
}
