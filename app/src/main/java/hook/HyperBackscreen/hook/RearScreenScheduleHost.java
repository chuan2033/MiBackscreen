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
    /** 5 秒：让形态切换（息屏 ↔ 黑屏）和时间窗边界更快跟上；单轮只读两个本地偏好，几乎不占 CPU。 */
    private static final long TICK_MS = 5_000L;
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
                        forceRearScreenToState(module, forceOff);
                    }
                } else {
                    boolean modeChanged = active && lastForceOff != null && lastForceOff != forceOff;
                    if (previous != active || modeChanged) {
                        logTransition(module, active);
                        if (active) {
                            // off = 背屏休眠（纯黑）；doze = 停在息屏（AOD）。
                            forceRearScreenToState(module, forceOff);
                        } else {
                            // 曾经处于窗口、现在不再处于：无论是窗口自然结束还是用户中途关闭，
                            // 都主动恢复背屏点亮。开机就不在窗口的情况已被首轮 previous==null
                            // 守卫拦下，不会走到这里，所以这里一定是「退出窗口」语义。
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
     * 把背屏切到指定形态。
     *
     * 走 `PowerManagerService#goToSleepOrDozeSubDisplay(group, isNoDoze, ...)`：
     * `isNoDoze=true` 内部是 `sleepPowerGroupLocked`，对应纯黑（关闭背屏）；
     * `isNoDoze=false` 是 `dozePowerGroupLocked`，对应息屏（AOD）。
     * 两个形态都用这一个入口直接置位，不依赖当前屏幕状态，所以中途切换形态
     * （息屏 ↔ 黑屏）也能立刻生效，不会因为背屏已经睡死而改不动。
     * 同样是 `Locked` 语义，持 `mLock` 调用，与系统 binder 线程一致。
     */
    private static void forceRearScreenToState(@NonNull XposedModule module, boolean noDoze) {
        Object[] internals = resolvePowerInternals(module);
        if (internals == null) return;
        Object powerManagerService = internals[0];
        Object lock = internals[1];
        Object powerGroup = internals[2];
        try {
            Method method = findMethod(powerManagerService.getClass(), "goToSleepOrDozeSubDisplay",
                    powerGroup.getClass(), boolean.class, long.class, int.class, int.class);
            if (method == null) {
                module.log(Log.WARN, TAG, "Rear screen schedule: state change skipped, method missing");
                return;
            }
            method.setAccessible(true);
            synchronized (lock) {
                method.invoke(powerManagerService,
                        powerGroup,
                        noDoze,
                        SystemClock.uptimeMillis(),
                        Constants.GO_TO_SLEEP_REASON_TIMEOUT,
                        Process.SYSTEM_UID);
            }
            module.log(Log.INFO, TAG, noDoze
                    ? "Rear screen schedule: rear screen sleep requested"
                    : "Rear screen schedule: rear screen doze requested");
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, "Rear screen schedule: state change request failed", error);
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
