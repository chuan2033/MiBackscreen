package hook.HyperBackscreen.hook.system;

import static hook.HyperBackscreen.hook.util.HookReflection.findClass;
import static hook.HyperBackscreen.hook.util.HookReflection.findDeclaredMethod;
import static hook.HyperBackscreen.hook.util.HookReflection.getFieldValue;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;

import hook.HyperBackscreen.bridge.PrefsBridge;
import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.common.RearScreenWakeMatcher;
import hook.HyperBackscreen.hook.ModuleMain;

/**
 * system_server 进程里的 Hook：背屏封面显示开关、背屏双击 / 定时关闭期间的唤醒拦截、
 * 背屏息屏超时覆盖、主屏高负载（游戏）时保持背屏常亮；应用投放到背屏的执行端在
 * {@link RearCastHost}（systemReady 之后注册接收器）。
 *
 * <p>{@link ModuleMain} 在 onModuleLoaded / onSystemServerStarting / onPackageReady("android")
 * 三处都会调 {@link #installSystemHooksOnce}，这里保证只成功安装一次。
 */
public final class SystemServerHooks {
    private final ModuleMain module;

    private static final long REAR_WAKE_DECISION_LOG_INTERVAL_MS = 5_000L;
    private volatile boolean systemHooksInstalled = false;
    private volatile long lastRearWakeDecisionLoggedAtElapsed;
    /** 背屏息屏超时覆盖最近一次生效值，只在变化时记日志（该方法每次电源状态刷新都会调）。 */
    private volatile long lastRearScreenOffTimeoutAppliedMs = Long.MIN_VALUE;

    /**
     * PowerGroup#getGroupId() 是包可见方法，只能按声明方法查找后 setAccessible。
     * 取不到就返回 null，调用方据此放行原逻辑。
     */
    /** 按 PowerGroup 类缓存 getGroupId；锁内路径不能每次都反射查找 + setAccessible。 */
    private static volatile Class<?> sPowerGroupClass;
    private static volatile Method sPowerGroupIdMethod;
    /** 双击熄屏过滤：WindowState#getOwningPackage 与 mGestureEnable 字段，按类缓存。 */
    private static volatile Class<?> sOwningPackageClass;
    private static volatile Method sOwningPackageMethod;
    private static volatile Class<?> sGestureEnableClass;
    private static volatile Field sGestureEnableField;
    /** 最近一次因名单关掉双击熄屏的包名，只在变化时记日志。 */
    private volatile String lastDoubleTapSleepBlockedPackage;

    public SystemServerHooks(@NonNull ModuleMain module) {
        this.module = module;
    }

