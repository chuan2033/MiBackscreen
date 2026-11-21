package hook.HyperBackscreen.hook.system;

import android.app.ActivityOptions;
import android.app.BroadcastOptions;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import hook.HyperBackscreen.bridge.PrefsBridge;
import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.common.RearCastPolicy;
import hook.HyperBackscreen.common.RearCastPolicy.Op;
import hook.HyperBackscreen.common.RearCastPolicy.TaskEntry;
import hook.HyperBackscreen.common.RearDesktopPolicy;
import hook.HyperBackscreen.common.RearDesktopSession;
import hook.HyperBackscreen.common.RearDesktopSession.Decision;
import hook.HyperBackscreen.common.RearDesktopSession.EndReason;
import hook.HyperBackscreen.hook.ModuleMain;

/**
 * 背屏桌面会话在 system_server 里的执行端。请求由 {@link RearCastHost} 在投放 HandlerThread 上转进来，
 * 计时、任务快照与状态通知也都在这条线程上执行，会话状态由 {@link RearDesktopSession} 串行推进。
 *
 * <p>启动：在发送者所属用户下重新解析组件，以系统身份用 {@code ActivityOptions#setLaunchDisplayId}
 * 在背屏启动；确认本次打开的任务在背屏最上层可见后进入显示中，通知背屏中心显示控制条。
 * 从受理到会话结束，ROM 的背屏适用性判定（{@code isShouldShowOnRearDisplay}）只对本次会话的应用放行
 * （{@link #allowsSessionApp}）；锁屏时 ROM 另查的内置名单不变，结果经 {@link #onRomLaunchDecision} 记录。
 *
 * <p>显示参数：调整背屏密度与方向前先把原值写进 {@link Constants#SETTING_REAR_DESKTOP_RESTORE}，
 * 会话结束、启动失败或开机发现残留记录时恢复；恢复失败保留记录，后续会话在恢复完成后才再调整。
 *
 * <p>任务变化：{@code TaskChangeNotificationController} 的通知 Hook 调 {@link #onTaskChanged}，
 * 只安排一次锁外的快照核对；用户切换、背屏移除与屏幕状态变化由这里注册的监听处理。
 *
 * <p>所有系统接口都走反射并整段 try/catch：缺失或报错只让本功能不可用，不影响 system_server。
 */
final class RearDesktopHost {
    private static final String TAG = Constants.LOG_TAG;
    private static final String LOG_LABEL = "Rear desktop";
    /** 任务通知后等这么久再核对，合并同一轮切换里的多次通知。 */
    private static final long CHECK_DEBOUNCE_MS = 120L;
    /** 启动与结束阶段的核对间隔。 */
    private static final long TRANSITION_POLL_MS = 150L;
    /** 显示中但任务不可见时的复查间隔。 */
    private static final long UNCONFIRMED_POLL_MS = 500L;
    /** 任务通知 Hook 全部缺失时，显示中的复查间隔。 */
    private static final long FALLBACK_POLL_MS = 1_500L;
    /**
     * 已调研的 ROM（18 Pro Max OS4.0.31.0）在锁屏时只按 SECONDARY_DISPLAY_WHITE_PACKAGE 放行背屏启动，
     * 背屏中心的人脸认证只服务于它自己的界面流程，没有针对单次应用启动的授权接口。
     */
    private static final boolean ROM_LAUNCH_AUTHORIZATION_SUPPORTED = false;
    /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC。 */
    private static final int INJECT_MODE_ASYNC = 0;
    /** Intent.ACTION_USER_SWITCHED（隐藏常量，系统在切换前台用户后发送）。 */
    private static final String ACTION_USER_SWITCHED = "android.intent.action.USER_SWITCHED";

    private static final RearDesktopSession SESSION = new RearDesktopSession();
    private static final Runnable CHECK = RearDesktopHost::checkTasks;

    @Nullable
    private static ModuleMain module;
    @Nullable
    private static Context context;
    @Nullable
    private static volatile Handler handler;

    /** 以下由持锁的系统回调读取或写入，只能是 volatile 基本值。 */
    private static volatile boolean sessionActive;
    private static volatile boolean taskEventsAvailable;
    /** 会话放行的应用与用户：从受理启动到会话结束，ROM 的背屏适用性判定对它放行。 */
    @Nullable
    private static volatile String sessionPackage;
    private static volatile int sessionUserId = RearCastPolicy.UNKNOWN;
    private static volatile boolean sessionAllowLogged;
    @Nullable
    private static volatile String diagnosticPackage;
    private static volatile int diagnosticSessionId;
    private static volatile long diagnosticUntil;
    private static volatile int romDeniedSessionId;

    /** 以下只在投放 HandlerThread 上访问。 */
    private static final Set<Integer> preexistingTasks = new HashSet<>();
    private static int mainTopBeforeLaunch = RearCastPolicy.UNKNOWN;

    private RearDesktopHost() {
    }

    // ---- 安装 ----

