package hook.HyperBackscreen.hook;

import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import hook.HyperBackscreen.bridge.PrefsBridge;
import hook.HyperBackscreen.common.Constants;
import io.github.libxposed.api.XposedModule;

/**
 * 「定时关闭背屏」在 system_server 里的执行端。
 *
 * 三件事：
 * 1. 时间窗内的超时覆盖与唤醒拦截由 {@link hook.HyperBackscreen.hook.ModuleMain} 的
 *    `adjustScreenOffTimeoutIfNeededLocked` / `isScreenSkippedWakeup` 钩子现算，不需要定时器；
 * 2. 但「到点那一刻」没人会调用那两个方法，所以这里起一个低频线程，在跨过时间窗边界时
 *    主动触发一次电源状态重算，让背屏按上面的覆盖值立刻熄灭；
 * 3. 线程只在时间窗进出时动作，其余时候只做一次取模比较，不写日志、不碰系统服务。
 *
 * 线程里没有跨进程调用以外的重活；prefs 读取本来就是本地缓存（LSPosed 远程偏好），
 * 这也是 Hook 端读偏好的统一做法。
 */
final class RearScreenScheduleHost {
    private static final String TAG = Constants.LOG_TAG;
    /** 15 秒够用：跨边界最晚晚 15 秒熄灭，换来的是几乎不占 CPU。 */
    private static final long TICK_MS = 15_000L;
    private static final Object LOCK = new Object();

    /** 背屏超时钩子拿到的 PowerManagerServiceImpl 实例，只用来触发状态重算。 */
    private static volatile WeakReference<Object> powerManagerServiceImpl;
    @Nullable
    private static volatile Thread worker;
    /** 上一次评估出的时间窗状态；null 表示还没评估过（启动后第一轮要立刻动作）。 */
    @Nullable
    private static volatile Boolean lastWindowActive;
    /** 上一次评估出的形态（true = 关闭背屏），用于窗口中途改形态也能立刻重做。 */
    @Nullable
    private static volatile Boolean lastForceOff;
    private static volatile boolean stopRequested;

    private RearScreenScheduleHost() {
    }

    /** 装一次就够：重复调用直接返回。 */
    static void install(@NonNull XposedModule module) {
        synchronized (LOCK) {
            if (worker != null) return;
            stopRequested = false;
            Thread thread = new Thread(() -> loop(module), "MiBackscreenRearSchedule");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            worker = thread;
            thread.start();
            module.log(Log.INFO, TAG, "Rear screen schedule host started");
        }
    }

    /** 超时钩子每次被调用时顺便把实例登记下来，用来在边界处触发重算。 */
    static void onPowerManagerService(@Nullable Object instance) {
        if (instance == null) return;
        WeakReference<Object> current = powerManagerServiceImpl;
        if (current != null && current.get() == instance) return;
        powerManagerServiceImpl = new WeakReference<>(instance);
    }

    /**
     * 时间窗内一律拒绝背屏（display group 1）的唤醒请求。
     * 与 `isScreenSkippedWakeup` 的语义一致：返回 true 表示这次唤醒不生效。
     */
    static boolean shouldBlockRearWake(@NonNull XposedModule module, @Nullable Object groupIdValue) {
        if (!(groupIdValue instanceof Integer)) return false;
        if ((Integer) groupIdValue != Constants.SYSTEM_SECONDARY_DISPLAY_GROUP_ID) return false;
        return PrefsBridge.isRearScreenScheduleActiveNow(module);
    }