    @Nullable
    private static Integer readPowerGroupId(@Nullable Object powerGroup) {
        if (powerGroup == null) return null;
        Class<?> type = powerGroup.getClass();
        Method method = sPowerGroupIdMethod;
        if (method == null || sPowerGroupClass != type) {
            method = findDeclaredMethod(type, Constants.SYSTEM_POWER_GROUP_GET_GROUP_ID_METHOD);
            if (method == null) return null;
            sPowerGroupIdMethod = method;
            sPowerGroupClass = type;
        }
        try {
            Object value = method.invoke(powerGroup);
            return value instanceof Integer ? (Integer) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void installSystemHooksOnce(@NonNull ClassLoader classLoader) {
        if (systemHooksInstalled) return;
        synchronized (module) {
            if (systemHooksInstalled) return;
            long startedAt = SystemClock.elapsedRealtime();
            // 应用投放到背屏：与下面的 Hook 互不依赖，自带 try/catch 与安装去重，
            // 失败只让投放不可用，不影响其它 Hook 的安装结果。
            RearCastHost.install(module, classLoader);
            try {
                if (installSystemHooks(classLoader)) {
                    systemHooksInstalled = true;
                    module.log(Log.INFO, Constants.LOG_TAG,
                            "System hooks installed in "
                                    + (SystemClock.elapsedRealtime() - startedAt) + " ms");
                }
            } catch (Throwable throwable) {
                module.log(Log.ERROR, Constants.LOG_TAG, "Failed to install system hooks", throwable);
            }
        }
    }

    private boolean installSystemHooks(@NonNull ClassLoader classLoader) {
        Class<?> coverManagerClass = findClass(Constants.SYSTEM_DUAL_SCREEN_COVER_MANAGER_CLASS, classLoader);
        if (coverManagerClass == null) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "System hook target missing: " + Constants.SYSTEM_DUAL_SCREEN_COVER_MANAGER_CLASS);
            return false;
        }
        Class<?> powerManagerServiceImplClass = findClass(
                Constants.SYSTEM_POWER_MANAGER_SERVICE_IMPL_CLASS,
                classLoader);

        boolean coverHookInstalled = module.hookMethodIfPresent(
                coverManagerClass,
                Constants.SYSTEM_SHOW_COVER_VIEW_METHOD,
                new Class[]{int.class},
                Constants.SYSTEM_DUAL_SCREEN_COVER_MANAGER_CLASS + "#"
                        + Constants.SYSTEM_SHOW_COVER_VIEW_METHOD,
                chain -> {
                    Object displayIdValue = chain.getArgs().get(0);
                    if (displayIdValue instanceof Integer
                            && ((Integer) displayIdValue) == Constants.SYSTEM_SECONDARY_DISPLAY_ID
                            && PrefsBridge.shouldDisableRearScreenCover(module)) {
                        module.log(Log.DEBUG, Constants.LOG_TAG, "Rear screen cover skipped");
                        return null;
                    }
                    return chain.proceed();
                }
        );

        boolean powerWakeHookInstalled = module.hookMethodIfPresent(
                powerManagerServiceImplClass == null
                        ? null
                        : findDeclaredMethod(
                                powerManagerServiceImplClass,
                                Constants.SYSTEM_IS_SCREEN_SKIPPED_WAKEUP_METHOD,
                                int.class,
                                String.class,
                                int.class),
                Constants.SYSTEM_POWER_MANAGER_SERVICE_IMPL_CLASS + "#"
                        + Constants.SYSTEM_IS_SCREEN_SKIPPED_WAKEUP_METHOD,
                chain -> {
                    Object groupIdValue = chain.getArgs().get(0);
                    Object detailsValue = chain.getArgs().get(1);
                    if (RearScreenScheduleHost.shouldBlockRearWake(module, groupIdValue)) {
                        module.log(Log.DEBUG, Constants.LOG_TAG,
                                "Rear screen wake skipped by schedule: " + detailsValue);
                        return true;
                    }
                    if (groupIdValue instanceof Integer
                            && RearScreenWakeMatcher.isRearDoubleTapWake(
                                    (Integer) groupIdValue,
                                    detailsValue)
                            && PrefsBridge.shouldDisableDoubleTapWake(module)) {
                        String[] packageNames = resolveForegroundPackages(chain.getThisObject());
                        boolean skipped = PrefsBridge.shouldSkipDoubleTapWakeForPackages(module, packageNames);
                        logRearWakeDecision((Integer) groupIdValue, detailsValue, packageNames, skipped);
                        if (skipped) {
                            module.log(Log.DEBUG, Constants.LOG_TAG,
                                    "Rear screen double tap wake skipped for "
                                            + joinPackageNames(packageNames));
                            return true;
                        }
                    }
                    return chain.proceed();
                }
        );

        // On this ROM the power service delegates to the cover manager. Do not inspect
        // the same wake twice; install the cover hook only when the primary target is absent.
        boolean coverWakeHookInstalled = !powerWakeHookInstalled && module.hookMethodIfPresent(
                coverManagerClass,
                Constants.SYSTEM_IS_SCREEN_SKIPPED_WAKEUP_METHOD,
                new Class[]{int.class, String.class, int.class},
                Constants.SYSTEM_DUAL_SCREEN_COVER_MANAGER_CLASS + "#"
                        + Constants.SYSTEM_IS_SCREEN_SKIPPED_WAKEUP_METHOD,
                chain -> {
                    Object groupIdValue = chain.getArgs().get(0);
                    Object detailsValue = chain.getArgs().get(1);
                    if (RearScreenScheduleHost.shouldBlockRearWake(module, groupIdValue)) {
                        module.log(Log.DEBUG, Constants.LOG_TAG,
                                "Rear screen wake skipped by schedule: " + detailsValue);
                        return true;
                    }
                    if (groupIdValue instanceof Integer
                            && RearScreenWakeMatcher.isRearDoubleTapWake(
                                    (Integer) groupIdValue,
                                    detailsValue)
                            && PrefsBridge.shouldDisableDoubleTapWake(module)) {
                        String[] packageNames = resolveForegroundPackages(getFieldValue(
                                chain.getThisObject(),
                                Constants.SYSTEM_POWER_MANAGER_SERVICE_IMPL_FIELD));
                        if (packageNames.length == 0) {
                            String packageName = resolveForegroundPackage(chain.getThisObject());
                            if (packageName != null) {
                                packageNames = new String[]{packageName};
                            }
                        }
                        boolean skipped = PrefsBridge.shouldSkipDoubleTapWakeForPackages(module, packageNames);
                        logRearWakeDecision((Integer) groupIdValue, detailsValue, packageNames, skipped);
                        if (skipped) {
                            module.log(Log.DEBUG, Constants.LOG_TAG,
                                    "Rear screen double tap wake skipped for "
                                            + joinPackageNames(packageNames));
                            return true;
                        }
                    }
                    return chain.proceed();
                }
        );
        // 背屏息屏超时：系统给背屏（display group 1）的倒计时只来自 subscreen_display_time
        // （默认 10 秒，游戏模式还会改写），这里按模块偏好覆盖返回值。UI 侧同时把档位写进
        // 该设置项作兜底，两层指向同一档位；偏好为「跟随系统」时不覆盖、原样放行。
        Class<?> powerGroupClass = findClass(Constants.SYSTEM_POWER_GROUP_CLASS, classLoader);
        if (powerGroupClass == null) {
            module.log(Log.WARN, Constants.LOG_TAG,
                    "System hook target missing: " + Constants.SYSTEM_POWER_GROUP_CLASS);
        } else {
            module.hookMethodIfPresent(
                    powerManagerServiceImplClass,
                    Constants.SYSTEM_ADJUST_SCREEN_OFF_TIMEOUT_METHOD,
                    new Class[]{powerGroupClass, long.class},
                    Constants.SYSTEM_POWER_MANAGER_SERVICE_IMPL_CLASS + "#"
                            + Constants.SYSTEM_ADJUST_SCREEN_OFF_TIMEOUT_METHOD,
                    chain -> {
                        RearScreenScheduleHost.onPowerManagerService(chain.getThisObject());
                        Integer groupIdValue = readPowerGroupId(chain.getArgs().get(0));
                        boolean rearGroup = groupIdValue != null
                                && groupIdValue == Constants.SYSTEM_SECONDARY_DISPLAY_GROUP_ID;
                        // 这条路径在 PMS mLock 内、随用户活动高频触发：主屏组直接放行，不读任何偏好。
                        if (!rearGroup) {
                            return chain.proceed();
                        }
                        // 定时关闭背屏优先于其它档位：窗口内把超时压到 1 秒，
                        // 背屏按系统自己的熄灭流程关掉，之后靠 isScreenSkippedWakeup 挡住唤醒。
                        if (rearGroup && PrefsBridge.isRearScreenScheduleActiveNow(module)) {
                            return (long) Constants.REAR_SCREEN_SCHEDULE_OFF_TIMEOUT_MS;
                        }
                        long overrideMillis = PrefsBridge.readRearScreenOffTimeoutMillis(module);
                        // 游戏（主屏高负载）期间如果开了「保持背屏亮」，强制按「一直亮屏」处理：
                        // 游戏模式会把 subscreen_display_time 改写成 30 秒，只抬设置值挡不住。
                        if (overrideMillis <= 0L
                                && RearScreenGameModeState.isActive()
                                && PrefsBridge.shouldKeepRearScreenOnInGame(module)) {
                            overrideMillis = Constants.REAR_SCREEN_OFF_TIMEOUT_ALWAYS_MS;
                        }
                        if (overrideMillis <= 0L) {
                            return chain.proceed();
                        }
                        if (overrideMillis != lastRearScreenOffTimeoutAppliedMs) {
                            lastRearScreenOffTimeoutAppliedMs = overrideMillis;
                            module.log(Log.INFO, Constants.LOG_TAG,
                                    "Rear screen off timeout override: " + overrideMillis + " ms");
                        }
                        return overrideMillis;
                    }
            );
            // 预热远程偏好：首次 getRemotePreferences 可能走 Binder，别让它发生在 PMS 锁内。
            try {
                PrefsBridge.readRearScreenOffTimeoutMillis(module);
                PrefsBridge.shouldKeepRearScreenOnInGame(module);
            } catch (Throwable ignored) {
            }
        }
        // 时间窗边界没人会触发电源状态重算，交给这个低频线程；只在跨边界时动作。
        RearScreenScheduleHost.install(module);
        // 游戏时保持背屏亮：DualScreenCoverManager.updateHighLoadSceneMode(int, boolean) 在主屏
        // 进入高负载（横屏大型游戏）时直接调用 goToSleepSecondaryDisplay(..., "mainScreen:highLoadGameMode", 0)
        // 关掉背屏，日志原文 "Secondary screen turned OFF, Main screen keep ON"。这条路径不走超时，
        // 只能在这里拦；开关开启时整段跳过，连 mScreenStates 也不更新 —— 否则状态会记成
        // 「背屏已关」而实际还亮着，后续双击唤醒/电源键判断都会错。
        module.hookMethodIfPresent(
                coverManagerClass,
                Constants.SYSTEM_UPDATE_HIGH_LOAD_SCENE_METHOD,
                new Class[]{int.class, boolean.class},
                Constants.SYSTEM_DUAL_SCREEN_COVER_MANAGER_CLASS + "#"
                        + Constants.SYSTEM_UPDATE_HIGH_LOAD_SCENE_METHOD,
                chain -> {
                    if (chain.getArgs().size() < 2 || !(chain.getArgs().get(1) instanceof Boolean)) {
                        return chain.proceed();
                    }
                    boolean highLoad = (Boolean) chain.getArgs().get(1);
                    boolean keepRearScreenOn =
                            highLoad && PrefsBridge.shouldKeepRearScreenOnInGame(module);
                    if (RearScreenGameModeState.setActive(highLoad)) {
                        module.log(Log.INFO, Constants.LOG_TAG, "High load game mode: " + highLoad
                                + ", keep rear screen on: " + keepRearScreenOn);
                    }
                    if (keepRearScreenOn) {
                        module.log(Log.INFO, Constants.LOG_TAG,
                                "Rear screen kept on in high load game mode");
                        return null;
                    }
                    return chain.proceed();
                }
        );
        // 按应用禁用双击熄屏：与上面的 Hook 互不依赖，类缺失只记 WARN，不影响返回值。
        installDoubleTapSleepFilterHook(classLoader);
        return coverHookInstalled && (powerWakeHookInstalled || coverWakeHookInstalled);
    }