    /** 任务通知 Hook 是否装上；全部缺失时显示中改为定时复查。 */
    static void setTaskEventsAvailable(boolean available) {
        taskEventsAvailable = available;
    }

    /** 接收器注册后在投放线程上调用一次：恢复残留的显示参数，注册用户、显示与屏幕状态监听。 */
    static void start(@NonNull ModuleMain owner, @NonNull Context systemContext, @NonNull Handler thread) {
        module = owner;
        context = systemContext;
        handler = thread;
        try {
            if (Settings.Global.getString(systemContext.getContentResolver(),
                    Constants.SETTING_REAR_DESKTOP_RESTORE) != null) {
                restoreDisplay("boot");
            }
        } catch (Throwable error) {
            log(Log.WARN, "restore after boot failed", error);
        }
        try {
            DisplayManager displays = systemContext.getSystemService(DisplayManager.class);
            if (displays != null) {
                displays.registerDisplayListener(new DisplayManager.DisplayListener() {
                    @Override
                    public void onDisplayAdded(int displayId) {
                    }

                    @Override
                    public void onDisplayRemoved(int displayId) {
                        if (displayId == Constants.SYSTEM_SECONDARY_DISPLAY_ID) {
                            apply(SESSION.onSystemEnd(EndReason.DISPLAY_REMOVED));
                        }
                    }

                    @Override
                    public void onDisplayChanged(int displayId) {
                        if (displayId == Constants.SYSTEM_SECONDARY_DISPLAY_ID && sessionActive) scheduleCheck(0L);
                    }
                }, thread);
            }
        } catch (Throwable error) {
            log(Log.WARN, "display listener unavailable", error);
        }
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_USER_SWITCHED);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            systemContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (ACTION_USER_SWITCHED.equals(intent.getAction())) {
                        apply(SESSION.onSystemEnd(EndReason.USER_SWITCHED));
                    } else if (sessionActive) {
                        // 锁屏状态变化：按 ROM 的可见性判定重新核对。
                        scheduleCheck(CHECK_DEBOUNCE_MS);
                    }
                }
            }, filter, null, thread, Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable error) {
            log(Log.WARN, "user and screen receiver unavailable", error);
        }
    }

    // ---- 持锁回调（不做任何可能取锁的调用） ----

    /**
     * ROM 背屏适用性判定为 false 时是否放行：只放行本次会话的应用，且 Activity 属于会话用户。
     * 锁屏时 ROM 在 isAllowedToStartOnRearDisplay 里另查内置名单，这一层不变。
     */
    static boolean allowsSessionApp(@Nullable ActivityInfo info) {
        String allowed = sessionPackage;
        if (allowed == null || info == null || !allowed.equals(info.packageName)) return false;
        if (info.applicationInfo == null || RearCastPolicy.userIdOf(info.applicationInfo.uid) != sessionUserId) {
            return false;
        }
        if (!sessionAllowLogged) {
            sessionAllowLogged = true;
            Handler thread = handler;
            if (thread != null) thread.post(() -> log(Log.INFO, "session app allowed on rear: " + allowed, null));
        }
        return true;
    }

    /** 任务变化通知；会话存在时安排一次锁外核对。 */
    static void onTaskChanged() {
        if (!sessionActive) return;
        scheduleCheck(CHECK_DEBOUNCE_MS);
    }

    /**
     * ROM 背屏启动判定的结果（{@code stage} 为方法名）。只记录本次面板启动的目标包在启动等待期内的判定。
     */
    static void onRomLaunchDecision(@Nullable String packageName, boolean allowed, @NonNull String stage) {
        String target = diagnosticPackage;
        if (target == null || !target.equals(packageName)) return;
        if (SystemClock.elapsedRealtime() > diagnosticUntil) return;
        int sessionId = diagnosticSessionId;
        if (!allowed) romDeniedSessionId = sessionId;
        Handler thread = handler;
        if (thread == null) return;
        thread.post(() -> log(allowed ? Log.INFO : Log.WARN, "ROM " + stage + " " + packageName
                + " -> " + (allowed ? "allow" : "deny") + " session=" + sessionId, null));
        if (!allowed) scheduleCheck(0L);
    }

    private static void scheduleCheck(long delayMs) {
        Handler thread = handler;
        if (thread == null) return;
        thread.removeCallbacks(CHECK);
        thread.postDelayed(CHECK, delayMs);
    }

    // ---- 请求 ----

    @NonNull
    static RearCastHost.Reply handle(@NonNull Op op, @NonNull Intent intent, @NonNull RearCastHost.Sender sender) {
        try {
            if (module == null || context == null) return RearCastHost.Reply.of(Constants.REAR_CAST_RESULT_NOT_HANDLED);
            if (op.isPanelOp() && intent.getIntExtra(Constants.EXTRA_REAR_DESKTOP_PROTOCOL, -1)
                    != Constants.REAR_DESKTOP_PROTOCOL_VERSION) {
                return RearCastHost.Reply.of(Constants.REAR_CAST_RESULT_BAD_REQUEST);
            }
            switch (op) {
                case PANEL_LAUNCH:
                    return launch(intent, sender);
                case PANEL_QUERY:
                    return reply(Constants.REAR_CAST_RESULT_DONE, sender);
                case PANEL_BACK:
                case PANEL_HOME:
                case PANEL_TO_MAIN:
                case PANEL_EXIT:
                    return control(op, intent.getIntExtra(Constants.EXTRA_REAR_DESKTOP_SESSION_ID, 0), sender);
                case DENSITY_REFRESH:
                    return refreshDensity(sender);
                default:
                    return RearCastHost.Reply.of(Constants.REAR_CAST_RESULT_BAD_REQUEST);
            }
        } catch (Throwable error) {
            log(Log.WARN, "request " + op + " failed", error);
            return RearCastHost.Reply.of(Constants.REAR_CAST_RESULT_MOVE_FAILED);
        }
    }

    /** 结果附带会话快照：只把发送者所属用户的会话告诉它。 */
    @NonNull
    private static RearCastHost.Reply reply(int code, @NonNull RearCastHost.Sender sender) {
        Bundle extras = new Bundle();
        boolean sameUser = SESSION.userId() == sender.userId;
        RearDesktopSession.State state = sameUser ? SESSION.state() : RearDesktopSession.State.IDLE;
        extras.putString(Constants.EXTRA_REAR_DESKTOP_STATE, state.wire);
        extras.putInt(Constants.EXTRA_REAR_DESKTOP_SESSION_ID, sameUser ? SESSION.sessionId() : 0);
        if (sameUser && state == RearDesktopSession.State.IDLE && SESSION.lastEndReason() != null) {
            extras.putInt(Constants.EXTRA_REAR_DESKTOP_SESSION_ID, SESSION.lastSessionId());
            extras.putString(Constants.EXTRA_REAR_DESKTOP_REASON, SESSION.lastEndReason().wire);
            extras.putInt(Constants.EXTRA_REAR_DESKTOP_RESULT, SESSION.lastResultCode());
        }
        return new RearCastHost.Reply(code, null, extras);
    }

    @NonNull
    private static RearCastHost.Reply launch(@NonNull Intent intent, @NonNull RearCastHost.Sender sender) {
        String requestId = intent.getStringExtra(Constants.EXTRA_REAR_DESKTOP_REQUEST_ID);
        String flattened = intent.getStringExtra(Constants.EXTRA_REAR_DESKTOP_COMPONENT);
        ComponentName component = flattened == null ? null : ComponentName.unflattenFromString(flattened);
        if (component == null || requestId == null || requestId.isEmpty()) {
            return RearCastHost.Reply.of(Constants.REAR_CAST_RESULT_BAD_REQUEST);
        }
        String missing = SystemApi.resolveTaskApi();
        if (missing != null) {
            log(Log.WARN, missing, null);
            return RearCastHost.Reply.of(Constants.REAR_CAST_RESULT_API_UNAVAILABLE);
        }
        if (resolveLaunchTarget(component, sender) == null) return reply(Constants.REAR_CAST_RESULT_BAD_COMPONENT, sender);
        boolean locked = keyguardLocked();
        Decision decision = SESSION.onLaunchRequest(requestId, sender.userId, component.flattenToString(),
                component.getPackageName(), locked, ROM_LAUNCH_AUTHORIZATION_SUPPORTED, SystemClock.elapsedRealtime());
        switch (decision.kind) {
            case LAUNCH:
                log(Log.INFO, "launch requested session=" + decision.sessionId + " request=" + requestId
                        + " component=" + component.flattenToShortString() + " user=" + sender.userId, null);
                beginLaunch(component, sender.userId, decision.sessionId);
                return reply(Constants.REAR_CAST_RESULT_ACCEPTED, sender);
            case AWAIT_AUTH:
                sessionActive = true;
                int waiting = decision.sessionId;
                postAt(() -> apply(SESSION.onTimeout(waiting, SystemClock.elapsedRealtime())), SESSION.deadline());
                notifyStatus(waiting, RearDesktopSession.State.AWAITING_AUTH, null, Constants.REAR_CAST_RESULT_ACCEPTED);
                return reply(Constants.REAR_CAST_RESULT_ACCEPTED, sender);
            case KNOWN:
                return reply(decision.resultCode, sender);
            default:
                if (decision.resultCode == Constants.REAR_CAST_RESULT_LOCKED) {
                    log(Log.INFO, "launch denied, keyguard locked, component=" + component.flattenToShortString(), null);
                }
                return reply(decision.resultCode, sender);
        }
    }

    /**
     * 在发送者所属用户下重新解析组件：已导出、已启用、出现在桌面入口查询里，面板持有它要求的权限。
     * 不满足时返回 null。
     */
    @Nullable
    private static ActivityInfo resolveLaunchTarget(@NonNull ComponentName component,
                                                    @NonNull RearCastHost.Sender sender) {
        Context system = context;
        if (system == null) return null;
        ActivityInfo info = null;
        boolean enabled = false;
        boolean launcherEntry = false;
        boolean holdsPermission = false;
        try {
            PackageManager pm = SystemApi.contextAsUser(system, sender.userId).getPackageManager();
            try {
                info = pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(
                        PackageManager.GET_META_DATA | PackageManager.MATCH_DISABLED_COMPONENTS));
            } catch (PackageManager.NameNotFoundException notFound) {
                info = null;
            }
            if (info != null) {
                enabled = isEnabled(pm.getComponentEnabledSetting(component), info.enabled)
                        && isEnabled(pm.getApplicationEnabledSetting(component.getPackageName()),
                        info.applicationInfo != null && info.applicationInfo.enabled);
                Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage(component.getPackageName());
                for (ResolveInfo entry : pm.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0))) {
                    if (entry.activityInfo != null
                            && component.getPackageName().equals(entry.activityInfo.packageName)
                            && component.getClassName().equals(entry.activityInfo.name)) {
                        launcherEntry = true;
                        break;
                    }
                }
                holdsPermission = info.permission != null
                        && system.checkPermission(info.permission, -1, sender.uid) == PackageManager.PERMISSION_GRANTED;
            }
        } catch (Throwable error) {
            log(Log.WARN, "resolve " + component.flattenToShortString() + " failed", error);
            return null;
        }
        RearCastPolicy.LaunchTarget target = new RearCastPolicy.LaunchTarget(info != null,
                component.getPackageName(), info != null && info.exported, enabled, launcherEntry,
                info == null ? null : info.permission, holdsPermission);
        if (!RearCastPolicy.isLaunchable(target)) {
            log(Log.INFO, "launch rejected, component=" + component.flattenToShortString()
                    + " resolved=" + target.resolved + " exported=" + target.exported + " enabled=" + target.enabled
                    + " launcher=" + target.launcherEntry + " permission=" + target.requiredPermission, null);
            return null;
        }
        return info;
    }

    private static boolean isEnabled(int setting, boolean manifestDefault) {
        switch (setting) {
            case PackageManager.COMPONENT_ENABLED_STATE_ENABLED:
                return true;
            case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT:
                return manifestDefault;
            default:
                return false;
        }
    }

    private static boolean keyguardLocked() {
        try {
            KeyguardManager keyguard = context == null ? null : context.getSystemService(KeyguardManager.class);
            return keyguard == null || keyguard.isKeyguardLocked();
        } catch (Throwable error) {
            log(Log.WARN, "keyguard state unavailable", error);
            return true;
        }
    }

    private static void beginLaunch(@NonNull ComponentName component, int userId, int sessionId) {
        sessionActive = true;
        prepareDisplay(userId);
        preexistingTasks.clear();
        mainTopBeforeLaunch = RearCastPolicy.UNKNOWN;
        try {
            List<TaskEntry> main = SystemApi.readRootTasks(Constants.SYSTEM_MAIN_DISPLAY_ID);
            for (TaskEntry entry : main) preexistingTasks.add(entry.rootTaskId);
            for (TaskEntry entry : SystemApi.readRootTasks(Constants.SYSTEM_SECONDARY_DISPLAY_ID)) {
                preexistingTasks.add(entry.rootTaskId);
            }
            mainTopBeforeLaunch = RearCastPolicy.topVisibleTaskId(main);
        } catch (Throwable error) {
            log(Log.DEBUG, "pre-launch snapshot skipped: " + error, null);
        }
        sessionUserId = userId;
        sessionAllowLogged = false;
        sessionPackage = component.getPackageName();
        diagnosticPackage = component.getPackageName();
        diagnosticSessionId = sessionId;
        diagnosticUntil = SystemClock.elapsedRealtime() + RearDesktopSession.LAUNCH_TIMEOUT_MS;
        Intent launch = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        Bundle options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(Constants.SYSTEM_SECONDARY_DISPLAY_ID)
                .toBundle();
        try {
            SystemApi.startActivityAsUser(context, launch, options, userId);
        } catch (Throwable error) {
            log(Log.WARN, "start " + component.flattenToShortString() + " failed", error);
            apply(SESSION.onLaunchFailed(sessionId, EndReason.LAUNCH_FAILED));
            return;
        }
        if (module != null) RearScreenScheduleHost.requestRearWake(module, "MiBackscreenRearDesktop", LOG_LABEL);
        postAt(() -> apply(SESSION.onTimeout(sessionId, SystemClock.elapsedRealtime())), SESSION.deadline());
        scheduleCheck(TRANSITION_POLL_MS);
    }

    @NonNull
    private static RearCastHost.Reply control(@NonNull Op op, int sessionId, @NonNull RearCastHost.Sender sender) {
        if (SESSION.state() != RearDesktopSession.State.IDLE && SESSION.userId() != sender.userId) {
            return reply(Constants.REAR_CAST_RESULT_NO_SESSION, sender);
        }
        Decision decision = SESSION.onControl(op, sessionId, SystemClock.elapsedRealtime());
        if (decision.kind == Decision.Kind.KNOWN) return reply(Constants.REAR_CAST_RESULT_DONE, sender);
        if (decision.kind != Decision.Kind.CONTROL) return reply(decision.resultCode, sender);
        int taskId = SESSION.taskId();
        int userId = SESSION.userId();
        boolean done;
        switch (op) {
            case PANEL_BACK:
                done = injectBack();
                scheduleCheck(CHECK_DEBOUNCE_MS);
                return reply(done ? Constants.REAR_CAST_RESULT_DONE : Constants.REAR_CAST_RESULT_MOVE_FAILED, sender);
            case PANEL_HOME:
                done = showRearHome(userId);
                break;
            case PANEL_TO_MAIN:
                done = moveSessionTaskToMain(taskId);
                break;
            case PANEL_EXIT:
            default:
                done = SESSION.taskOwned() && SystemApi.removeTask(taskId);
                if (done) {
                    log(Log.INFO, "removed task " + taskId + " session=" + sessionId, null);
                } else {
                    // 任务不归会话（启动前已存在）或结束失败：回到背屏首页，保留任务。
                    done = showRearHome(userId);
                }
                break;
        }
        if (!done) {
            SESSION.onControlFailed(sessionId);
            return reply(Constants.REAR_CAST_RESULT_MOVE_FAILED, sender);
        }
        log(Log.INFO, "control " + op + " session=" + sessionId, null);
        postAt(() -> apply(SESSION.onTimeout(sessionId, SystemClock.elapsedRealtime())), SESSION.deadline());
        scheduleCheck(TRANSITION_POLL_MS);
        return reply(Constants.REAR_CAST_RESULT_DONE, sender);
    }

    private static boolean moveSessionTaskToMain(int taskId) {
        try {
            if (!RearCastPolicy.containsTask(SystemApi.readRootTasks(Constants.SYSTEM_SECONDARY_DISPLAY_ID), taskId)) {
                log(Log.WARN, "task " + taskId + " not on rear before move", null);
                return false;
            }
            SystemApi.moveRootTask(taskId, Constants.SYSTEM_MAIN_DISPLAY_ID);
            return true;
        } catch (Throwable error) {
            log(Log.WARN, "move task " + taskId + " to main failed", error);
            return false;
        }
    }

    /** 在背屏显示背屏中心首页（SubScreenLauncher 是 singleTask 的 SECONDARY_HOME）。 */
    private static boolean showRearHome(int userId) {
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_SECONDARY_HOME)
                .setClassName(Constants.TARGET_PACKAGE, Constants.SUBSCREEN_LAUNCHER_ACTIVITY_CLASS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Bundle options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(Constants.SYSTEM_SECONDARY_DISPLAY_ID)
                .toBundle();
        try {
            SystemApi.startActivityAsUser(context, home, options, userId);
            return true;
        } catch (Throwable error) {
            log(Log.WARN, "show rear home failed", error);
            return false;
        }
    }

    /** 向背屏发送一次返回键（按下 + 抬起）。 */
    private static boolean injectBack() {
        try {
            Class<?> globalClass = Class.forName("android.hardware.input.InputManagerGlobal");
            Object global = globalClass.getMethod("getInstance").invoke(null);
            Method inject = globalClass.getMethod("injectInputEvent", android.view.InputEvent.class, int.class);
            Method setDisplayId = KeyEvent.class.getMethod("setDisplayId", int.class);
            long now = SystemClock.uptimeMillis();
            for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                KeyEvent event = new KeyEvent(now, now, action, KeyEvent.KEYCODE_BACK, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                        KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                        InputDevice.SOURCE_KEYBOARD);
                setDisplayId.invoke(event, Constants.SYSTEM_SECONDARY_DISPLAY_ID);
                if (!Boolean.TRUE.equals(inject.invoke(global, event, INJECT_MODE_ASYNC))) {
                    log(Log.WARN, "back key rejected", null);
                    return false;
                }
            }
            return true;
        } catch (Throwable error) {
            log(Log.WARN, "back key failed", error);
            return false;
        }
    }

    @NonNull
    private static RearCastHost.Reply refreshDensity(@NonNull RearCastHost.Sender sender) {
        RearDesktopSession.State state = SESSION.state();
        boolean active = state == RearDesktopSession.State.LAUNCHING || state == RearDesktopSession.State.SHOWING;
        if (!active || SESSION.userId() != sender.userId) {
            return RearCastHost.Reply.of(Constants.REAR_CAST_RESULT_NO_SESSION);
        }
        applyDensityRefresh();
        return RearCastHost.Reply.of(Constants.REAR_CAST_RESULT_DONE);
    }

    // ---- 任务核对 ----

    private static void checkTasks() {
        RearDesktopSession.State state = SESSION.state();
        if (state == RearDesktopSession.State.IDLE || state == RearDesktopSession.State.AWAITING_AUTH) return;
        long now = SystemClock.elapsedRealtime();
        if (state == RearDesktopSession.State.LAUNCHING) {
            int sessionId = SESSION.sessionId();
            if (romDeniedSessionId == sessionId) {
                apply(SESSION.onLaunchFailed(sessionId, EndReason.LAUNCH_DENIED));
                return;
            }
            String packageName = SESSION.packageName();
            try {
                if (packageName != null) {
                    apply(SESSION.onLaunchObserved(sessionId, RearCastPolicy.observeLaunch(
                            SystemApi.readRootTasks(Constants.SYSTEM_SECONDARY_DISPLAY_ID),
                            SystemApi.readRootTasks(Constants.SYSTEM_MAIN_DISPLAY_ID),
                            packageName, preexistingTasks, mainTopBeforeLaunch), now));
                }
            } catch (Throwable error) {
                log(Log.DEBUG, "launch snapshot failed: " + error, null);
            }
            if (SESSION.state() == RearDesktopSession.State.LAUNCHING) scheduleCheck(TRANSITION_POLL_MS);
            return;
        }
        RearCastPolicy.Presence presence;
        try {
            presence = RearCastPolicy.presenceOf(
                    SystemApi.readRootTasks(Constants.SYSTEM_SECONDARY_DISPLAY_ID),
                    SystemApi.readRootTasks(Constants.SYSTEM_MAIN_DISPLAY_ID),
                    SESSION.taskId());
        } catch (Throwable error) {
            // 读不到任务：按无法确认处理，限时后回收。
            log(Log.DEBUG, "session snapshot failed: " + error, null);
            presence = RearCastPolicy.Presence.HIDDEN;
        }
        boolean displayOn = rearDisplayOn();
        apply(SESSION.onSnapshot(presence, displayOn, now));
        RearDesktopSession.State after = SESSION.state();
        if (after == RearDesktopSession.State.ENDING) {
            scheduleCheck(TRANSITION_POLL_MS);
        } else if (after == RearDesktopSession.State.SHOWING) {
            if (!SESSION.controlsAllowed() && displayOn) {
                scheduleCheck(UNCONFIRMED_POLL_MS);
            } else if (!taskEventsAvailable) {
                scheduleCheck(FALLBACK_POLL_MS);
            }
        }
    }

    private static boolean rearDisplayOn() {
        try {
            DisplayManager displays = context == null ? null : context.getSystemService(DisplayManager.class);
            Display display = displays == null ? null : displays.getDisplay(Constants.SYSTEM_SECONDARY_DISPLAY_ID);
            return display != null && display.getState() == Display.STATE_ON;
        } catch (Throwable error) {
            return true;
        }
    }

    private static void postAt(@NonNull Runnable task, long elapsedDeadline) {
        Handler thread = handler;
        if (thread == null) return;
        thread.postDelayed(task, Math.max(0L, elapsedDeadline - SystemClock.elapsedRealtime()) + 10L);
    }

    /** 执行状态机的决定。 */
    private static void apply(@NonNull Decision decision) {
        switch (decision.kind) {
            case SHOW:
                diagnosticPackage = null;
                log(Log.INFO, "launch confirmed session=" + decision.sessionId + " task=" + SESSION.taskId()
                        + " owned=" + SESSION.taskOwned(), null);
                notifyStatus(decision.sessionId, RearDesktopSession.State.SHOWING, null,
                        Constants.REAR_CAST_RESULT_DONE);
                if (!taskEventsAvailable) scheduleCheck(FALLBACK_POLL_MS);
                break;
            case END:
                diagnosticPackage = null;
                sessionPackage = null;
                sessionUserId = RearCastPolicy.UNKNOWN;
                sessionActive = false;
                Handler thread = handler;
                if (thread != null) thread.removeCallbacks(CHECK);
                EndReason reason = decision.reason;
                if (reason == EndReason.LAUNCH_DENIED || reason == EndReason.LAUNCH_REDIRECTED) {
                    log(Log.INFO, "launch denied session=" + decision.sessionId + " reason=" + reason.wire, null);
                }
                restoreDisplay(reason == null ? "end" : reason.wire);
                log(Log.INFO, "session ended session=" + decision.sessionId
                        + " reason=" + (reason == null ? "" : reason.wire) + " shown=" + decision.wasShown, null);
                handleLeftover(decision);
                notifyStatus(decision.sessionId, RearDesktopSession.State.IDLE, reason, decision.resultCode);
                break;
            default:
                break;
        }
    }

    /**
     * 会话结束后任务还留在背屏后台（返回手势、回首页等）：会话创建的任务结束掉，启动前已有的任务移回主屏。
     * 任务已不在背屏、或又回到背屏最上层可见时不处理。
     */
    private static void handleLeftover(@NonNull Decision decision) {
        if (decision.leftover == RearDesktopSession.Leftover.NONE) return;
        int taskId = decision.leftoverTaskId;
        try {
            RearCastPolicy.Presence presence = RearCastPolicy.presenceOf(
                    SystemApi.readRootTasks(Constants.SYSTEM_SECONDARY_DISPLAY_ID),
                    SystemApi.readRootTasks(Constants.SYSTEM_MAIN_DISPLAY_ID),
                    taskId);
            if (presence != RearCastPolicy.Presence.HOME_ON_TOP && presence != RearCastPolicy.Presence.HIDDEN) {
                log(Log.DEBUG, "leftover task " + taskId + " skipped: " + presence, null);
                return;
            }
            if (decision.leftover == RearDesktopSession.Leftover.REMOVE) {
                boolean removed = SystemApi.removeTask(taskId);
                log(removed ? Log.INFO : Log.WARN, "leftover task " + taskId + " removed=" + removed
                        + " session=" + decision.sessionId, null);
            } else {
                SystemApi.moveRootTask(taskId, Constants.SYSTEM_MAIN_DISPLAY_ID);
                log(Log.INFO, "leftover task " + taskId + " moved to main session=" + decision.sessionId, null);
            }
        } catch (Throwable error) {
            log(Log.WARN, "leftover task " + taskId + " handling failed", error);
        }
    }

    /** 把会话状态告诉会话用户的背屏中心；背屏中心以 RECEIVER_NOT_EXPORTED 注册，并核对发送方是 system。 */
    private static void notifyStatus(int sessionId, @NonNull RearDesktopSession.State state,
                                     @Nullable EndReason reason, int resultCode) {
        int userId = SESSION.userId();
        if (userId < 0 || context == null) return;
        Intent intent = new Intent(Constants.ACTION_REAR_DESKTOP_STATUS)
                .setPackage(Constants.TARGET_PACKAGE)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(Constants.EXTRA_REAR_DESKTOP_SESSION_ID, sessionId)
                .putExtra(Constants.EXTRA_REAR_DESKTOP_STATE, state.wire)
                .putExtra(Constants.EXTRA_REAR_DESKTOP_RESULT, resultCode);
        if (reason != null) intent.putExtra(Constants.EXTRA_REAR_DESKTOP_REASON, reason.wire);
        try {
            Bundle options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
            SystemApi.sendBroadcastAsUser(context, intent, userId, options);
        } catch (Throwable error) {
            log(Log.WARN, "status notify failed", error);
        }
    }

    // ---- 显示参数 ----

    /**
     * 会话开始：先写恢复记录，再以同一事务调整密度与方向。有未恢复的旧记录时先恢复，
     * 仍然失败就不调整本次会话的显示参数。
     */
    private static void prepareDisplay(int userId) {
        Context system = context;
        if (system == null || module == null) return;
        ContentResolver resolver = system.getContentResolver();
        if (Settings.Global.getString(resolver, Constants.SETTING_REAR_DESKTOP_RESTORE) != null) {
            restoreDisplay("pending");
            if (Settings.Global.getString(resolver, Constants.SETTING_REAR_DESKTOP_RESTORE) != null) {
                log(Log.WARN, "display restore pending, session keeps current display settings", null);
                return;
            }
        }
        int density = PrefsBridge.readRearDesktopDensity(module);
        try {
            Object windowManager = windowManager();
            int display = Constants.SYSTEM_SECONDARY_DISPLAY_ID;
            int base = (Integer) invoke(windowManager, "getBaseDisplayDensity", new Class[]{int.class}, display);
            int initial = (Integer) invoke(windowManager, "getInitialDisplayDensity", new Class[]{int.class}, display);
            boolean ignore = (Boolean) invoke(windowManager, "getIgnoreOrientationRequest", new Class[]{int.class}, display);
            boolean applyDensity = RearDesktopPolicy.shouldApplyDensity(density);
            if (!applyDensity && ignore) {
                log(Log.INFO, "display unchanged, density=" + density, null);
                return;
            }
            RearDesktopPolicy.RestoreState state = new RearDesktopPolicy.RestoreState(userId,
                    RearDesktopPolicy.forcedDensityOf(base, initial), ignore, applyDensity);
            Settings.Global.putString(resolver, Constants.SETTING_REAR_DESKTOP_RESTORE, state.encode());
            if (applyDensity) setForcedDensity(windowManager, density, userId);
            invoke(windowManager, "setIgnoreOrientationRequest", new Class[]{int.class, boolean.class}, display, true);
            log(Log.INFO, "display prepared density=" + density + " saved=" + state.encode(), null);
        } catch (Throwable error) {
            log(Log.WARN, "display prepare failed", error);
            restoreDisplay("prepare failed");
        }
    }

    /** 会话中改了档位：现读远程偏好并调整；改回 0 时恢复会话开始前的密度。 */
    private static void applyDensityRefresh() {
        Context system = context;
        if (system == null || module == null) return;
        ContentResolver resolver = system.getContentResolver();
        int density = PrefsBridge.readRearDesktopDensity(module);
        RearDesktopPolicy.RestoreState state = RearDesktopPolicy.RestoreState.decode(
                Settings.Global.getString(resolver, Constants.SETTING_REAR_DESKTOP_RESTORE));
        try {
            if (state == null) {
                if (RearDesktopPolicy.shouldApplyDensity(density)) prepareDisplay(SESSION.userId());
            } else if (RearDesktopPolicy.shouldApplyDensity(density)) {
                setForcedDensity(windowManager(), density, state.userId);
                Settings.Global.putString(resolver, Constants.SETTING_REAR_DESKTOP_RESTORE,
                        state.withDensityApplied(true).encode());
            } else if (state.densityApplied) {
                setDensity(windowManager(), state.forcedDensity, state.userId);
                Settings.Global.putString(resolver, Constants.SETTING_REAR_DESKTOP_RESTORE,
                        state.withDensityApplied(false).encode());
            }
            log(Log.INFO, "density refreshed density=" + density, null);
        } catch (Throwable error) {
            log(Log.WARN, "density refresh failed", error);
        }
    }

    /** 按记录恢复；全部完成后清除记录，有失败时保留记录并报告。 */
    private static void restoreDisplay(@NonNull String reason) {
        Context system = context;
        if (system == null) return;
        ContentResolver resolver = system.getContentResolver();
        String saved;
        try {
            saved = Settings.Global.getString(resolver, Constants.SETTING_REAR_DESKTOP_RESTORE);
        } catch (Throwable error) {
            log(Log.WARN, "restore record unreadable (" + reason + ")", error);
            return;
        }
        if (saved == null) return;
        RearDesktopPolicy.RestoreState state = RearDesktopPolicy.RestoreState.decode(saved);
        if (state == null) {
            Settings.Global.putString(resolver, Constants.SETTING_REAR_DESKTOP_RESTORE, null);
            log(Log.WARN, "malformed restore record dropped: " + saved, null);
            return;
        }
        RearDesktopPolicy.RestorePlan plan = RearDesktopPolicy.planRestore(state, SystemApi.onlySystemUser(system));
        boolean densityRestored = false;
        boolean orientationRestored = false;
        try {
            Object windowManager = windowManager();
            if (plan.restoreDensity) {
                try {
                    setDensity(windowManager, plan.forcedDensity, plan.densityUserId);
                    densityRestored = true;
                } catch (Throwable error) {
                    log(Log.WARN, "density restore failed (" + reason + ")", error);
                }
            }
            try {
                invoke(windowManager, "setIgnoreOrientationRequest", new Class[]{int.class, boolean.class},
                        Constants.SYSTEM_SECONDARY_DISPLAY_ID, plan.ignoreOrientationRequest);
                orientationRestored = true;
            } catch (Throwable error) {
                log(Log.WARN, "orientation restore failed (" + reason + ")", error);
            }
        } catch (Throwable error) {
            log(Log.WARN, "window manager unavailable (" + reason + ")", error);
        }
        RearDesktopPolicy.RestoreState remaining =
                RearDesktopPolicy.remainingAfter(state, plan, densityRestored, orientationRestored);
        try {
            Settings.Global.putString(resolver, Constants.SETTING_REAR_DESKTOP_RESTORE,
                    remaining == null ? null : remaining.encode());
        } catch (Throwable error) {
            log(Log.WARN, "restore record update failed (" + reason + ")", error);
        }
        if (remaining == null) {
            log(Log.INFO, "display restored (" + reason + ") from " + saved
                    + (plan.restoreDensity || !state.densityApplied ? "" : ", density skipped for unknown user"), null);
        } else {
            log(Log.WARN, "display restore incomplete (" + reason + "), record kept " + remaining.encode(), null);
        }
    }

    private static void setForcedDensity(@NonNull Object windowManager, int density, int userId) throws Exception {
        invoke(windowManager, "setForcedDisplayDensityForUser", new Class[]{int.class, int.class, int.class},
                Constants.SYSTEM_SECONDARY_DISPLAY_ID, density, userId);
    }

    private static void setDensity(@NonNull Object windowManager, int forcedDensity, int userId) throws Exception {
        if (forcedDensity == RearDesktopPolicy.NO_FORCED_DENSITY) {
            invoke(windowManager, "clearForcedDisplayDensityForUser", new Class[]{int.class, int.class},
                    Constants.SYSTEM_SECONDARY_DISPLAY_ID, userId);
        } else {
            setForcedDensity(windowManager, forcedDensity, userId);
        }
    }

    @NonNull
    private static Object windowManager() throws Exception {
        Object service = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null);
        if (service == null) throw new IllegalStateException("IWindowManager unavailable");
        return service;
    }

    @Nullable
    private static Object invoke(@NonNull Object target, @NonNull String name, @NonNull Class<?>[] types,
                                 Object... args) throws Exception {
        return target.getClass().getMethod(name, types).invoke(target, args);
    }

    private static void log(int priority, @NonNull String message, @Nullable Throwable error) {
        ModuleMain owner = module;
        if (owner == null) return;
        if (error == null) {
            owner.log(priority, TAG, LOG_LABEL + ": " + message);
        } else {
            owner.log(priority, TAG, LOG_LABEL + ": " + message, error);
        }
    }
}
