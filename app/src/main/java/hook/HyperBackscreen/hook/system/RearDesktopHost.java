package hook.HyperBackscreen.hook.system;

import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;

import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.common.RearDesktopPolicy;
import hook.HyperBackscreen.hook.ModuleMain;

/**
 * 背屏桌面在 system_server 里的执行端，由 {@link RearCastHost} 在它的 HandlerThread 上调用。
 *
 * <p>会话：模块 App 的前台服务持有一个 Binder 令牌，开始会话时随请求交过来。会话期间
 * <ul>
 *   <li>{@link #isSessionActive()} 为 true，背屏启动拦截 hook 据此放行应用显示在背屏；</li>
 *   <li>背屏按请求的密度显示，并忽略应用的方向请求（背屏固定横向，竖屏应用直接铺满横向区域）。</li>
 * </ul>
 * 会话开始前的背屏设置写进 {@link Constants#SETTING_REAR_DESKTOP_RESTORE}；会话结束、令牌死亡（模块进程被杀）
 * 或开机发现残留记录时按它恢复。
 *
 * <p>所有系统接口都走反射并整段 try/catch：缺失或报错只让本功能不可用，不影响 system_server。
 */
final class RearDesktopHost {
    private static final String TAG = Constants.LOG_TAG;
    private static final String LOG_LABEL = "Rear desktop";
    /** UserHandle.USER_CURRENT（隐藏常量）。 */
    private static final int USER_CURRENT = -2;
    /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC。 */
    private static final int INJECT_MODE_ASYNC = 0;

    /** 背屏启动拦截 hook 在 WindowManager 全局锁内读它，只能是一个 volatile 字段。 */
    private static volatile boolean sessionActive;
    /** 以下只在投放 HandlerThread 上访问。 */
    @Nullable
    private static IBinder sessionToken;
    @Nullable
    private static IBinder.DeathRecipient deathRecipient;
    private static int sessionDensity = RearDesktopPolicy.DENSITY_UNCHANGED;

    private RearDesktopHost() {
    }

    static boolean isSessionActive() {
        return sessionActive;
    }