    /**
     * 按应用禁用背屏「双击熄屏」。
     *
     * <p>MiuiSubscreenDoubleTapGesture#onFocusedWindowChanged 按新焦点窗口的包名刷新
     * mGestureEnable（内置名单里的应用关掉，其余恢复 true），onPointerEvent 只在它为 true 时
     * goToSleep。这里在原方法之后补一刀：新焦点包名在用户名单里就把 mGestureEnable 置 false。
     * 名单变化在下一次焦点切换时生效。
     *
     * <p>system_server 敏感：回调里只做字段读写与一次进程内偏好读取，所有异常吞掉只记日志。
     */
    private void installDoubleTapSleepFilterHook(@NonNull ClassLoader classLoader) {
        try {
            Class<?> gestureClass = findClass(Constants.SYSTEM_SUBSCREEN_DOUBLE_TAP_GESTURE_CLASS, classLoader);
            if (gestureClass == null) {
                module.log(Log.WARN, Constants.LOG_TAG,
                        "System hook target missing: " + Constants.SYSTEM_SUBSCREEN_DOUBLE_TAP_GESTURE_CLASS);
                return;
            }
            Method focusChanged = null;
            for (Method method : gestureClass.getDeclaredMethods()) {
                if (Constants.SYSTEM_ON_FOCUSED_WINDOW_CHANGED_METHOD.equals(method.getName())
                        && method.getParameterTypes().length == 2) {
                    focusChanged = method;
                    break;
                }
            }
            module.hookMethodIfPresent(
                    focusChanged,
                    Constants.SYSTEM_SUBSCREEN_DOUBLE_TAP_GESTURE_CLASS + "#"
                            + Constants.SYSTEM_ON_FOCUSED_WINDOW_CHANGED_METHOD,
                    chain -> {
                        Object result = chain.proceed();
                        try {
                            Object newFocus = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                            String packageName = owningPackage(newFocus);
                            if (packageName != null
                                    && PrefsBridge.shouldSkipDoubleTapSleepForPackage(module, packageName)
                                    && disableGesture(chain.getThisObject())) {
                                // 同一个应用里焦点来回切（弹窗、输入法）会反复触发，只在包名变化时记一次。
                                if (!packageName.equals(lastDoubleTapSleepBlockedPackage)) {
                                    lastDoubleTapSleepBlockedPackage = packageName;
                                    module.log(Log.INFO, Constants.LOG_TAG,
                                            "Rear screen double tap sleep disabled for " + packageName);
                                }
                            } else if (packageName != null) {
                                lastDoubleTapSleepBlockedPackage = null;
                            }
                        } catch (Throwable error) {
                            module.log(Log.WARN, Constants.LOG_TAG,
                                    "Double tap sleep filter failed: " + error);
                        }
                        return result;
                    }
            );
            // 预热远程偏好：首次读取可能走 Binder，不要放到焦点回调里。
            PrefsBridge.shouldSkipDoubleTapSleepForPackage(module, Constants.MODULE_PACKAGE);
        } catch (Throwable throwable) {
            module.log(Log.WARN, Constants.LOG_TAG, "Double tap sleep filter hook failed: " + throwable);
        }
    }