    private static void loop(@NonNull XposedModule module) {
        while (!stopRequested) {
            try {
                boolean active = PrefsBridge.isRearScreenScheduleActiveNow(module);
                boolean forceOff = PrefsBridge.shouldForceOffRearScreen(module);
                Boolean previous = lastWindowActive;
                if (previous == null) {
                    // 启动后第一轮只登记状态：开机在窗口内才立刻动作；
                    // 不在窗口内就什么都不做 —— 否则开机瞬间会误打一条
                    // 「leaving off window」，还可能平白点亮背屏。
                    lastWindowActive = active;
                    lastForceOff = forceOff;
                    if (active) {
                        module.log(Log.INFO, TAG, "Rear screen schedule: already inside off window");
                        if (forceOff) {
                            forceRearScreenOff(module);
                        } else {
                            forceRearScreenStateUpdate(module);
                        }
                    }
                } else {
                    boolean modeChanged = active && lastForceOff != null && lastForceOff != forceOff;
                    if (previous != active || modeChanged) {
                        logTransition(module, active);
                        if (active) {
                            // off = 背屏休眠（纯黑）；doze = 停在息屏（超时覆盖 + 状态重算）。
                            if (forceOff) {
                                forceRearScreenOff(module);
                            } else {
                                forceRearScreenStateUpdate(module);
                            }
                        } else if (PrefsBridge.shouldAutoWakeRearScreen(module)) {
                            wakeRearScreen(module);
                        }
                    }
                    lastForceOff = forceOff;
                }
            } catch (Throwable error) {
                // 一轮失败不能让线程退出，否则整晚都不会再判断。
                module.log(Log.WARN, TAG, "Rear screen schedule tick failed", error);
            }
            try {
                Thread.sleep(TICK_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 只在状态翻转时打一条，避免每次评估都刷日志。 */
    private static void logTransition(@NonNull XposedModule module, boolean active) {
        lastWindowActive = active;
        module.log(Log.INFO, TAG, active
                ? "Rear screen schedule: entering off window"
                : "Rear screen schedule: leaving off window");
    }

    /**
     * 触发一次背屏电源状态重算。
     *
     * `PowerManagerService#userActivitySecondaryDisplay(displayId, eventTime, event, flags, uid)`
     * 是系统自己的公开入口，内部会拿 mLock 再调 updatePowerStateLocked，所以在我们线程里调用
     * 不需要手动持锁。重算时超时会被 `adjustScreenOffTimeoutIfNeededLocked` 覆盖成 1 秒，
     * 背屏随即按正常流程熄灭。
     */
    private static void forceRearScreenStateUpdate(@NonNull XposedModule module) {
        WeakReference<Object> reference = powerManagerServiceImpl;
        Object service = reference == null ? null : reference.get();
        if (service == null) {
            module.log(Log.WARN, TAG,
                    "Rear screen schedule: power service not captured yet, skip boundary action");
            return;
        }
        try {
            Object powerManagerService = readField(service, "mPowerManagerService");
            if (powerManagerService == null) {
                module.log(Log.WARN, TAG, "Rear screen schedule: mPowerManagerService is null");
                return;
            }
            Method method = powerManagerService.getClass().getDeclaredMethod(
                    "userActivitySecondaryDisplay",
                    int.class, long.class, int.class, int.class, int.class);
            method.setAccessible(true);
            // event 0 = USER_ACTIVITY_EVENT_OTHER，flags 0，uid 用 system。
            method.invoke(powerManagerService,
                    Constants.SYSTEM_SECONDARY_DISPLAY_GROUP_ID,
                    SystemClock.uptimeMillis(),
                    0,
                    0,
                    Process.SYSTEM_UID);
            module.log(Log.INFO, TAG, "Rear screen schedule: power state refresh requested");
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, "Rear screen schedule: refresh request failed", error);
        }
    }

    @Nullable
    private static Object readField(@NonNull Object target, @NonNull String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 直接把背屏关掉（纯黑，不是息屏）。
     *
     * 走 `PowerManagerService#goToSleepOrDozeSubDisplay(group, isNoDoze=true, ...)`，
     * 内部就是 `sleepPowerGroupLocked`，对应背屏的 ASLEEP 状态；`dozePowerGroupLocked`
     * 那条路才会落到息屏。超时覆盖只能推到 doze，所以「关闭背屏」必须在这里主动关。
     * 同样是 `Locked` 语义，持 `mLock` 调用，与系统 binder 线程一致。
     */
    private static void forceRearScreenOff(@NonNull XposedModule module) {
        Object[] internals = resolvePowerInternals(module);
        if (internals == null) return;
        Object powerManagerService = internals[0];
        Object lock = internals[1];
        Object powerGroup = internals[2];
        try {
            Method sleep = findMethod(powerManagerService.getClass(), "goToSleepOrDozeSubDisplay",
                    powerGroup.getClass(), boolean.class, long.class, int.class, int.class);
            if (sleep == null) {
                module.log(Log.WARN, TAG, "Rear screen schedule: sleep skipped, method missing");
                return;
            }
            sleep.setAccessible(true);
            synchronized (lock) {
                sleep.invoke(powerManagerService,
                        powerGroup,
                        true,
                        SystemClock.uptimeMillis(),
                        Constants.GO_TO_SLEEP_REASON_TIMEOUT,
                        Process.SYSTEM_UID);
            }
            module.log(Log.INFO, TAG, "Rear screen schedule: rear screen sleep requested");
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, "Rear screen schedule: sleep request failed", error);
        }
    }

    /**
     * 时间窗结束时主动点亮背屏。
     *
     * 走 `PowerManagerService#wakeUpSecondaryDisplay(PowerGroup, long, int, String, int)`。
     * 这是 `Locked` 语义的内部方法（必须持 mLock），系统自己的 binder 线程也是先拿锁再调它，
     * 所以这里同样在 mLock 内调用。拿不到任何一个环节都只打 WARN —— 少了主动点亮，
     * 用户自己双击唤醒仍然可用，不影响时间窗本身。
     */
    private static void wakeRearScreen(@NonNull XposedModule module) {
        Object[] internals = resolvePowerInternals(module);
        if (internals == null) return;
        Object powerManagerService = internals[0];
        Object lock = internals[1];
        Object powerGroup = internals[2];
        try {
            Method wake = findMethod(powerManagerService.getClass(), "wakeUpSecondaryDisplay",
                    powerGroup.getClass(), long.class, int.class, String.class, int.class);
            if (wake == null) {
                module.log(Log.WARN, TAG, "Rear screen schedule: wake skipped, method missing");
                return;
            }
            wake.setAccessible(true);
            synchronized (lock) {
                wake.invoke(powerManagerService,
                        powerGroup,
                        SystemClock.uptimeMillis(),
                        Constants.WAKE_REASON_APPLICATION,
                        "MiBackscreenRearSchedule",
                        Process.SYSTEM_UID);
            }
            module.log(Log.INFO, TAG, "Rear screen schedule: rear screen wake requested");
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, "Rear screen schedule: wake request failed", error);
        }
    }

    /**
     * 取出 {PowerManagerService, mLock, 背屏 PowerGroup} 三件套；任一环节缺失就打一条 WARN 并返回 null。
     */
    @Nullable
    private static Object[] resolvePowerInternals(@NonNull XposedModule module) {
        WeakReference<Object> reference = powerManagerServiceImpl;
        Object service = reference == null ? null : reference.get();
        if (service == null) {
            module.log(Log.WARN, TAG, "Rear screen schedule: power service not captured yet");
            return null;
        }
        try {
            Object powerManagerService = readField(service, "mPowerManagerService");
            if (powerManagerService == null) {
                module.log(Log.WARN, TAG, "Rear screen schedule: mPowerManagerService is null");
                return null;
            }
            Object lock = readField(powerManagerService, "mLock");
            Object groups = readField(powerManagerService, "mPowerGroups");
            if (lock == null || groups == null) {
                module.log(Log.WARN, TAG, "Rear screen schedule: power internals unavailable");
                return null;
            }
            Method get = groups.getClass().getMethod("get", int.class);
            Object powerGroup = get.invoke(groups, Constants.SYSTEM_SECONDARY_DISPLAY_GROUP_ID);
            if (powerGroup == null) {
                module.log(Log.WARN, TAG, "Rear screen schedule: rear power group missing");
                return null;
            }
            return new Object[]{powerManagerService, lock, powerGroup};
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, "Rear screen schedule: power internals lookup failed", error);
            return null;
        }
    }

    @Nullable
    private static Method findMethod(
            @NonNull Class<?> type,
            @NonNull String name,
            @NonNull Class<?>... parameterTypes
    ) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