    /** 开机注册接收器时调用：上次会话没正常结束（重启、崩溃）就恢复背屏设置。 */
    static void restoreAfterBoot(@NonNull ModuleMain module, @NonNull Context context) {
        try {
            String saved = Settings.Global.getString(context.getContentResolver(), Constants.SETTING_REAR_DESKTOP_RESTORE);
            if (saved == null) return;
            restore(module, context, "boot");
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, LOG_LABEL + ": restore after boot failed", error);
        }
    }

    /** 在背屏上启动背屏桌面。锁屏时不打开：系统也只允许内置名单里的应用在锁屏时显示到背屏。 */
    static int open(@NonNull ModuleMain module, @NonNull Context context, int userId) {
        if (isDeviceLocked(module, context)) return Constants.REAR_CAST_RESULT_LOCKED;
        Intent intent = new Intent()
                .setClassName(Constants.MODULE_PACKAGE, Constants.REAR_DESKTOP_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Bundle options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(Constants.SYSTEM_SECONDARY_DISPLAY_ID)
                .toBundle();
        try {
            startActivityAsUser(context, intent, options, userId);
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, LOG_LABEL + ": open failed", error);
            return Constants.REAR_CAST_RESULT_MOVE_FAILED;
        }
        RearScreenScheduleHost.requestRearWake(module, "MiBackscreenRearDesktop", LOG_LABEL);
        return Constants.REAR_CAST_RESULT_DONE;
    }

    private static void startActivityAsUser(@NonNull Context context, @NonNull Intent intent,
                                            @NonNull Bundle options, int userId) throws Exception {
        Method method = Context.class.getMethod("startActivityAsUser", Intent.class, Bundle.class, UserHandle.class);
        UserHandle user = (UserHandle) UserHandle.class.getMethod("of", int.class).invoke(null, userId);
        method.invoke(context, intent, options, user);
    }

    /**
     * 开始（或更新）会话。同一个令牌重复开始只更新密度；换了令牌（服务重建）先结束旧会话。
     */
    static int start(@NonNull ModuleMain module, @NonNull Context context, @NonNull Handler handler,
                     @Nullable IBinder token, int density) {
        if (token == null || !RearDesktopPolicy.isAcceptedDensity(density)) {
            return Constants.REAR_CAST_RESULT_BAD_REQUEST;
        }
        if (sessionActive && sessionToken != null && sessionToken != token) {
            stop(module, context, "replaced");
        }
        try {
            Object windowManager = windowManager();
            if (Settings.Global.getString(context.getContentResolver(), Constants.SETTING_REAR_DESKTOP_RESTORE) == null) {
                int display = Constants.SYSTEM_SECONDARY_DISPLAY_ID;
                int base = (Integer) invoke(windowManager, "getBaseDisplayDensity", new Class[]{int.class}, display);
                int initial = (Integer) invoke(windowManager, "getInitialDisplayDensity", new Class[]{int.class}, display);
                boolean ignore = (Boolean) invoke(windowManager, "getIgnoreOrientationRequest", new Class[]{int.class}, display);
                RearDesktopPolicy.RestoreState state =
                        new RearDesktopPolicy.RestoreState(RearDesktopPolicy.forcedDensityOf(base, initial), ignore);
                Settings.Global.putString(context.getContentResolver(), Constants.SETTING_REAR_DESKTOP_RESTORE, state.encode());
                module.log(Log.INFO, TAG, LOG_LABEL + ": saved display state " + state.encode());
            }
            applyDensity(context, windowManager, density);
            invoke(windowManager, "setIgnoreOrientationRequest", new Class[]{int.class, boolean.class},
                    Constants.SYSTEM_SECONDARY_DISPLAY_ID, true);
            if (sessionToken != token) {
                IBinder.DeathRecipient recipient = () -> handler.post(() -> {
                    if (sessionToken == token) stop(module, context, "token died");
                });
                token.linkToDeath(recipient, 0);
                sessionToken = token;
                deathRecipient = recipient;
            }
            sessionDensity = density;
            sessionActive = true;
            module.log(Log.INFO, TAG, LOG_LABEL + ": session active, density=" + density);
            return Constants.REAR_CAST_RESULT_DONE;
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, LOG_LABEL + ": start failed", error);
            stop(module, context, "start failed");
            return Constants.REAR_CAST_RESULT_API_UNAVAILABLE;
        }
    }

    private static void applyDensity(@NonNull Context context, @NonNull Object windowManager, int density)
            throws Exception {
        if (density != RearDesktopPolicy.DENSITY_UNCHANGED) {
            invoke(windowManager, "setForcedDisplayDensityForUser", new Class[]{int.class, int.class, int.class},
                    Constants.SYSTEM_SECONDARY_DISPLAY_ID, density, USER_CURRENT);
            return;
        }
        // 会话中从某个档位改回「不调整」：恢复会话开始前的密度。
        if (sessionDensity == RearDesktopPolicy.DENSITY_UNCHANGED) return;
        RearDesktopPolicy.RestoreState state = RearDesktopPolicy.RestoreState.decode(
                Settings.Global.getString(context.getContentResolver(), Constants.SETTING_REAR_DESKTOP_RESTORE));
        if (state != null) setDensity(windowManager, state.forcedDensity);
    }

    /** 结束会话并恢复背屏设置；没有会话时也会按残留记录恢复。 */
    static int stop(@NonNull ModuleMain module, @NonNull Context context, @NonNull String reason) {
        IBinder token = sessionToken;
        IBinder.DeathRecipient recipient = deathRecipient;
        sessionActive = false;
        sessionToken = null;
        deathRecipient = null;
        sessionDensity = RearDesktopPolicy.DENSITY_UNCHANGED;
        if (token != null && recipient != null) {
            try {
                token.unlinkToDeath(recipient, 0);
            } catch (Throwable ignored) {
                // 令牌已死亡时 unlink 会抛 NoSuchElementException，忽略即可。
            }
        }
        restore(module, context, reason);
        return Constants.REAR_CAST_RESULT_DONE;
    }

    private static void restore(@NonNull ModuleMain module, @NonNull Context context, @NonNull String reason) {
        try {
            String saved = Settings.Global.getString(context.getContentResolver(), Constants.SETTING_REAR_DESKTOP_RESTORE);
            RearDesktopPolicy.RestoreState state = RearDesktopPolicy.RestoreState.decode(saved);
            if (state != null) {
                Object windowManager = windowManager();
                setDensity(windowManager, state.forcedDensity);
                invoke(windowManager, "setIgnoreOrientationRequest", new Class[]{int.class, boolean.class},
                        Constants.SYSTEM_SECONDARY_DISPLAY_ID, state.ignoreOrientationRequest);
            }
            if (saved != null) {
                Settings.Global.putString(context.getContentResolver(), Constants.SETTING_REAR_DESKTOP_RESTORE, null);
            }
            module.log(Log.INFO, TAG, LOG_LABEL + ": session ended (" + reason + ")"
                    + (state == null ? "" : ", restored " + state.encode()));
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, LOG_LABEL + ": restore failed (" + reason + ")", error);
        }
    }

    private static void setDensity(@NonNull Object windowManager, int forcedDensity) throws Exception {
        if (forcedDensity == RearDesktopPolicy.NO_FORCED_DENSITY) {
            invoke(windowManager, "clearForcedDisplayDensityForUser", new Class[]{int.class, int.class},
                    Constants.SYSTEM_SECONDARY_DISPLAY_ID, USER_CURRENT);
        } else {
            invoke(windowManager, "setForcedDisplayDensityForUser", new Class[]{int.class, int.class, int.class},
                    Constants.SYSTEM_SECONDARY_DISPLAY_ID, forcedDensity, USER_CURRENT);
        }
    }

    /** 向背屏发送一次返回键（按下 + 抬起）。 */
    static int back(@NonNull ModuleMain module) {
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
                    module.log(Log.WARN, TAG, LOG_LABEL + ": back key rejected");
                    return Constants.REAR_CAST_RESULT_MOVE_FAILED;
                }
            }
            return Constants.REAR_CAST_RESULT_DONE;
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, LOG_LABEL + ": back key failed", error);
            return Constants.REAR_CAST_RESULT_API_UNAVAILABLE;
        }
    }

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
}