    /** WindowState#getOwningPackage()；按类缓存 Method，回调里不重复反射查找。 */
    @Nullable
    private static String owningPackage(@Nullable Object windowState) {
        if (windowState == null) return null;
        Class<?> type = windowState.getClass();
        Method method = sOwningPackageMethod;
        if (method == null || sOwningPackageClass != type) {
            method = findMethodInHierarchy(type, Constants.SYSTEM_WINDOW_STATE_OWNING_PACKAGE_METHOD);
            if (method == null) return null;
            sOwningPackageMethod = method;
            sOwningPackageClass = type;
        }
        try {
            Object value = method.invoke(windowState);
            return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Method findMethodInHierarchy(@NonNull Class<?> type, @NonNull String name) {
        Class<?> current = type;
        while (current != null) {
            Method method = findDeclaredMethod(current, name);
            if (method != null) return method;
            current = current.getSuperclass();
        }
        return null;
    }

    /** 把手势对象的 mGestureEnable 置 false；字段按类缓存。 */
    private static boolean disableGesture(@Nullable Object gesture) {
        if (gesture == null) return false;
        Class<?> type = gesture.getClass();
        Field field = sGestureEnableField;
        if (field == null || sGestureEnableClass != type) {
            field = null;
            Class<?> current = type;
            while (current != null && field == null) {
                try {
                    field = current.getDeclaredField(Constants.SYSTEM_GESTURE_ENABLE_FIELD);
                } catch (NoSuchFieldException ignored) {
                    current = current.getSuperclass();
                }
            }
            if (field == null || field.getType() != boolean.class) return false;
            field.setAccessible(true);
            sGestureEnableField = field;
            sGestureEnableClass = type;
        }
        try {
            field.setBoolean(gesture, false);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Nullable
    private static String resolveForegroundPackage(@Nullable Object coverManager) {
        Object powerManagerServiceImpl = getFieldValue(
                coverManager,
                Constants.SYSTEM_POWER_MANAGER_SERVICE_IMPL_FIELD);
        Object packageName = getFieldValue(
                powerManagerServiceImpl,
                Constants.SYSTEM_FOREGROUND_APP_PACKAGE_FIELD);
        return packageName instanceof String ? (String) packageName : null;
    }

    /**
     * 取当前前台包名候选。
     *
     * <p>**只读字段，不能调 `ActivityTaskManagerService#getTasks`。** 这个方法在
     * `isScreenSkippedWakeup` 里被调用，而该调用点由 `wakePowerGroupLocked` 触发，
     * 此时线程已经持有 `PowerManagerService` 的 `mLock`。`getTasks` 需要
     * `WindowManagerGlobalLock`，持有该锁的 binder 线程又可能在 `startPausing` 里
     * 申请唤醒锁、等 `mLock` —— 两个锁互相等待，system_server 会整体卡死。
     * 2026-11-03 11:37 的死机正是这条路径（watchdog 抓到的栈：`InputReader` 持
     * `mLock` 等 `WindowManagerGlobalLock`，`binder:4309_1A` 持 `WindowManagerGlobalLock`
     * 等 `mLock`）。读字段不取锁，最坏只是拿不到包名，本次不做拦截。
     */
    @NonNull
    private static String[] resolveForegroundPackages(@Nullable Object powerManagerServiceImpl) {
        LinkedHashSet<String> packages = new LinkedHashSet<>();
        addPackageName(packages, getFieldValue(
                powerManagerServiceImpl,
                Constants.SYSTEM_FOREGROUND_APP_PACKAGE_FIELD));
        Object activityTaskManager = getFieldValue(
                powerManagerServiceImpl,
                Constants.SYSTEM_ACTIVITY_TASK_MANAGER_FIELD);
        Object topActivity = getFieldValue(
                activityTaskManager,
                Constants.SYSTEM_TOP_RESUMED_ACTIVITY_FIELD);
        addPackageName(packages, getFieldValue(
                topActivity,
                Constants.SYSTEM_ACTIVITY_RECORD_PACKAGE_FIELD));
        return packages.toArray(new String[0]);
    }

    private static void addPackageName(
            @NonNull LinkedHashSet<String> packages,
            @Nullable Object packageName
    ) {
        if (packageName instanceof String value && !value.isEmpty()) {
            packages.add(value);
        }
    }

    @NonNull
    private static String joinPackageNames(@Nullable String[] packageNames) {
        if (packageNames == null || packageNames.length == 0) return "[]";
        StringBuilder builder = new StringBuilder();
        for (String packageName : packageNames) {
            if (packageName == null || packageName.isEmpty()) continue;
            if (builder.length() > 0) builder.append(',');
            builder.append(packageName);
        }
        return builder.length() == 0 ? "[]" : builder.toString();
    }

    private void logRearWakeDecision(
            int groupId,
            @Nullable Object details,
            @Nullable String[] packageNames,
            boolean skipped
    ) {
        long now = SystemClock.elapsedRealtime();
        if (!skipped && now - lastRearWakeDecisionLoggedAtElapsed < REAR_WAKE_DECISION_LOG_INTERVAL_MS) {
            return;
        }
        lastRearWakeDecisionLoggedAtElapsed = now;
        module.log(Log.DEBUG, Constants.LOG_TAG,
                "Rear screen wake decision: groupId=" + groupId
                        + ", details=" + String.valueOf(details)
                        + ", foreground=" + joinPackageNames(packageNames)
                        + ", skipped=" + skipped);
    }
}
