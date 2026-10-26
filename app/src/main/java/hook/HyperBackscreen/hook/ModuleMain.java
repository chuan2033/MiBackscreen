package hook.HyperBackscreen.hook;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;

import hook.HyperBackscreen.bridge.PrefsBridge;
import hook.HyperBackscreen.BuildConfig;
import hook.HyperBackscreen.bridge.DiagnosticLogStore;
import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.common.ModuleStrings;
import hook.HyperBackscreen.common.BackgroundTasks;
import hook.HyperBackscreen.common.RearScreenWakeMatcher;
import hook.HyperBackscreen.common.ThemeResourceAccess;
import hook.HyperBackscreen.ui.SwipePanelHost;
import hook.HyperBackscreen.ui.battery.BatteryRingHost;
import hook.HyperBackscreen.hook.target.SubScreenTargets;
import hook.HyperBackscreen.hook.target.TargetMembers;
import hook.HyperBackscreen.hook.target.TargetResolver;
import io.github.libxposed.api.XposedModule;

public class ModuleMain extends XposedModule {
    private final Set<Method> installedMethods = new HashSet<>();
    private final Set<Constructor<?>> installedConstructors = new HashSet<>();
    private final AtomicReference<Integer> pendingSelection = new AtomicReference<>();
    private final AtomicBoolean selectionSyncRunning = new AtomicBoolean();
    private final AtomicBoolean aiIndexSyncRunning = new AtomicBoolean();
    private volatile boolean themeAiAppPageVisible;
    /** 处于前台/已创建的 AI 应用卡链路页面实例；清空才代表离开该链路。 */
    private final Set<Object> themeAiAppFlowActivities =
            Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<String> pendingRepairs = new HashSet<>();
    private final ScheduledThreadPoolExecutor repairExecutor = createRepairExecutor();

    private static ScheduledThreadPoolExecutor createRepairExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "MiBackscreen-Repair");
            thread.setDaemon(true);
            return thread;
        });
        executor.setKeepAliveTime(30, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
    private static final long REAR_SELECTION_PENDING_TIMEOUT_MS = 15_000L;
    private static final long THEME_DATABASE_SYNC_DEDUP_WINDOW_MS = 2_000L;
    private static final long AI_APP_INDEX_SYNC_DEDUP_WINDOW_MS = 2_000L;
    private static final long REAR_WAKE_DECISION_LOG_INTERVAL_MS = 5_000L;
    private static final long MAX_EDIT_CONFIG_BYTES = 512 * 1024L;
    private static final long MAX_THEME_INDEX_BYTES = 2 * 1024 * 1024L;
    private volatile boolean systemHooksInstalled = false;
    private volatile boolean hooksInstalled = false;
    private volatile boolean themeStoreHooksInstalled = false;
    private volatile boolean personalAssistantHooksInstalled = false;
    private final Set<Object> themeSettingsShortcutControllers =
            Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Object> themeAiShortcutControllers =
            Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<View, PendingRearSelection> pendingRearSelections =
            Collections.synchronizedMap(new WeakHashMap<>());
    @Nullable
    private volatile Integer lastThemeDatabaseSyncedWidgetId;
    private volatile long lastThemeDatabaseSyncedAtElapsed;
    private volatile long lastAiAppIndexSyncedAtElapsed;
    private volatile long lastRearWakeDecisionLoggedAtElapsed;
    /** 背屏息屏超时覆盖最近一次生效值，只在变化时记日志（该方法每次电源状态刷新都会调）。 */
    private volatile long lastRearScreenOffTimeoutAppliedMs = Long.MIN_VALUE;
    private volatile long lastSubscreenAppInfoModified = Long.MIN_VALUE;
    private volatile long lastSubscreenAppInfoLength = Long.MIN_VALUE;
    private volatile int lastSubscreenAppInfoCount = Integer.MIN_VALUE;
    /** insertAppWidget 交易期间置位，供应用卡列表 getter 判断是否需要封顶 size。 */
    private static final ThreadLocal<Boolean> APP_CARD_INSERT_ACTIVE = new ThreadLocal<>();

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        // 供被 Hook 进程（背屏）内的 PrefsBridge 取远程偏好使用
        PrefsBridge.attachModule(this);
        log(Log.INFO, Constants.LOG_TAG, "onModuleLoaded: " + param.getProcessName());
        if (param.isSystemServer()) {
            ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
            if (contextClassLoader != null) {
                installSystemHooksOnce(contextClassLoader);
            }
            installSystemHooksOnce(ClassLoader.getSystemClassLoader());
        }
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        installSystemHooksOnce(param.getClassLoader());
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String packageName = param.getPackageName();
        if (BuildConfig.DEBUG && (Constants.TARGET_PACKAGE.equals(packageName)
                || Constants.THEME_STORE_PACKAGE.equals(packageName))) {
            hookMethodIfPresent(findDeclaredMethod(Instrumentation.class, "callApplicationOnCreate", Application.class),
                    "debug host probe", chain -> {
                        Object result = chain.proceed();
                        try {
                            Class.forName("hook.HyperBackscreen.hook.DebugHostProbe")
                                    .getMethod("register", Context.class, XposedModule.class, ClassLoader.class)
                                    .invoke(null, chain.getArgs().get(0), this, param.getClassLoader());
                        } catch (Throwable error) {
                            log(Log.WARN, Constants.LOG_TAG, "Debug host probe unavailable", error);
                        }
                        return result;
                    });
        }

        if (Constants.PERSONAL_ASSISTANT_PACKAGE.equals(packageName)) {
            if (personalAssistantHooksInstalled) return;
            synchronized (this) {
                if (personalAssistantHooksInstalled) return;
                try {
                    installPersonalAssistantStoreHooks(param.getClassLoader());
                    personalAssistantHooksInstalled = true;
                    log(Log.INFO, Constants.LOG_TAG, "Personal assistant hooks installed");
                } catch (Throwable throwable) {
                    log(Log.ERROR, Constants.LOG_TAG,
                            "Failed to install personal assistant hooks", throwable);
                }
            }
            return;
        }

        if (Constants.SYSTEM_PACKAGE.equals(packageName)) {
            installSystemHooksOnce(param.getClassLoader());
            return;
        }

        if (Constants.TARGET_PACKAGE.equals(packageName)) {
            if (hooksInstalled) return;
            synchronized (this) {
                if (hooksInstalled) return;
                try {
                    SwipePanelHost.setLoadedModuleApkPath(getModuleApplicationInfo().sourceDir);
                    installTargetResolver(packageName);
                    installLongPressHooks(param.getClassLoader());
                    installSubscreenAppWidgetGateHooks(param.getClassLoader());
                    installAppCardLimitHook(param.getClassLoader());
                    installModuleEntryClickHook(param.getClassLoader());
                    installQuickPanelDispatchHook(param.getClassLoader());
                    installRearScreenSelectionSyncHook(param.getClassLoader());
                    installAutoWallpaperRotationHooks(param.getClassLoader());
                    hooksInstalled = true;
                    log(Log.INFO, Constants.LOG_TAG, "Hooks installed for " + Constants.TARGET_PACKAGE);
                } catch (Throwable throwable) {
                    log(Log.ERROR, Constants.LOG_TAG, "Failed to install hooks", throwable);
                }
            }
            return;
        }

        if (Constants.THEME_STORE_PACKAGE.equals(packageName)) {
            if (themeStoreHooksInstalled) return;
            synchronized (this) {
                if (themeStoreHooksInstalled) return;
                try {
                    installWallpaperLimitHook(param.getClassLoader());
                    installThemeDeviceIdentityHooks(param.getClassLoader());
                    installThemeNetworkDeviceSpoofHook(param.getClassLoader());
                    installThemeLegacyDownloadHook(param.getClassLoader());
                    installRearScreenApplyFixHook(param.getClassLoader());
                    installAiCompanionReuseApplyHook(param.getClassLoader());
                    installThemeFaceEnrollmentHook(param.getClassLoader());
                    installRearScreenPersonalizationPresetHook(param.getClassLoader());
                    installThemeSettingsSelectionSyncHook(param.getClassLoader());
                    installThemeAiAppIndexSyncHooks(param.getClassLoader());
                    installThemeSettingsAiVisibilityHooks(param.getClassLoader());
                    installThemeSettingsShortcutHook(param.getClassLoader());
                    themeStoreHooksInstalled = true;
                    log(Log.INFO, Constants.LOG_TAG, "Theme store hooks installed");
                } catch (Throwable throwable) {
                    log(Log.ERROR, Constants.LOG_TAG, "Failed to install theme store hooks", throwable);
                }
            }
        }

    }

    private void installPersonalAssistantStoreHooks(@NonNull ClassLoader classLoader) {
        Class<?> commonParamsClass = findClass(
                Constants.PERSONAL_ASSISTANT_COMMON_PARAMS_CLASS,
                classLoader);
        if (commonParamsClass == null) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Personal assistant hook target missing: "
                            + Constants.PERSONAL_ASSISTANT_COMMON_PARAMS_CLASS);
            return;
        }

        hookMethodIfPresent(
                commonParamsClass,
                Constants.PERSONAL_ASSISTANT_ENVIRONMENT_SIGNAL_METHOD,
                new Class[]{Context.class, String.class},
                Constants.PERSONAL_ASSISTANT_COMMON_PARAMS_CLASS + "#"
                        + Constants.PERSONAL_ASSISTANT_ENVIRONMENT_SIGNAL_METHOD,
                chain -> {
                    Object result = chain.proceed();
                    if (result instanceof JSONObject environmentSignal) {
                        if (!PrefsBridge.shouldEnable18ProFeatures(this)) return result;
                        environmentSignal.put("phoneDevice",
                                Constants.PERSONAL_ASSISTANT_REAR_DEVICE);
                        environmentSignal.put("phoneModel",
                                Constants.PERSONAL_ASSISTANT_REAR_MODEL);
                    }
                    return result;
                }
        );

        Class<?> repositoryClass = findClass(
                Constants.PERSONAL_ASSISTANT_STORE_REPOSITORY_CLASS,
                classLoader);
        Class<?> responseClass = findClass(
                Constants.PERSONAL_ASSISTANT_STORE_RESPONSE_CLASS,
                classLoader);
        if (repositoryClass != null && responseClass != null) {
            hookMethodIfPresent(
                    repositoryClass,
                    Constants.PERSONAL_ASSISTANT_STORE_CONVERT_METHOD,
                    new Class[]{repositoryClass, responseClass},
                    Constants.PERSONAL_ASSISTANT_STORE_REPOSITORY_CLASS + "#"
                            + Constants.PERSONAL_ASSISTANT_STORE_CONVERT_METHOD,
                    chain -> {
                        Object result = chain.proceed();
                        logBackScreenStoreData("cloud", result);
                        return result;
                    }
            );
        } else {
            log(Log.WARN, Constants.LOG_TAG,
                    "Personal assistant store repository hook target missing");
        }

        Class<?> presetParserClass = findClass(
                Constants.PERSONAL_ASSISTANT_PRESET_PARSER_CLASS,
                classLoader);
        if (presetParserClass != null) {
            hookMethodIfPresent(
                    presetParserClass,
                    Constants.PERSONAL_ASSISTANT_PRESET_PARSE_METHOD,
                    new Class[]{},
                    Constants.PERSONAL_ASSISTANT_PRESET_PARSER_CLASS + "#"
                            + Constants.PERSONAL_ASSISTANT_PRESET_PARSE_METHOD,
                    chain -> {
                        Object result = chain.proceed();
                        if (PrefsBridge.shouldEnable18ProFeatures(this)) {
                            Object modulePreset = parsePersonalAssistantPreset(
                                    classLoader,
                                    presetParserClass,
                                    new File(Constants.PERSONAL_ASSISTANT_PRESET_JSON_PATH));
                            if (modulePreset instanceof List<?> list && !list.isEmpty()) {
                                result = modulePreset;
                            }
                        }
                        logBackScreenStoreData("preset", result);
                        return result;
                    }
            );
        }
    }

    @Nullable
    private Object parsePersonalAssistantPreset(
            @NonNull ClassLoader classLoader,
            @NonNull Class<?> parserClass,
            @NonNull File jsonFile
    ) {
        if (!jsonFile.isFile()) {
            log(Log.WARN, Constants.LOG_TAG,
                    "BackScreenStore preset file unavailable: " + jsonFile);
            return null;
        }
        try {
            Class<?> gsonClass = findClass(Constants.PERSONAL_ASSISTANT_GSON_CLASS, classLoader);
            Class<?> presetJsonClass = findClass(
                    Constants.PERSONAL_ASSISTANT_PRESET_JSON_CLASS,
                    classLoader);
            Class<?> presetCategoryClass = findClass(
                    Constants.PERSONAL_ASSISTANT_PRESET_CATEGORY_CLASS,
                    classLoader);
            if (gsonClass == null || presetJsonClass == null || presetCategoryClass == null) {
                log(Log.WARN, Constants.LOG_TAG,
                        "BackScreenStore preset model classes unavailable");
                return null;
            }

            Object gson = gsonClass.getDeclaredConstructor().newInstance();
            Method fromJson = gsonClass.getMethod("fromJson", String.class, Class.class);
            Object presetJson = fromJson.invoke(
                    gson,
                    readUtf8(jsonFile, MAX_THEME_INDEX_BYTES),
                    presetJsonClass);
            Object productsValue = invokeNoArg(presetJson, "getProducts");
            if (!(productsValue instanceof List<?> products) || products.isEmpty()) {
                log(Log.WARN, Constants.LOG_TAG,
                        "BackScreenStore module preset contains no products");
                return null;
            }

            Method convertCategory = parserClass.getDeclaredMethod(
                    "a",
                    presetCategoryClass,
                    String.class);
            convertCategory.setAccessible(true);
            Locale locale = Locale.getDefault();
            String localeKey = locale.getLanguage() + "_" + locale.getCountry();
            ArrayList<Object> categories = new ArrayList<>();
            for (Object product : products) {
                if (product == null || !presetCategoryClass.isInstance(product)) continue;
                Object category = convertCategory.invoke(null, product, localeKey);
                Object items = invokeNoArg(category, "getItems");
                if (category != null && items instanceof List<?> list && !list.isEmpty()) {
                    categories.add(category);
                }
            }
            return categories.isEmpty() ? null : categories;
        } catch (Throwable throwable) {
            log(Log.ERROR, Constants.LOG_TAG,
                    "BackScreenStore module preset parse failed", throwable);
            return null;
        }
    }

    private void logBackScreenStoreData(@NonNull String source, @Nullable Object value) {
        if (!(value instanceof List<?> categories)) {
            log(Log.DEBUG, Constants.LOG_TAG,
                    "BackScreenStore " + source + ": result is " + value);
            return;
        }
        int itemCount = 0;
        StringBuilder names = new StringBuilder();
        int categoryLimit = Math.min(categories.size(), 8);
        for (int i = 0; i < categoryLimit; i++) {
            Object category = categories.get(i);
            String title = String.valueOf(invokeNoArg(category, "getCategoryTitle"));
            Object itemsValue = invokeNoArg(category, "getItems");
            if (!(itemsValue instanceof List<?> items)) {
                continue;
            }
            itemCount += items.size();
            if (names.length() > 0) names.append(" | ");
            names.append(title).append(": ");
            int itemLimit = Math.min(items.size(), 8);
            for (int j = 0; j < itemLimit; j++) {
                if (j > 0) names.append(", ");
                names.append(invokeNoArg(items.get(j), "getAppName"));
            }
            if (items.size() > itemLimit) {
                names.append("...");
            }
        }
        for (int i = categoryLimit; i < categories.size(); i++) {
            Object itemsValue = invokeNoArg(categories.get(i), "getItems");
            if (itemsValue instanceof List<?> items) {
                itemCount += items.size();
            }
        }
        log(Log.INFO, Constants.LOG_TAG,
                "BackScreenStore " + source
                        + ": categories=" + categories.size()
                        + ", items=" + itemCount
                        + ", names=[" + names + "]");
    }

    @Nullable
    private static Object invokeNoArg(@Nullable Object target, @NonNull String methodName) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * PowerGroup#getGroupId() 是包可见方法，只能按声明方法查找后 setAccessible。
     * 取不到就返回 null，调用方据此放行原逻辑。
     */
    @Nullable
    private static Integer readPowerGroupId(@Nullable Object powerGroup) {
        if (powerGroup == null) return null;
        Method method = findDeclaredMethod(
                powerGroup.getClass(),
                Constants.SYSTEM_POWER_GROUP_GET_GROUP_ID_METHOD);
        if (method == null) return null;
        try {
            Object value = method.invoke(powerGroup);
            return value instanceof Integer ? (Integer) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void installSystemHooksOnce(@NonNull ClassLoader classLoader) {
        if (systemHooksInstalled) return;
        synchronized (this) {
            if (systemHooksInstalled) return;
            try {
                if (installSystemHooks(classLoader)) {
                    systemHooksInstalled = true;
                    log(Log.INFO, Constants.LOG_TAG, "System hooks installed");
                }
            } catch (Throwable throwable) {
                log(Log.ERROR, Constants.LOG_TAG, "Failed to install system hooks", throwable);
            }
        }
    }

    private boolean installSystemHooks(@NonNull ClassLoader classLoader) {
        Class<?> coverManagerClass = findClass(Constants.SYSTEM_DUAL_SCREEN_COVER_MANAGER_CLASS, classLoader);
        if (coverManagerClass == null) {
            log(Log.WARN, Constants.LOG_TAG,
                    "System hook target missing: " + Constants.SYSTEM_DUAL_SCREEN_COVER_MANAGER_CLASS);
            return false;
        }
        Class<?> powerManagerServiceImplClass = findClass(
                Constants.SYSTEM_POWER_MANAGER_SERVICE_IMPL_CLASS,
                classLoader);

        boolean coverHookInstalled = hookMethodIfPresent(
                coverManagerClass,
                Constants.SYSTEM_SHOW_COVER_VIEW_METHOD,
                new Class[]{int.class},
                Constants.SYSTEM_DUAL_SCREEN_COVER_MANAGER_CLASS + "#"
                        + Constants.SYSTEM_SHOW_COVER_VIEW_METHOD,
                chain -> {
                    Object displayIdValue = chain.getArgs().get(0);
                    if (displayIdValue instanceof Integer
                            && ((Integer) displayIdValue) == 1
                            && PrefsBridge.shouldDisableRearScreenCover(this)) {
                        log(Log.DEBUG, Constants.LOG_TAG, "Rear screen cover skipped");
                        return null;
                    }
                    return chain.proceed();
                }
        );

        boolean powerWakeHookInstalled = hookMethodIfPresent(
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
                    if (RearScreenScheduleHost.shouldBlockRearWake(this, groupIdValue)) {
                        log(Log.DEBUG, Constants.LOG_TAG,
                                "Rear screen wake skipped by schedule: " + detailsValue);
                        return true;
                    }
                    if (groupIdValue instanceof Integer
                            && RearScreenWakeMatcher.isRearDoubleTapWake(
                                    (Integer) groupIdValue,
                                    detailsValue)
                            && PrefsBridge.shouldDisableDoubleTapWake(this)) {
                        String[] packageNames = resolveForegroundPackages(chain.getThisObject());
                        boolean skipped = PrefsBridge.shouldSkipDoubleTapWakeForPackages(this, packageNames);
                        logRearWakeDecision((Integer) groupIdValue, detailsValue, packageNames, skipped);
                        if (skipped) {
                            log(Log.DEBUG, Constants.LOG_TAG,
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
        boolean coverWakeHookInstalled = !powerWakeHookInstalled && hookMethodIfPresent(
                coverManagerClass,
                Constants.SYSTEM_IS_SCREEN_SKIPPED_WAKEUP_METHOD,
                new Class[]{int.class, String.class, int.class},
                Constants.SYSTEM_DUAL_SCREEN_COVER_MANAGER_CLASS + "#"
                        + Constants.SYSTEM_IS_SCREEN_SKIPPED_WAKEUP_METHOD,
                chain -> {
                    Object groupIdValue = chain.getArgs().get(0);
                    Object detailsValue = chain.getArgs().get(1);
                    if (RearScreenScheduleHost.shouldBlockRearWake(this, groupIdValue)) {
                        log(Log.DEBUG, Constants.LOG_TAG,
                                "Rear screen wake skipped by schedule: " + detailsValue);
                        return true;
                    }
                    if (groupIdValue instanceof Integer
                            && RearScreenWakeMatcher.isRearDoubleTapWake(
                                    (Integer) groupIdValue,
                                    detailsValue)
                            && PrefsBridge.shouldDisableDoubleTapWake(this)) {
                        String[] packageNames = resolveForegroundPackages(getFieldValue(
                                chain.getThisObject(),
                                Constants.SYSTEM_POWER_MANAGER_SERVICE_IMPL_FIELD));
                        if (packageNames.length == 0) {
                            String packageName = resolveForegroundPackage(chain.getThisObject());
                            if (packageName != null) {
                                packageNames = new String[]{packageName};
                            }
                        }
                        boolean skipped = PrefsBridge.shouldSkipDoubleTapWakeForPackages(this, packageNames);
                        logRearWakeDecision((Integer) groupIdValue, detailsValue, packageNames, skipped);
                        if (skipped) {
                            log(Log.DEBUG, Constants.LOG_TAG,
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
            log(Log.WARN, Constants.LOG_TAG,
                    "System hook target missing: " + Constants.SYSTEM_POWER_GROUP_CLASS);
        } else {
            hookMethodIfPresent(
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
                        // 定时关闭背屏优先于其它档位：窗口内把超时压到 1 秒，
                        // 背屏按系统自己的熄灭流程关掉，之后靠 isScreenSkippedWakeup 挡住唤醒。
                        if (rearGroup && PrefsBridge.isRearScreenScheduleActiveNow(this)) {
                            return (long) Constants.REAR_SCREEN_SCHEDULE_OFF_TIMEOUT_MS;
                        }
                        long overrideMillis = PrefsBridge.readRearScreenOffTimeoutMillis(this);
                        // 游戏（主屏高负载）期间如果开了「保持背屏亮」，强制按「一直亮屏」处理：
                        // 游戏模式会把 subscreen_display_time 改写成 30 秒，只抬设置值挡不住。
                        if (overrideMillis <= 0L
                                && RearScreenGameModeState.isActive()
                                && PrefsBridge.shouldKeepRearScreenOnInGame(this)) {
                            overrideMillis = Constants.REAR_SCREEN_OFF_TIMEOUT_ALWAYS_MS;
                        }
                        if (overrideMillis <= 0L) {
                            return chain.proceed();
                        }
                        if (!rearGroup) {
                            return chain.proceed();
                        }
                        if (overrideMillis != lastRearScreenOffTimeoutAppliedMs) {
                            lastRearScreenOffTimeoutAppliedMs = overrideMillis;
                            log(Log.INFO, Constants.LOG_TAG,
                                    "Rear screen off timeout override: " + overrideMillis + " ms");
                        }
                        return overrideMillis;
                    }
            );
        }
        // 时间窗边界没人会触发电源状态重算，交给这个低频线程；只在跨边界时动作。
        RearScreenScheduleHost.install(this);
        // 游戏时保持背屏亮：DualScreenCoverManager.updateHighLoadSceneMode(int, boolean) 在主屏
        // 进入高负载（横屏大型游戏）时直接调用 goToSleepSecondaryDisplay(..., "mainScreen:highLoadGameMode", 0)
        // 关掉背屏，日志原文 "Secondary screen turned OFF, Main screen keep ON"。这条路径不走超时，
        // 只能在这里拦；开关开启时整段跳过，连 mScreenStates 也不更新 —— 否则状态会记成
        // 「背屏已关」而实际还亮着，后续双击唤醒/电源键判断都会错。
        hookMethodIfPresent(
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
                            highLoad && PrefsBridge.shouldKeepRearScreenOnInGame(this);
                    if (RearScreenGameModeState.setActive(highLoad)) {
                        log(Log.INFO, Constants.LOG_TAG, "High load game mode: " + highLoad
                                + ", keep rear screen on: " + keepRearScreenOn);
                    }
                    if (keepRearScreenOn) {
                        log(Log.INFO, Constants.LOG_TAG,
                                "Rear screen kept on in high load game mode");
                        return null;
                    }
                    return chain.proceed();
                }
        );
        return coverHookInstalled && (powerWakeHookInstalled || coverWakeHookInstalled);
    }

    private void installLongPressHooks(@NonNull ClassLoader classLoader) {
        Class<?> gestureClass = findLongPressGestureClass(classLoader);
        if (gestureClass != null) {
            hookLongPressMethod(gestureClass, Constants.HOOK_METHOD_GATE_NEW, new Class[]{MotionEvent.class}, false);
            hookLongPressMethod(gestureClass, Constants.HOOK_METHOD_LONG_PRESS_TOUCH_NEW, new Class[]{MotionEvent.class}, null);
            hookLongPressMethod(gestureClass, Constants.HOOK_METHOD_RUN, new Class[]{}, null);
            log(Log.INFO, Constants.LOG_TAG,
                    "Long press hook target resolved: " + gestureClass.getName());
        }

        Class<?> legacyGestureClass = findClass(Constants.HOOK_CLASS, classLoader);
        if (legacyGestureClass != null && legacyGestureClass != gestureClass) {
            hookLongPressMethod(legacyGestureClass, Constants.HOOK_METHOD_GATE, new Class[]{MotionEvent.class}, false);
            hookLongPressMethod(legacyGestureClass, Constants.HOOK_METHOD_RUN, new Class[]{}, null);
        }

        if (gestureClass == null && legacyGestureClass == null) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Long press hook targets missing: "
                            + Constants.HOOK_CLASS_LONG_PRESS_18PRO + ", "
                            + Constants.HOOK_CLASS_LONG_PRESS_OS4 + ", "
                            + Constants.HOOK_CLASS_LONG_PRESS_NEW + ", "
                            + Constants.HOOK_CLASS);
        }
    }

    /**
     * 长按手势类的混淆名逐版本重排（Z1.v → k2.s → U1.C）。只按名字取类，会在名字被
     * 让给别的类时静默装到错误目标上，所以逐个候选校验三个 hook 方法是否齐全。
     */
    @Nullable
    private static Class<?> findLongPressGestureClass(@NonNull ClassLoader classLoader) {
        String[] classNames = {
                Constants.HOOK_CLASS_LONG_PRESS_18PRO,
                Constants.HOOK_CLASS_LONG_PRESS_OS4,
                Constants.HOOK_CLASS_LONG_PRESS_NEW
        };
        for (String className : classNames) {
            Class<?> candidate = findClass(className, classLoader);
            if (candidate == null) continue;
            if (findDeclaredMethod(candidate, Constants.HOOK_METHOD_GATE_NEW, MotionEvent.class) == null) continue;
            if (findDeclaredMethod(candidate, Constants.HOOK_METHOD_LONG_PRESS_TOUCH_NEW, MotionEvent.class) == null) continue;
            if (findDeclaredMethod(candidate, Constants.HOOK_METHOD_RUN) == null) continue;
            return candidate;
        }
        return null;
    }
    /**
     * 给背屏中心进程装上目标定位器：DexKit 桥、按宿主版本缓存、判据失配时回退到旧类名。
     */
    private void installTargetResolver(@NonNull String packageName) {
        Context context = null;
        String apkPath = null;
        long versionCode = 0L;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getDeclaredMethod("currentActivityThread").invoke(null);
            Object systemContext = thread == null
                    ? null
                    : activityThread.getDeclaredMethod("getSystemContext").invoke(thread);
            Object application = activityThread.getDeclaredMethod("currentApplication").invoke(null);
            context = systemContext instanceof Context ? (Context) systemContext
                    : application instanceof Context ? (Context) application : null;
            if (context != null) {
                android.content.pm.PackageInfo info = context.getPackageManager()
                        .getPackageInfo(packageName, 0);
                apkPath = info.applicationInfo.publicSourceDir != null
                        ? info.applicationInfo.publicSourceDir
                        : info.applicationInfo.sourceDir;
                versionCode = info.getLongVersionCode();
                // 系统 Context 属于 android 包，没有数据目录；缓存要写进宿主自己的 files。
                context = context.createPackageContext(packageName, 0);
            }
        } catch (Throwable error) {
            log(Log.WARN, Constants.LOG_TAG, "Target resolver init failed: " + error);
        }
        TargetResolver.install(context, apkPath, versionCode,
                message -> log(Log.DEBUG, Constants.LOG_TAG, message));
        log(Log.INFO, Constants.LOG_TAG,
                "Target resolver: " + TargetResolver.state() + ", host version " + versionCode
                        + ", apk " + apkPath);
    }

    private void installSubscreenAppWidgetGateHooks(@NonNull ClassLoader classLoader) {
        Class<?> deviceConfigClass = TargetResolver.resolveClass(classLoader, SubScreenTargets.DEVICE_CONFIG);
        if (deviceConfigClass != null) {
            hookBooleanTrueIfPresent(
                    deviceConfigClass,
                    Constants.SUBSCREEN_DEVICE_CONFIG_CLASS,
                    Constants.SUBSCREEN_DEVICE_CONFIG_APP_WIDGET_METHOD);
        }

        Class<?> guideSettingsClass = TargetResolver.resolveClass(classLoader, SubScreenTargets.GUIDE_SETTINGS);
        if (guideSettingsClass != null) {
            hookMethodIfPresent(
                    guideSettingsClass,
                    Constants.SUBSCREEN_GUIDE_HIDDEN_METHOD,
                    new Class[]{String.class},
                    Constants.SUBSCREEN_GUIDE_SETTINGS_CLASS + "#"
                            + Constants.SUBSCREEN_GUIDE_HIDDEN_METHOD,
                    chain -> {
                        Object key = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                        if (Constants.SUBSCREEN_APP_CARD_GUIDE_KEY.equals(key)
                                && PrefsBridge.shouldEnable18ProFeatures(this)) {
                            return false;
                        }
                        return chain.proceed();
                    }
            );
        } else {
            log(Log.DEBUG, Constants.LOG_TAG,
                    "App widget guide hook target missing: "
                            + Constants.SUBSCREEN_GUIDE_SETTINGS_CLASS);
        }

        hookSubscreenAppWidgetSecureSetting();
        hookSubscreenLauncherPanelGesture(classLoader);
    }

    /**
     * 背屏应用卡上限由 SubScreenService 的 Binder 在 insertAppWidget 交易里用 15 判定，超限回 -2。
     * 列表 getter 返回内部列表的副本，因此只在这次交易期间把副本的 size 封顶到 14：判定放行后
     * 新增项与保存都作用在同一副本上，落盘数据完整。
     */
    private void installAppCardLimitHook(@NonNull ClassLoader classLoader) {
        Class<?> binderClass = TargetResolver.resolveClass(classLoader, SubScreenTargets.SERVICE_BINDER);
        Class<?> listManagerClass = TargetResolver.resolveClass(classLoader, SubScreenTargets.APP_LIST_MANAGER);
        if (binderClass == null || listManagerClass == null) {
            log(Log.WARN, Constants.LOG_TAG, "App card limit targets missing: "
                    + Constants.SUBSCREEN_SERVICE_BINDER_CLASS + ", "
                    + Constants.SUBSCREEN_APP_LIST_MANAGER_CLASS);
            return;
        }

        hookMethodIfPresent(
                findDeclaredMethod(binderClass, Constants.SUBSCREEN_ON_TRANSACT_METHOD,
                        int.class, Parcel.class, Parcel.class, int.class),
                Constants.SUBSCREEN_SERVICE_BINDER_CLASS + "#" + Constants.SUBSCREEN_ON_TRANSACT_METHOD,
                chain -> {
                    Object transaction = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (!(transaction instanceof Integer)
                            || (Integer) transaction != Constants.SUBSCREEN_INSERT_APP_WIDGET_TRANSACTION) {
                        return chain.proceed();
                    }
                    APP_CARD_INSERT_ACTIVE.set(Boolean.TRUE);
                    try {
                        return chain.proceed();
                    } finally {
                        APP_CARD_INSERT_ACTIVE.remove();
                    }
                }
        );

        hookMethodIfPresent(
                findDeclaredMethod(listManagerClass, Constants.SUBSCREEN_APP_LIST_GETTER_METHOD),
                Constants.SUBSCREEN_APP_LIST_MANAGER_CLASS + "#"
                        + Constants.SUBSCREEN_APP_LIST_GETTER_METHOD,
                chain -> {
                    refreshSubscreenAppInfoCacheIfChanged(classLoader, chain.getThisObject());
                    Object list = chain.proceed();
                    if (!(list instanceof ArrayList)) return list;
                    ArrayList<?> items = (ArrayList<?>) list;
                    if (Boolean.TRUE.equals(APP_CARD_INSERT_ACTIVE.get())
                            && PrefsBridge.shouldEnableAppCard(this)
                            && PrefsBridge.shouldRemoveAppCardLimit(this)
                            && items.size() >= Constants.SUBSCREEN_APP_CARD_LIMIT) {
                        return new SizeCappedList(items, Constants.SUBSCREEN_APP_CARD_LIMIT - 1);
                    }
                    // c() also feeds Binder capacity checks and edits. UI-only entries belong in d().
                    return list;
                }
        );

        // 背屏上滑面板通过这条分发链取数据；进入前先同步官方索引并刷新宿主缓存。
        hookMethodIfPresent(
                findDeclaredMethod(listManagerClass,
                        Constants.SUBSCREEN_APP_LIST_DISPATCH_METHOD, Consumer.class),
                Constants.SUBSCREEN_APP_LIST_MANAGER_CLASS + "#"
                        + Constants.SUBSCREEN_APP_LIST_DISPATCH_METHOD,
                chain -> {
                    refreshSubscreenAppInfoCacheIfChanged(classLoader, chain.getThisObject());
                    return chain.proceed();
                }
        );

        // 保存路径按 size() 索引遍历，封顶副本要先还原成普通列表，否则尾部条目会被截掉。
        hookMethodIfPresent(
                findDeclaredMethodWithFirstParam(
                        listManagerClass,
                        Constants.SUBSCREEN_APP_LIST_SAVE_METHOD,
                        ArrayList.class),
                Constants.SUBSCREEN_APP_LIST_MANAGER_CLASS + "#"
                        + Constants.SUBSCREEN_APP_LIST_SAVE_METHOD,
                chain -> {
                    Object list = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (!(list instanceof SizeCappedList)) {
                        return chain.proceed();
                    }
                    List<Object> args = new ArrayList<>(chain.getArgs());
                    args.set(0, ((SizeCappedList) list).fullCopy());
                    return chain.proceed(args.toArray());
                }
        );

        log(Log.INFO, Constants.LOG_TAG, "App card limit hook installed");
    }

    private void refreshSubscreenAppInfoCacheIfChanged(
            @NonNull ClassLoader classLoader,
            @Nullable Object listManager
    ) {
        if (listManager == null) return;
        syncOfficialPanelAppCardWithSubscreenIndex();
        cleanupDeletedSubscreenAppInfoEntries();
        File appInfoFile = new File(Constants.SUBSCREEN_APP_INFO_FILE);
        if (!appInfoFile.isFile()) return;

        long modified = appInfoFile.lastModified();
        long length = appInfoFile.length();
        if (modified == lastSubscreenAppInfoModified
                && length == lastSubscreenAppInfoLength) {
            return;
        }

        try {
            ArrayList<Object> items = readSubscreenAppInfoList(classLoader, appInfoFile);
            if (items == null) return;
            if (setFieldValue(listManager, "c", items)) {
                lastSubscreenAppInfoModified = modified;
                lastSubscreenAppInfoLength = length;
                lastSubscreenAppInfoCount = items.size();
                notifySubscreenAppInfoListeners(classLoader, listManager, items, "refresh");
                log(Log.INFO, Constants.LOG_TAG,
                        "Refreshed SubScreenCenter app list cache: size=" + items.size());
            }
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "refreshSubscreenAppInfoCacheIfChanged failed", e);
        }
    }

    private void refreshActiveSubscreenAppInfoCache(@NonNull ClassLoader classLoader) {
        Class<?> listManagerClass = findClass(Constants.SUBSCREEN_APP_LIST_MANAGER_CLASS, classLoader);
        if (listManagerClass == null) return;
        Object listManager = getStaticFieldValueByType(listManagerClass, listManagerClass);
        refreshSubscreenAppInfoCacheIfChanged(classLoader, listManager);
    }

    private void notifySubscreenAppInfoListeners(
            @NonNull ClassLoader classLoader,
            @NonNull Object listManager,
            @NonNull ArrayList<Object> items,
            @NonNull String source
    ) {
        Object listenersValue = getFieldValue(listManager, "a");
        if (!(listenersValue instanceof Collection<?> listeners) || listeners.isEmpty()) return;
        Class<?> entryClass = TargetResolver.resolveClass(classLoader, SubScreenTargets.LAUNCHER_ENTRY);
        if (entryClass == null) return;
        ArrayList<Object> payload = new ArrayList<>(items);
        int notified = 0;
        for (Object listener : new ArrayList<>(listeners)) {
            if (listener == null) continue;
            Method callback = findDeclaredMethod(
                    listener.getClass(), "a", ArrayList.class, String.class, entryClass);
            if (callback == null) continue;
            try {
                callback.invoke(listener, new ArrayList<>(payload), source, null);
                notified++;
            } catch (Throwable e) {
                log(Log.WARN, Constants.LOG_TAG,
                        "SubScreenCenter app list listener notify failed", e);
            }
        }
        if (notified > 0) {
            log(Log.INFO, Constants.LOG_TAG,
                    "Notified SubScreenCenter app list listeners: count=" + notified
                            + ", size=" + payload.size());
        }
    }

    @Nullable
    private ArrayList<Object> readSubscreenAppInfoList(
            @NonNull ClassLoader classLoader,
            @NonNull File appInfoFile
    ) {
        try {
            Class<?> entryClass = Class.forName(
                    Constants.SUBSCREEN_LAUNCHER_ENTRY_CLASS, false, classLoader);
            Constructor<?> constructor = entryClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            JSONArray array = new JSONArray(readUtf8(appInfoFile, MAX_THEME_INDEX_BYTES));
            ArrayList<Object> items = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                Object entry = constructor.newInstance();
                callMethodQuietly(entry, "c", item.optString("appName", null));
                setFieldValue(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_MTZ_FIELD,
                        item.optString("mtzPath", null));
                setFieldValue(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_BIND_APP_FIELD,
                        item.optString("bindApp", null));
                setFieldValue(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_ICON_FIELD,
                        item.optString("appIconPath", null));
                setFieldValue(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_LIGHT_FIELD,
                        item.optString("previewLightPath", null));
                setFieldValue(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_DARK_FIELD,
                        item.optString("previewDarkPath", null));
                setFieldValue(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_RES_ID_FIELD,
                        item.optString("resId", null));
                setFieldValue(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_GAME_FIELD,
                        item.optBoolean("isGame", false));
                setFieldValue(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_CARD_TYPE_FIELD,
                        item.optInt("appCardType", -1));
                setFieldValue(entry, Constants.SUBSCREEN_LAUNCHER_ENTRY_PRESET_FIELD,
                        item.optBoolean("isPreset", false));
                items.add(entry);
            }
            return items;
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "readSubscreenAppInfoList failed", e);
            return null;
        }
    }

    /**
     * 模块卡片的点击。
     *
     * 宿主 {@code e2.l.onClick} 对 {@code k != 3} 的条目直接 {@code startActivity(item.m)}，
     * 那会把模块设置页开到主屏上；这里改成在背屏当前窗口里展开快捷面板，和原来的上滑面板
     * 是同一套 UI，只是入口换成卡片。
     */
    private void installModuleEntryClickHook(@NonNull ClassLoader classLoader) {
        Class<?> holderClass = TargetResolver.resolveClass(classLoader, SubScreenTargets.LAUNCHER_HOLDER);
        if (holderClass == null) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Module entry click target missing: " + Constants.SUBSCREEN_LAUNCHER_HOLDER_CLASS);
            return;
        }
        Method clickMethod = findDeclaredMethod(
                holderClass, Constants.SUBSCREEN_LAUNCHER_HOLDER_CLICK_METHOD, View.class);
        if (clickMethod == null) {
            log(Log.WARN, Constants.LOG_TAG, "Module entry click method missing: "
                    + Constants.SUBSCREEN_LAUNCHER_HOLDER_CLASS);
            return;
        }
        hookMethodIfPresent(
                clickMethod,
                Constants.SUBSCREEN_LAUNCHER_HOLDER_CLASS + "#"
                        + Constants.SUBSCREEN_LAUNCHER_HOLDER_CLICK_METHOD,
                chain -> {
                    Object item = getFieldValue(chain.getThisObject(),
                            Constants.SUBSCREEN_LAUNCHER_HOLDER_ITEM_FIELD);
                    if (!PanelAppCard.isModuleEntry(classLoader, item)) return chain.proceed();
                    if (!PrefsBridge.shouldEnableAppCard(this)) return null;
                    Object clicked = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    Activity activity = resolveHostActivity(clicked);
                    if (activity == null) return chain.proceed();
                    if (SwipePanelHost.shouldSuppressModuleCardClick()) {
                        log(Log.DEBUG, Constants.LOG_TAG,
                                "Quick panel card click ignored after launcher swipe");
                        return null;
                    }
                    SwipePanelHost.show(activity, clicked instanceof View ? (View) clicked : null);
                    log(Log.INFO, Constants.LOG_TAG, "Quick panel opened from card entry");
                    return null;
                }
        );
        log(Log.INFO, Constants.LOG_TAG, "Module entry click hook installed");
    }

    private void installQuickPanelDispatchHook(@NonNull ClassLoader classLoader) {
        Class<?> launcherClass = findClass(Constants.SUBSCREEN_LAUNCHER_ACTIVITY_CLASS, classLoader);
        if (launcherClass == null) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Quick panel dispatch target missing: "
                            + Constants.SUBSCREEN_LAUNCHER_ACTIVITY_CLASS);
            return;
        }
        for (String lifecycle : new String[]{"onPause", "onDestroy"}) {
            hookMethodIfPresent(findDeclaredMethod(launcherClass, lifecycle),
                    Constants.SUBSCREEN_LAUNCHER_ACTIVITY_CLASS + "#" + lifecycle, chain -> {
                        SwipePanelHost.release((Activity) chain.getThisObject());
                        BatteryRingHost.release((Activity) chain.getThisObject());
                        return chain.proceed();
                    });
        }
        hookMethodIfPresent(findDeclaredMethod(launcherClass, "onResume"),
                Constants.SUBSCREEN_LAUNCHER_ACTIVITY_CLASS + "#onResume", chain -> {
                    Object result = chain.proceed();
                    refreshActiveSubscreenAppInfoCache(classLoader);
                    BatteryRingHost.show((Activity) chain.getThisObject(), this);
                    return result;
                });
        hookMethodIfPresent(
                findDeclaredMethod(launcherClass, "dispatchTouchEvent", MotionEvent.class),
                Constants.SUBSCREEN_LAUNCHER_ACTIVITY_CLASS + "#dispatchTouchEvent",
                chain -> {
                    Object event = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (event instanceof MotionEvent) {
                        Object host = chain.getThisObject();
                        SwipePanelHost.trackLauncherTouch(
                                (MotionEvent) event,
                                host instanceof Context ? (Context) host : null);
                        if (SwipePanelHost.handleLauncherGesture((MotionEvent) event)) {
                            return true;
                        }
                    }
                    return chain.proceed();
                }
        );
    }

    /** 背屏面板挂在宿主 Activity 的窗口上，所以要从点击到的 View 反查 Activity。 */
    @Nullable
    private static Activity resolveHostActivity(@Nullable Object view) {
        if (!(view instanceof View)) return null;
        Context context = ((View) view).getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private void hookSubscreenAppWidgetSecureSetting() {
        hookMethodIfPresent(
                findDeclaredMethod(Settings.Secure.class, "getInt",
                        ContentResolver.class, String.class, int.class),
                "Settings.Secure#getInt(ContentResolver,String,int)",
                chain -> PrefsBridge.shouldEnable18ProFeatures(this)
                        && AppWidgetFeatureGate.isSubscreenAppWidgetSecureKey(
                        chain.getArgs().size() > 1 ? chain.getArgs().get(1) : null)
                        ? 1
                        : chain.proceed()
        );
        hookMethodIfPresent(
                findDeclaredMethod(Settings.Secure.class, "getInt",
                        ContentResolver.class, String.class),
                "Settings.Secure#getInt(ContentResolver,String)",
                chain -> PrefsBridge.shouldEnable18ProFeatures(this)
                        && AppWidgetFeatureGate.isSubscreenAppWidgetSecureKey(
                        chain.getArgs().size() > 1 ? chain.getArgs().get(1) : null)
                        ? 1
                        : chain.proceed()
        );
    }

    private void hookSubscreenLauncherPanelGesture(@NonNull ClassLoader classLoader) {
        Class<?> gestureClass = TargetResolver.resolveClass(
                classLoader, SubScreenTargets.LAUNCHER_GESTURE);
        if (gestureClass == null) {
            log(Log.DEBUG, Constants.LOG_TAG,
                    "App widget gesture hook target missing: "
                            + Constants.SUBSCREEN_LAUNCHER_PANEL_GESTURE_CLASS);
            return;
        }
        Method gestureMethod = findDeclaredMethod(
                gestureClass,
                Constants.SUBSCREEN_LAUNCHER_PANEL_GESTURE_METHOD,
                MotionEvent.class);
        final boolean gestureReturnsBoolean = gestureMethod != null
                && (gestureMethod.getReturnType() == boolean.class
                || gestureMethod.getReturnType() == Boolean.class);
        hookMethodIfPresent(
                gestureMethod,
                Constants.SUBSCREEN_LAUNCHER_PANEL_GESTURE_CLASS + "#"
                        + Constants.SUBSCREEN_LAUNCHER_PANEL_GESTURE_METHOD,
                chain -> {
                    if (!PrefsBridge.shouldEnable18ProFeatures(this)) return chain.proceed();
                    Object event = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (event instanceof MotionEvent
                            && SwipePanelHost.handleLauncherGesture((MotionEvent) event)) {
                        return gestureReturnsBoolean ? true : null;
                    }
                    setFieldValue(
                            chain.getThisObject(),
                            Constants.SUBSCREEN_LAUNCHER_PANEL_WIDGET_ENABLED_FIELD,
                            true);
                    return chain.proceed();
                }
        );
    }

    /**
     * 背屏长按选择壁纸后，宿主只把所选下标保存到自己的 user_pref.json。
     * 系统设置页不读这个下标，而是直接预览 Secure Settings 中 theme_rear_widget
     * 的第一项，因此两边会长期显示不同壁纸。
     */
    private void installRearScreenSelectionSyncHook(@NonNull ClassLoader classLoader) {
        Class<?> mainPanelClass = findClass(Constants.HOOK_CLASS_MAIN_PANEL, classLoader);
        if (mainPanelClass == null) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Rear selection sync target missing: " + Constants.HOOK_CLASS_MAIN_PANEL);
            return;
        }

        hookMethodIfPresent(
                findFirstDeclaredMethod(
                        mainPanelClass,
                        new Class[]{boolean.class},
                        Constants.HOOK_METHOD_REQUEST_EXIT_EDIT_18PRO,
                        Constants.HOOK_METHOD_REQUEST_EXIT_EDIT),
                Constants.HOOK_CLASS_MAIN_PANEL + "#["
                        + Constants.HOOK_METHOD_REQUEST_EXIT_EDIT_18PRO + ", "
                        + Constants.HOOK_METHOD_REQUEST_EXIT_EDIT + "]",
                chain -> {
                    View panel = chain.getThisObject() instanceof View
                            ? (View) chain.getThisObject()
                            : null;
                    Object cancelValue = chain.getArgs().isEmpty()
                            ? null
                            : chain.getArgs().get(0);
                    boolean cancel = Boolean.TRUE.equals(cancelValue);

                    if (panel == null || cancel || !PrefsBridge.shouldFixRearScreenApply(this)) {
                        if (panel != null) pendingRearSelections.remove(panel);
                        return chain.proceed();
                    }

                    PendingRearSelection pending = capturePendingRearSelection(panel);
                    if (pending == null) {
                        pendingRearSelections.remove(panel);
                    } else {
                        pendingRearSelections.put(panel, pending);
                        log(Log.DEBUG, Constants.LOG_TAG,
                                "Rear selection commit requested: id=" + pending.wallpaperId
                                        + ", index=" + pending.selectedIndex);
                    }
                    return chain.proceed();
                }
        );

        hookMethodIfPresent(
                findFirstDeclaredMethod(
                        mainPanelClass,
                        new Class[]{},
                        Constants.HOOK_METHOD_SAVE_USER_SELECTION_18PRO,
                        Constants.HOOK_METHOD_SAVE_USER_SELECTION),
                Constants.HOOK_CLASS_MAIN_PANEL + "#["
                        + Constants.HOOK_METHOD_SAVE_USER_SELECTION_18PRO + ", "
                        + Constants.HOOK_METHOD_SAVE_USER_SELECTION + "]",
                chain -> {
                    Object result = chain.proceed();
                    if (chain.getThisObject() instanceof View panel) {
                        PendingRearSelection pending = pendingRearSelections.get(panel);
                        if (PrefsBridge.shouldFixRearScreenApply(this)
                                || (pending != null && pending.autoRotation)) {
                            consumePendingRearSelection(panel);
                        } else {
                            pendingRearSelections.remove(panel);
                        }
                        AutoWallpaperRotationHost.onWallpaperCommitted(panel);
                    }
                    return result;
                }
        );
    }

    private void installAutoWallpaperRotationHooks(@NonNull ClassLoader classLoader) {
        Class<?> mainPanelClass = findClass(Constants.HOOK_CLASS_MAIN_PANEL, classLoader);
        if (mainPanelClass == null) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Auto wallpaper target missing: " + Constants.HOOK_CLASS_MAIN_PANEL);
            return;
        }

        hookMethodIfPresent(
                findDeclaredMethod(mainPanelClass, Constants.HOOK_METHOD_MAIN_PANEL_RESUME),
                Constants.HOOK_CLASS_MAIN_PANEL + "#"
                        + Constants.HOOK_METHOD_MAIN_PANEL_RESUME,
                chain -> {
                    Object result = chain.proceed();
                    if (chain.getThisObject() instanceof View panel) {
                        AutoWallpaperRotationHost.onPanelResumed(
                                panel,
                                this,
                                this::rotateToNextRearWallpaper);
                    }
                    return result;
                });
        hookMethodIfPresent(
                findDeclaredMethod(mainPanelClass, Constants.HOOK_METHOD_MAIN_PANEL_PAUSE),
                Constants.HOOK_CLASS_MAIN_PANEL + "#"
                        + Constants.HOOK_METHOD_MAIN_PANEL_PAUSE,
                chain -> {
                    if (chain.getThisObject() instanceof View panel) {
                        AutoWallpaperRotationHost.onPanelPaused(panel);
                    }
                    return chain.proceed();
                });
        hookMethodIfPresent(
                findDeclaredMethod(mainPanelClass, Constants.HOOK_METHOD_MAIN_PANEL_DESTROY),
                Constants.HOOK_CLASS_MAIN_PANEL + "#"
                        + Constants.HOOK_METHOD_MAIN_PANEL_DESTROY,
                chain -> {
                    if (chain.getThisObject() instanceof View panel) {
                        AutoWallpaperRotationHost.release(panel);
                    }
                    return chain.proceed();
                });
        hookMethodIfPresent(
                findDeclaredMethod(
                        mainPanelClass,
                        Constants.HOOK_METHOD_MAIN_PANEL_AOD_CHANGED,
                        boolean.class),
                Constants.HOOK_CLASS_MAIN_PANEL + "#"
                        + Constants.HOOK_METHOD_MAIN_PANEL_AOD_CHANGED,
                chain -> {
                    Object result = chain.proceed();
                    if (chain.getThisObject() instanceof View panel) {
                        AutoWallpaperRotationHost.onPanelStateChanged(panel);
                    }
                    return result;
                });
    }

    private boolean rotateToNextRearWallpaper(@NonNull View panel) {
        if (SwipePanelHost.isShowing()) return false;
        Object editing = getFieldValue(panel, Constants.HOOK_FIELD_MAIN_PANEL_EDITING);
        if (Boolean.TRUE.equals(editing)) return false;
        Object state = getFieldValue(panel, Constants.HOOK_FIELD_MAIN_PANEL_STATE);
        if (state instanceof Number && ((Number) state).intValue() != 0) return false;

        Object listValue = getFieldValue(panel, Constants.HOOK_FIELD_WIDGET_LIST_OS4);
        if (!(listValue instanceof List<?>)) {
            listValue = getFieldValue(panel, Constants.HOOK_FIELD_WIDGET_LIST);
        }
        if (!(listValue instanceof List<?> widgets) || widgets.size() < 2) return false;

        Integer currentIndex = resolveWallpaperIndex(panel, false);
        if (currentIndex == null) return false;
        int nextIndex = AutoWallpaperRotationPolicy.nextIndex(currentIndex, widgets.size());
        if (nextIndex < 0) return false;
        Integer nextId = resolveWallpaperIdAtIndex(panel, nextIndex);
        if (nextId == null) return false;

        PendingRearSelection pending = new PendingRearSelection(
                nextId,
                nextIndex,
                SystemClock.elapsedRealtime(),
                true);
        pendingRearSelections.put(panel, pending);
        try {
            callMethodCompatible(
                    panel,
                    Constants.HOOK_METHOD_AUTO_WALLPAPER_SWITCH,
                    nextIndex,
                    widgets);
            log(Log.INFO, Constants.LOG_TAG,
                    "Auto wallpaper switch requested: id=" + nextId
                            + ", index=" + currentIndex + "->" + nextIndex);
            return true;
        } catch (Throwable error) {
            pendingRearSelections.remove(panel);
            log(Log.WARN, Constants.LOG_TAG, "Auto wallpaper switch failed", error);
            return false;
        }
    }

    @Nullable
    private PendingRearSelection capturePendingRearSelection(@NonNull View panel) {
        Integer committedId = resolveWallpaperId(panel, false);
        Integer previewId = resolveWallpaperId(panel, true);
        Integer previewIndex = resolveWallpaperIndex(panel, true);
        if (committedId == null || previewId == null || previewIndex == null
                || committedId.intValue() == previewId.intValue()) {
            return null;
        }
        return new PendingRearSelection(
                previewId,
                previewIndex,
                SystemClock.elapsedRealtime(),
                false);
    }

    private void consumePendingRearSelection(@NonNull View panel) {
        PendingRearSelection pending = pendingRearSelections.get(panel);
        if (pending == null) return;

        long age = SystemClock.elapsedRealtime() - pending.requestedAtElapsed;
        if (age < 0L || age > REAR_SELECTION_PENDING_TIMEOUT_MS) {
            pendingRearSelections.remove(panel);
            log(Log.WARN, Constants.LOG_TAG,
                    "Ignored expired rear selection commit: id=" + pending.wallpaperId
                            + ", ageMs=" + age);
            return;
        }

        Integer committedId = resolveWallpaperId(panel, false);
        if (committedId == null || committedId.intValue() != pending.wallpaperId) {
            pendingRearSelections.remove(panel);
            log(Log.WARN, Constants.LOG_TAG,
                    "Ignored mismatched rear selection commit: pendingId=" + pending.wallpaperId
                            + ", committedId=" + committedId);
            return;
        }

        pendingRearSelections.remove(panel);
        syncSelectedWallpaperToSettings(panel, pending.wallpaperId, pending.selectedIndex);
    }

    @Nullable
    private Integer resolveWallpaperId(@NonNull View panel, boolean preview) {
        Object os4ListValue = getFieldValue(panel, Constants.HOOK_FIELD_WIDGET_LIST_OS4);
        boolean os4Layout = os4ListValue instanceof List<?>;
        Object listValue = os4Layout
                ? os4ListValue
                : getFieldValue(panel, Constants.HOOK_FIELD_WIDGET_LIST);
        if (!(listValue instanceof List<?> widgets)) return null;

        Integer index = resolveWallpaperIndex(panel, preview, os4Layout);
        if (index == null || index < 0 || index >= widgets.size()) return null;
        return resolveWallpaperIdAtIndex(widgets, index);
    }

    @Nullable
    private Integer resolveWallpaperIdAtIndex(@NonNull View panel, int index) {
        Object listValue = getFieldValue(panel, Constants.HOOK_FIELD_WIDGET_LIST_OS4);
        if (!(listValue instanceof List<?>)) {
            listValue = getFieldValue(panel, Constants.HOOK_FIELD_WIDGET_LIST);
        }
        return listValue instanceof List<?> widgets
                ? resolveWallpaperIdAtIndex(widgets, index)
                : null;
    }

    @Nullable
    private Integer resolveWallpaperIdAtIndex(@NonNull List<?> widgets, int index) {
        if (index < 0 || index >= widgets.size()) return null;
        Object widget = widgets.get(index);
        Object bean = getFieldValue(widget, Constants.HOOK_FIELD_WIDGET_BEAN);
        Object idValue = getFieldValue(bean, Constants.HOOK_FIELD_WIDGET_ID);
        return idValue instanceof Number ? ((Number) idValue).intValue() : null;
    }

    @Nullable
    private Integer resolveWallpaperIndex(@NonNull View panel, boolean preview) {
        boolean os4Layout = getFieldValue(panel, Constants.HOOK_FIELD_WIDGET_LIST_OS4)
                instanceof List<?>;
        return resolveWallpaperIndex(panel, preview, os4Layout);
    }

    @Nullable
    private Integer resolveWallpaperIndex(
            @NonNull View panel,
            boolean preview,
            boolean os4Layout
    ) {
        String fieldName;
        if (preview) {
            fieldName = os4Layout
                    ? Constants.HOOK_FIELD_PREVIEW_INDEX_OS4
                    : Constants.HOOK_FIELD_PREVIEW_INDEX;
        } else {
            fieldName = os4Layout
                    ? Constants.HOOK_FIELD_SELECTED_INDEX_OS4
                    : Constants.HOOK_FIELD_SELECTED_INDEX;
        }
        Object indexValue = getFieldValue(panel, fieldName);
        return indexValue instanceof Number ? ((Number) indexValue).intValue() : null;
    }

    private void syncSelectedWallpaperToSettings(
            @NonNull View panel,
            int selectedId,
            int selectedIndex
    ) {
        try {
            String raw = Settings.Secure.getString(
                    panel.getContext().getContentResolver(),
                    Constants.SECURE_THEME_REAR_WIDGET);
            if (isEmpty(raw)) return;

            JSONObject root = new JSONObject(raw);
            JSONArray data = root.optJSONArray("data");
            if (data == null || data.length() == 0) return;

            JSONObject selected = null;
            JSONArray remaining = new JSONArray();
            boolean needsWrite = false;
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                if (item.optInt("id") == selectedId) {
                    selected = item;
                    if (i != 0 || !item.optBoolean("changed", false)) {
                        needsWrite = true;
                    }
                } else {
                    if (item.optBoolean("changed", false)) needsWrite = true;
                    item.put("changed", false);
                    remaining.put(item);
                }
            }
            if (selected == null) return;
            if (!needsWrite) {
                log(Log.DEBUG, Constants.LOG_TAG,
                        "Rear selection already synchronized: id=" + selectedId
                                + ", index=" + selectedIndex);
                return;
            }

            selected.put("changed", true);
            JSONArray reordered = new JSONArray();
            reordered.put(selected);
            for (int i = 0; i < remaining.length(); i++) {
                reordered.put(remaining.get(i));
            }
            root.put("data", reordered);
            root.put("updateTime", System.currentTimeMillis());

            if (Settings.Secure.putString(
                    panel.getContext().getContentResolver(),
                    Constants.SECURE_THEME_REAR_WIDGET,
                    root.toString())) {
                log(Log.INFO, Constants.LOG_TAG,
                        "Synced rear selection to Settings: id=" + selectedId
                                + ", index=" + selectedIndex);
            } else {
                log(Log.WARN, Constants.LOG_TAG,
                        "Failed to write rear selection to Settings: id=" + selectedId);
            }
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "syncSelectedWallpaperToSettings failed", e);
        }
    }

    private static final class PendingRearSelection {
        final int wallpaperId;
        final int selectedIndex;
        final long requestedAtElapsed;
        final boolean autoRotation;

        PendingRearSelection(
                int wallpaperId,
                int selectedIndex,
                long requestedAtElapsed,
                boolean autoRotation
        ) {
            this.wallpaperId = wallpaperId;
            this.selectedIndex = selectedIndex;
            this.requestedAtElapsed = requestedAtElapsed;
            this.autoRotation = autoRotation;
        }
    }

    private static Intent moduleSettingsIntent() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName(
                Constants.MODULE_PACKAGE,
                Constants.MODULE_PACKAGE + ".ui.MainActivity"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private static Intent themeManagerIntent(@NonNull String className) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName(Constants.THEME_STORE_PACKAGE, className));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private void installWallpaperLimitHook(@NonNull ClassLoader classLoader) {
        Class<?> viewModelClass = findClass(Constants.THEME_REAR_VIEWMODEL_CLASS, classLoader);
        if (viewModelClass == null) {
            log(Log.WARN, Constants.LOG_TAG, "Theme hook target missing: " + Constants.THEME_REAR_VIEWMODEL_CLASS);
            return;
        }
        Method applyCheckMethod = TargetMembers.uniqueMethod(viewModelClass, boolean.class, List.class);
        if (applyCheckMethod == null) {
            applyCheckMethod = findFirstDeclaredMethod(
                    viewModelClass,
                    new Class[]{List.class},
                    Constants.THEME_APPLY_CHECK_METHOD_18PRO,
                    Constants.THEME_APPLY_CHECK_METHOD_OS4,
                    Constants.THEME_APPLY_CHECK_METHOD);
        }
        if (applyCheckMethod == null) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Hook targets missing: " + Constants.THEME_REAR_VIEWMODEL_CLASS
                            + "#[" + Constants.THEME_APPLY_CHECK_METHOD_18PRO
                            + ", " + Constants.THEME_APPLY_CHECK_METHOD_OS4
                            + ", " + Constants.THEME_APPLY_CHECK_METHOD + "]");
            return;
        }
        hookMethodIfPresent(
                applyCheckMethod,
                Constants.THEME_REAR_VIEWMODEL_CLASS + "#" + applyCheckMethod.getName(),
                chain -> {
                    if (PrefsBridge.shouldRemoveWallpaperLimit(this)) {
                        return true;
                    }
                    return chain.proceed();
                }
        );
    }

    private void installThemeDeviceIdentityHooks(@NonNull ClassLoader classLoader) {
        // 只保留 isSupportRearScreen 标志。device / version / model 不在 DeviceUtils 层全局改写：
        // 那会让所有主题商店请求都声称自己是 18 Pro Max，普通背屏资源详情直接 404。
        // 需要伪装的请求由 installThemeNetworkDeviceSpoofHook 按路径与 request_flag 精确注入。
        hookBooleanTrueIfPresent(
                classLoader,
                Constants.THEME_DEVICE_UTILS_CLASS,
                "a");
        hookStringReturnIfPresent(
                classLoader,
                Constants.THEME_DEVICE_UTILS_CLASS,
                "y",
                Constants.THEME_REAR_DEVICE,
                this::shouldSpoofThemeAiPageDeviceIdentity);
        hookStringReturnIfPresent(
                classLoader,
                Constants.THEME_DEVICE_UTILS_CLASS,
                "cdj",
                Constants.THEME_REAR_MODEL,
                this::shouldSpoofThemeAiPageDeviceIdentity);
        hookStringReturnIfPresent(
                classLoader,
                Constants.THEME_DEVICE_UTILS_CLASS,
                "z",
                Constants.THEME_REAR_VERSION,
                this::shouldSpoofThemeAiPageDeviceIdentity);
        hookStringReturnIfPresent(
                classLoader,
                Constants.THEME_ONLINE_SERVICE_CLASS,
                "fnq8",
                Constants.THEME_REAR_VERSION,
                this::shouldSpoofThemeAiPageDeviceIdentity);
    }

    private boolean shouldSpoofThemeAiPageDeviceIdentity() {
        return themeAiAppPageVisible && PrefsBridge.shouldEnable18ProFeatures(this);
    }

    private void installThemeNetworkDeviceSpoofHook(@NonNull ClassLoader classLoader) {
        Class<?> interceptorClass = findClass(Constants.THEME_PARAM_INTERCEPTOR_CLASS, classLoader);
        Class<?> requestClass = findClass(Constants.THEME_NETWORK_REQUEST_CLASS, classLoader);
        if (interceptorClass == null || requestClass == null) {
            log(Log.WARN, Constants.LOG_TAG, "Theme network spoof target missing");
            return;
        }

        hookMethodIfPresent(
                findDeclaredMethod(
                        interceptorClass,
                        Constants.THEME_NETWORK_REWRITE_METHOD,
                        requestClass,
                        java.util.LinkedHashMap.class,
                        String.class),
                Constants.THEME_PARAM_INTERCEPTOR_CLASS + "#"
                        + Constants.THEME_NETWORK_REWRITE_METHOD,
                chain -> {
                    if (!PrefsBridge.shouldEnable18ProFeatures(this)) return chain.proceed();
                    Object request = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    Object params = chain.getArgs().size() > 1 ? chain.getArgs().get(1) : null;
                    boolean diagnosticRequest = shouldLogThemeDiagnosticRequest(request);
                    boolean spoofed = false;
                    if (shouldSpoofThemeAiAppRequest(request) && params instanceof Map<?, ?> rawMap) {
                        @SuppressWarnings("unchecked")
                        Map<String, String> map = (Map<String, String>) rawMap;
                        map.put("device", Constants.THEME_REAR_DEVICE);
                        map.put("model", Constants.THEME_REAR_MODEL);
                        map.put("version", Constants.THEME_REAR_VERSION);
                        map.put("isSupportRearScreen", "true");
                        spoofed = true;
                        log(Log.DEBUG, Constants.LOG_TAG,
                                "Spoofed Theme rear AI request as " + Constants.THEME_REAR_DEVICE);
                    }
                    if (diagnosticRequest) {
                        log(Log.DEBUG, Constants.LOG_TAG,
                                "Theme request diagnostic: spoofed=" + spoofed
                                        + ", " + summarizeThemeNetworkRequest(request, params));
                    }
                    return chain.proceed();
                }
        );
    }

    private boolean shouldLogThemeDiagnosticRequest(@Nullable Object request) {
        if (request == null) return false;
        return ThemeNetworkClassifier.shouldLogDiagnostic(themeRequestPathOrUrl(request));
    }

    @NonNull
    private String summarizeThemeNetworkRequest(@Nullable Object request, @Nullable Object params) {
        String path = asString(callMethodQuietly(request, "cdj"));
        String url = stripQueryAndFragment(String.valueOf(callMethodQuietly(request, "url")));
        String requestFlag = asString(callMethodQuietly(
                request, "s", Constants.THEME_AI_APP_REQUEST_FLAG_HEADER));
        return "path=" + path
                + ", url=" + clipForLog(url, 240)
                + ", request_flag=" + requestFlag
                + ", params=" + summarizeThemeNetworkParams(params);
    }

    @NonNull
    private String summarizeThemeNetworkParams(@Nullable Object params) {
        if (!(params instanceof Map<?, ?> map)) return String.valueOf(params);
        return "{device=" + map.get("device")
                + ", model=" + map.get("model")
                + ", version=" + map.get("version")
                + ", isSupportRearScreen=" + map.get("isSupportRearScreen")
                + ", category=" + map.get("category")
                + ", module=" + map.get("module")
                + ", screen=" + map.get("screen")
                + ", page=" + map.get("page")
                + ", request_flag=" + map.get("request_flag")
                + "}";
    }

    @NonNull
    private static String stripQueryAndFragment(@Nullable String value) {
        if (value == null || "null".equals(value)) return "null";
        int query = value.indexOf('?');
        int fragment = value.indexOf('#');
        int end = value.length();
        if (query >= 0) end = Math.min(end, query);
        if (fragment >= 0) end = Math.min(end, fragment);
        return value.substring(0, end);
    }

    private boolean shouldSpoofThemeAiAppRequest(@Nullable Object request) {
        if (request == null) {
            return false;
        }
        String flag = themeRequestFlag(request);
        return ThemeNetworkClassifier.shouldSpoofDeviceIdentity(themeRequestPathOrUrl(request), flag);
    }

    @Nullable
    private String themeRequestPathOrUrl(@Nullable Object request) {
        String raw = asString(callMethodQuietly(request, "cdj"));
        if (!isEmpty(raw)) return raw;
        raw = String.valueOf(callMethodQuietly(request, "url"));
        return isEmpty(raw) ? null : raw;
    }

    @Nullable
    private String themeRequestFlag(@Nullable Object request) {
        return asString(callMethodQuietly(request, "s", Constants.THEME_AI_APP_REQUEST_FLAG_HEADER));
    }

    private void installThemeLegacyDownloadHook(@NonNull ClassLoader classLoader) {
        Class<?> networkHelperClass = findClass(Constants.THEME_NETWORK_HELPER_CLASS, classLoader);
        Class<?> requestUrlClass = findClass(Constants.THEME_REQUEST_URL_CLASS, classLoader);
        if (networkHelperClass == null || requestUrlClass == null) {
            log(Log.WARN, Constants.LOG_TAG, "Theme legacy download hook target missing");
            return;
        }
        hookMethodIfPresent(
                networkHelperClass,
                "f7l8",
                new Class[]{requestUrlClass},
                Constants.THEME_NETWORK_HELPER_CLASS + "#f7l8",
                chain -> {
                    if (!PrefsBridge.shouldEnable18ProFeatures(this)) return chain.proceed();
                    Object requestUrl = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (!isThemeRearDownloadRequest(requestUrl)) {
                        return chain.proceed();
                    }
                    // 背屏壁纸先按本机真实身份请求；AI 应用卡（非 rearscreen 类目）沿用 18 Pro Max 伪装。
                    boolean realFirst = isThemeRearDiffDownloadRequest(requestUrl)
                            || isThemeRearWallpaperDownloadRequest(requestUrl);
                    applyThemeRearDownloadIdentity(requestUrl, realFirst);
                    log(Log.DEBUG, Constants.LOG_TAG,
                            "Theme rear download request: "
                                    + summarizeThemeDownloadRequest(requestUrl));
                    Object result = chain.proceed();
                    log(Log.DEBUG, Constants.LOG_TAG,
                            "Theme rear download response length=" + themeResponseLength(result)
                                    + ", body=" + clipForLog(asString(result), 600));
                    // 首页商店会同时展示两个设备池的背屏资源：真实身份取不到说明该资源属于
                    // 18 Pro Max 池（如「Xiaomi 18 Pro 系列」），换 madrid 重试一次即可拿到。
                    if (realFirst && isThemeDownloadUrlError(result)) {
                        applyThemeRearDownloadIdentity(requestUrl, false);
                        log(Log.DEBUG, Constants.LOG_TAG,
                                "Theme rear download retry as 18 Pro Max: "
                                        + summarizeThemeDownloadRequest(requestUrl));
                        try {
                            Object retried = chain.proceed();
                            log(Log.DEBUG, Constants.LOG_TAG,
                                    "Theme rear download retry response length="
                                            + themeResponseLength(retried)
                                            + ", body=" + clipForLog(asString(retried), 600));
                            if (!isThemeDownloadUrlError(retried) || !(retried instanceof String)) {
                                result = retried;
                            }
                        } catch (Throwable throwable) {
                            log(Log.WARN, Constants.LOG_TAG,
                                    "Theme rear download retry failed: " + throwable);
                        }
                    }
                    return result;
                }
        );
    }

    private static int themeResponseLength(@Nullable Object result) {
        String body = result instanceof String ? (String) result : null;
        return body == null ? -1 : body.length();
    }

    private static boolean isThemeDownloadUrlError(@Nullable Object result) {
        return result instanceof String body
                && body.contains(Constants.THEME_DOWNLOAD_ERROR_MARKER);
    }

    private boolean isThemeRearDownloadRequest(@Nullable Object requestUrl) {
        Object baseUrl = callMethodQuietly(requestUrl, "getBaseUrl");
        return String.valueOf(baseUrl).contains(Constants.THEME_DOWNLOAD_PATH);
    }

    /**
     * 把设备身份写入背屏资源下载请求。
     *
     * <p>主题商店首页会同时展示两个设备池的背屏资源，而 {@code download/v2} 接口按 {@code device}
     * 分池发放，任选其一都会让另一池取不到：
     * <ul>
     *   <li>本机真实身份（{@code ro.product.device}）：17 系列背屏壁纸、背屏个性化、用户分享等；</li>
     *   <li>18 Pro Max（{@code madrid}）：18 Pro 系列专属背屏、AI 应用卡资源。</li>
     * </ul>
     * 因此调用方先按资源倾向选一个身份发出请求，失败时再由 {@code installThemeLegacyDownloadHook}
     * 换另一个身份重试（见该方法内的回退分支）。
     *
     * @param realIdentity true = 本机真实 device/version；false = 18 Pro Max 伪装
     */
    private void applyThemeRearDownloadIdentity(
            @Nullable Object requestUrl, boolean realIdentity) {
        String baseUrl = asString(callMethodQuietly(requestUrl, "getBaseUrl"));
        if (!isEmpty(baseUrl)) {
            String device = realIdentity ? android.os.Build.DEVICE : Constants.THEME_REAR_DEVICE;
            String version = realIdentity
                    ? getCurrentThemeSystemVersion() : Constants.THEME_REAR_VERSION;
            String rewritten = rewriteUrlParameter(baseUrl, "device", device);
            if (!isEmpty(version)) {
                rewritten = rewriteUrlParameter(rewritten, "version", version);
            }
            if (!rewritten.equals(baseUrl)) {
                callMethodQuietly(requestUrl, "setBaseUrl", rewritten);
            }
        }
        Object existing = callMethodQuietly(
                requestUrl, "getParameter", "isSupportRearScreen");
        if (existing != null && "true".equalsIgnoreCase(String.valueOf(existing))) {
            return;
        }
        callMethodQuietly(requestUrl, "addParameter", "isSupportRearScreen", "true");
    }

    /**
     * 判定是否为背屏壁纸类目下载。仅认 {@code category} 精确等于 {@code rearscreen}，
     * 避免误伤带 rearscreen 前缀的 AI 应用卡类目。
     *
     * <p>注意：{@code category} 只存在于 baseUrl 的 query 里，{@code RequestUrl#getParameter}
     * 读不到（它只反映 addParameter 写入的参数容器），必须直接解析 URL。
     */
    private boolean isThemeRearWallpaperDownloadRequest(@Nullable Object requestUrl) {
        String baseUrl = asString(callMethodQuietly(requestUrl, "getBaseUrl"));
        return Constants.THEME_REAR_WALLPAPER_CATEGORY
                .equalsIgnoreCase(readUrlParameter(baseUrl, "category"));
    }

    /** 从 URL 的 query string 中读取参数值；不存在时返回空串。 */
    @NonNull
    private static String readUrlParameter(@Nullable String url, @NonNull String key) {
        if (isEmpty(url)) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[?&]" + java.util.regex.Pattern.quote(key) + "=([^&#]*)")
                .matcher(url);
        return matcher.find() ? matcher.group(1) : "";
    }

    @NonNull
    private static String getCurrentThemeSystemVersion() {
        String release = android.os.Build.VERSION.RELEASE;
        String incremental = android.os.Build.VERSION.INCREMENTAL;
        if (release == null || release.isEmpty() || incremental == null || incremental.isEmpty()) {
            return "";
        }
        return release + "_" + incremental;
    }

    private boolean isThemeRearDiffDownloadRequest(@Nullable Object requestUrl) {
        Object baseUrl = callMethodQuietly(requestUrl, "getBaseUrl");
        return String.valueOf(baseUrl).contains("/diff" + Constants.THEME_DOWNLOAD_PATH);
    }

    @NonNull
    private static String rewriteUrlParameter(
            @NonNull String url, @NonNull String key, @NonNull String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([?&])" + key + "=[^&#]*")
                .matcher(url);
        if (matcher.find()) {
            return matcher.replaceFirst("$1" + key + "=" + value);
        }
        return url + (url.contains("?") ? "&" : "?") + key + "=" + value;
    }

    /** 诊断用。device/version/category 一律从 baseUrl 读——getParameter 读不到它们。 */
    @NonNull
    private String summarizeThemeDownloadRequest(@Nullable Object requestUrl) {
        String baseUrl = String.valueOf(callMethodQuietly(requestUrl, "getBaseUrl"));
        return "baseUrl=" + baseUrl
                + ", category=" + readUrlParameter(baseUrl, "category")
                + ", urlDevice=" + readUrlParameter(baseUrl, "device")
                + ", urlVersion=" + readUrlParameter(baseUrl, "version")
                + ", diff=" + isThemeRearDiffDownloadRequest(requestUrl)
                + ", isSupportRearScreen="
                + String.valueOf(callMethodQuietly(requestUrl, "getParameter", "isSupportRearScreen"));
    }

    @NonNull
    private static String clipForLog(@Nullable String value, int maxChars) {
        if (value == null) return "null";
        if (value.length() <= maxChars) return value;
        return value.substring(0, maxChars) + "...";
    }

    private void installThemeFaceEnrollmentHook(@NonNull ClassLoader classLoader) {
        // Theme Manager 11.5.3.1: k(Context) is the enrollment-count query only.
        // Keep the credential gate, enrollment UI, content audit and authentication intact.
        Class<?> helper = findClass("com.rearScreen.helper.RearScreenBiometricHelper", classLoader);
        if (helper == null) {
            // 原来是静默返回：类名随主题商店版本漂移时完全查不到痕迹，是诊断盲区。
            log(Log.WARN, Constants.LOG_TAG,
                    "Face enrollment hook target missing:"
                            + " com.rearScreen.helper.RearScreenBiometricHelper");
            return;
        }
        Method method = findDeclaredMethod(helper, "k", Context.class);
        if (method == null || method.getReturnType() != int.class
                || !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Face enrollment hook signature mismatch: k(android.content.Context):int");
            return;
        }
        boolean installed = hookMethodIfPresent(method, "RearScreenBiometricHelper#k", chain -> {
            Object original = chain.proceed();
            if (!(original instanceof Integer count) || count > 0
                    || !PrefsBridge.shouldFixFaceEnrollment(this)) return original;
            if (chain.getArgs().isEmpty() || !(chain.getArgs().get(0) instanceof Context context)) {
                return original;
            }
            int actual = ThemeFaceEnrollment.read(context);
            return actual >= 0 ? actual : original;
        });
        log(installed ? Log.INFO : Log.WARN, Constants.LOG_TAG,
                installed ? "Face enrollment compatibility hook installed"
                        : "Face enrollment compatibility hook not installed");
    }


    private void installRearScreenApplyFixHook(@NonNull ClassLoader classLoader) {
        Class<?> applyResultClass = findClass(Constants.THEME_APPLY_RESULT_CLASS, classLoader);
        if (applyResultClass == null) {
            return;
        }

        hookMethodIfPresent(
                applyResultClass,
                Constants.THEME_APPLY_RESULT_METHOD,
                new Class[]{Object.class},
                Constants.THEME_APPLY_RESULT_CLASS + "#" + Constants.THEME_APPLY_RESULT_METHOD,
                chain -> {
                    boolean fixApply = PrefsBridge.shouldFixRearScreenApply(this);
                    boolean removeLimit = PrefsBridge.shouldRemoveWallpaperLimit(this);
                    Object bean = fixApply || removeLimit
                            ? getFieldValue(chain.getThisObject(), Constants.THEME_APPLY_BEAN_FIELD)
                            : null;
                    ThemeMagicRepairTarget repairTarget = ThemeMagicRepairTarget.from(bean);
                    if (fixApply) {
                        grantRearScreenRuntimeAccess();
                    }
                    if (bean != null) {
                        if (fixApply) {
                            promoteReappliedWallpaper(bean, classLoader);
                            fillSnapshotPaths(bean);
                            patchRightsPath(bean, classLoader);
                            patchMtzPath(bean);
                        }
                        patchThemeMagicAssetAccess(bean);
                    }
                    Object result = chain.proceed();
                    if (fixApply) {
                        // 壁纸文件是在本流程内才复制进运行目录的，落库后再兜一次。
                        grantRearScreenRuntimeAccess();
                    }
                    if (repairTarget != null) {
                        scheduleThemeMagicAssetRepair(repairTarget);
                    }
                    return result;
                }
        );
    }

    /**
     * 背屏 AI 陪伴壁纸的重复应用。
     *
     * 详情页入口以 {@code isUpdate = false} 进入应用流程，宿主只在该标志为 true 时按
     * (resId, applyId) 命中已有项并替换，否则按新 applyId 新增一行，「妙享背屏」里于是出现
     * 多条同 resId 的记录。这里在 AI 陪伴场景把 applyId 对齐到已有项并改走替换分支；
     * 普通壁纸保持宿主原行为，仍允许同一 resId 存在多个不同编辑版本。
     */
    private void installAiCompanionReuseApplyHook(@NonNull ClassLoader classLoader) {
        Class<?> companionClass = findClass(
                Constants.THEME_REAR_RES_OPERATION_COMPANION_CLASS, classLoader);
        Class<?> itemClass = findClass(Constants.THEME_REAR_LIST_ITEM_CLASS, classLoader);
        if (companionClass == null || itemClass == null) {
            log(Log.WARN, Constants.LOG_TAG,
                    "AI companion apply reuse target missing: "
                            + Constants.THEME_REAR_RES_OPERATION_COMPANION_CLASS);
            return;
        }

        Method applyEntry = findAiCompanionApplyEntry(companionClass, itemClass);
        if (applyEntry == null) {
            log(Log.WARN, Constants.LOG_TAG, "AI companion apply entry not found");
            return;
        }

        hookMethodIfPresent(
                applyEntry,
                Constants.THEME_REAR_RES_OPERATION_COMPANION_CLASS + "#" + applyEntry.getName(),
                chain -> {
                    if (!PrefsBridge.shouldFixRearScreenApply(this)) {
                        return chain.proceed();
                    }
                    List<Object> args = chain.getArgs();
                    if (args.size() < 2) {
                        return chain.proceed();
                    }
                    Object isUpdate = args.get(0);
                    Object bean = args.get(1);
                    if (!(isUpdate instanceof Boolean) || Boolean.TRUE.equals(isUpdate)) {
                        return chain.proceed();
                    }
                    if (bean == null || !isAiCompanionItem(bean)) {
                        return chain.proceed();
                    }

                    String currentApplyId = asString(callMethodQuietly(bean, "getApplyId"));
                    String reusableApplyId = findReusedAiCompanionApplyId(bean, classLoader);
                    if (reusableApplyId == null || reusableApplyId.equals(currentApplyId)) {
                        return chain.proceed();
                    }
                    if (!alignAiCompanionApplyId(bean, currentApplyId, reusableApplyId)) {
                        return chain.proceed();
                    }

                    List<Object> rewritten = new ArrayList<>(args);
                    rewritten.set(0, Boolean.TRUE);
                    log(Log.INFO, Constants.LOG_TAG,
                            "AI companion apply reused existing id: resId="
                                    + asString(callMethodQuietly(bean, "getResId"))
                                    + ", " + currentApplyId + " -> " + reusableApplyId);
                    return chain.proceed(rewritten.toArray());
                }
        );
    }

    @Nullable
    private static Method findAiCompanionApplyEntry(
            @NonNull Class<?> companionClass, @NonNull Class<?> itemClass) {
        Method signatureMatch = null;
        for (Method method : companionClass.getDeclaredMethods()) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length < 3
                    || parameterTypes[0] != boolean.class
                    || parameterTypes[1] != itemClass) {
                continue;
            }
            if (Constants.THEME_REAR_APPLY_ENTRY_METHOD.equals(method.getName())) {
                return method;
            }
            if (signatureMatch == null) {
                signatureMatch = method;
            }
        }
        return signatureMatch;
    }

    private boolean isAiCompanionItem(@NonNull Object bean) {
        if (Boolean.TRUE.equals(callMethodQuietly(bean, "isNFC"))) {
            return false;
        }
        String resType = asString(callMethodQuietly(bean, "getResType"));
        String resSubType = asString(callMethodQuietly(bean, "getResSubType"));
        return Constants.THEME_REAR_AI_COMPANION_RES_TYPE.equals(resType)
                || Constants.THEME_REAR_AI_COMPANION_RES_TYPE.equals(resSubType)
                || Constants.THEME_REAR_AI_COMPANION_SUB_TYPE.equals(resSubType);
    }

    /**
     * 取该 resId 已应用过的最近一条 applyId。宿主落库主键是 (resId, applyId)，
     * 对齐后同 resId 不会再多出一行。
     */
    @Nullable
    private String findReusedAiCompanionApplyId(
            @NonNull Object bean, @NonNull ClassLoader classLoader) {
        try {
            String resId = asString(callMethod(bean, "getResId"));
            if (isEmpty(resId)) {
                return null;
            }
            Class<?> managerClass = findClass(
                    Constants.THEME_REAR_DATA_MANAGER_CLASS, classLoader);
            if (managerClass == null) {
                return null;
            }
            Field companionField = findStaticCompanionField(
                    managerClass, Constants.THEME_REAR_DATA_MANAGER_COMPANION_FIELD);
            if (companionField == null) {
                return null;
            }
            companionField.setAccessible(true);
            Object companion = companionField.get(null);
            if (companion == null) {
                return null;
            }
            Object manager = callMethod(
                    companion, Constants.THEME_REAR_DATA_MANAGER_GET_INSTANCE_METHOD);
            List<?> items = callRearListGetter(manager);
            if (items == null) {
                return null;
            }

            String currentApplyId = asString(callMethodQuietly(bean, "getApplyId"));
            String latestApplyId = null;
            long latestValue = Long.MIN_VALUE;
            for (Object item : items) {
                if (item == null) {
                    continue;
                }
                if (!resId.equals(asString(callMethodQuietly(item, "getResId")))) {
                    continue;
                }
                String applyId = asString(callMethodQuietly(item, "getApplyId"));
                if (isEmpty(applyId) || applyId.equals(currentApplyId)) {
                    continue;
                }
                long value = parseApplyIdValue(applyId);
                if (value > latestValue) {
                    latestValue = value;
                    latestApplyId = applyId;
                }
            }
            return latestApplyId;
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "Find AI companion applyId failed", e);
            return null;
        }
    }

    /**
     * 把 bean 上的 applyId 换成已有值。运行时路径由 applyId 实时拼出，但 mamlEditConfigPath
     * 一类的字段是详情页带过来的绝对路径，需要同步改写，否则仍会落在新 applyId 的目录上。
     */
    private boolean alignAiCompanionApplyId(
            @NonNull Object bean, @NonNull String fromApplyId, @NonNull String toApplyId) {
        try {
            if (!isEmpty(fromApplyId)) {
                rewriteApplyIdPath(bean, "getMamlEditConfigPath", "setMamlEditConfigPath",
                        fromApplyId, toApplyId);
                rewriteApplyIdPath(bean, "getSnapshotPreviewPath", "setSnapshotPreviewPath",
                        fromApplyId, toApplyId);
                rewriteApplyIdPath(bean, "getResSnapshotPath", "setResSnapshotPath",
                        fromApplyId, toApplyId);
                rewriteApplyIdPath(bean, "getMetaSnapshotPath", "setMetaSnapshotPath",
                        fromApplyId, toApplyId);
            }
            callMethod(bean, "setApplyId", toApplyId);
            return true;
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "Align AI companion applyId failed", e);
            return false;
        }
    }

    private void rewriteApplyIdPath(
            @NonNull Object bean,
            @NonNull String getter,
            @NonNull String setter,
            @NonNull String fromApplyId,
            @NonNull String toApplyId) throws Throwable {
        String value = asString(callMethodQuietly(bean, getter));
        if (isEmpty(value) || !value.contains(fromApplyId)) {
            return;
        }
        callMethod(bean, setter, value.replace(fromApplyId, toApplyId));
    }

    private static long parseApplyIdValue(@NonNull String applyId) {
        try {
            return Long.parseLong(applyId);
        } catch (NumberFormatException e) {
            return applyId.hashCode();
        }
    }

    private void installRearScreenPersonalizationPresetHook(@NonNull ClassLoader classLoader) {
        Class<?> settingHelperCompanionClass = findClass(
                Constants.THEME_SETTING_HELPER_COMPANION_CLASS, classLoader);
        if (settingHelperCompanionClass != null) {
            hookMethodIfPresent(
                    settingHelperCompanionClass,
                    "zy",
                    new Class[]{},
                    Constants.THEME_SETTING_HELPER_COMPANION_CLASS + "#zy",
                    chain -> {
                        Object result = chain.proceed();
                        if (!PrefsBridge.shouldEnable18ProFeatures(this)) return result;
                        String cmf = asString(result);
                        return isEmpty(cmf) ? Constants.THEME_REAR_CMF_FALLBACK : result;
                    }
            );
        } else {
            log(Log.DEBUG, Constants.LOG_TAG,
                    "Rear personalization CMF hook target missing: "
                            + Constants.THEME_SETTING_HELPER_COMPANION_CLASS);
        }

        Class<?> miuiUtilsClass = findClass(Constants.THEME_MIUI_UTILS_CLASS, classLoader);
        if (miuiUtilsClass != null) {
            hookMethodIfPresent(
                    miuiUtilsClass,
                    "zurt",
                    new Class[]{},
                    Constants.THEME_MIUI_UTILS_CLASS + "#zurt",
                    chain -> PrefsBridge.shouldEnable18ProFeatures(this) ? false : chain.proceed()
            );
        } else {
            log(Log.DEBUG, Constants.LOG_TAG,
                    "Rear personalization version hook target missing: "
                            + Constants.THEME_MIUI_UTILS_CLASS);
        }

        Class<?> repositoryClass = findClass(
                Constants.THEME_REAR_PRESET_REPOSITORY_CLASS, classLoader);
        Class<?> continuationClass = findClass("kotlin.coroutines.Continuation", classLoader);
        if (repositoryClass == null || continuationClass == null) {
            log(Log.DEBUG, Constants.LOG_TAG,
                    "Rear personalization preset hook target missing: "
                            + Constants.THEME_REAR_PRESET_REPOSITORY_CLASS);
            return;
        }
        hookMethodIfPresent(
                repositoryClass,
                "k",
                new Class[]{continuationClass},
                Constants.THEME_REAR_PRESET_REPOSITORY_CLASS + "#k",
                chain -> {
                    Object result = chain.proceed();
                    if (!PrefsBridge.shouldEnable18ProFeatures(this)) return result;
                    return ensureRearPersonalizationAiPresets(result, chain.getThisObject());
                }
        );
    }

    private Object ensureRearPersonalizationAiPresets(
            @Nullable Object result,
            @Nullable Object repository
    ) {
        if (!(result instanceof List<?> rawList)) return result;
        if (hasRearPersonalizationAiPresets(rawList)) return result;

        ArrayList<Object> merged = new ArrayList<>(rawList);
        int added = 0;
        added += mergeRearPersonalizationPresetPath(
                merged, repository, Constants.THEME_REAR_TEMPLATE_NORMAL_DATA_DIR);
        added += mergeRearPersonalizationPresetPath(
                merged, repository, Constants.THEME_REAR_TEMPLATE_NORMAL_PRODUCT_DIR);
        added += mergeRearPersonalizationPresetPath(
                merged, repository, Constants.THEME_REAR_TEMPLATE_NORMAL_SYSTEM_DIR);
        if (added > 0) {
            log(Log.INFO, Constants.LOG_TAG,
                    "Rear personalization AI presets ensured: added=" + added);
            return merged;
        }
        log(Log.DEBUG, Constants.LOG_TAG,
                "Rear personalization AI preset source unavailable");
        return result;
    }

    private int mergeRearPersonalizationPresetPath(
            @NonNull ArrayList<Object> target,
            @Nullable Object repository,
            @NonNull String path
    ) {
        if (repository == null) return 0;
        ArrayList<Object> supplemental = new ArrayList<>();
        try {
            Object value = callMethodCompatible(repository, "zurt", supplemental, path);
            List<?> parsed = value instanceof List<?> list ? list : supplemental;
            log(Log.DEBUG, Constants.LOG_TAG,
                    "Rear personalization preset source: path=" + path
                            + ", size=" + parsed.size()
                            + ", current=" + target.size());
            int added = mergeRearPersonalizationPresetCategories(target, parsed);
            if (added == 0) {
                added = mergeLeadingRearPersonalizationPresetCategories(target, parsed);
            }
            return added;
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Rear personalization preset source failed: path=" + path, e);
            return 0;
        }
    }

    private int mergeRearPersonalizationPresetCategories(
            @NonNull ArrayList<Object> target,
            @NonNull List<?> supplemental
    ) {
        int added = 0;
        for (Object category : supplemental) {
            String categoryType = asString(callNoArgMethodQuietly(category, "getCategoryType"));
            if (!isRearPersonalizationAiCategory(categoryType)) continue;
            Object existing = findRearPresetCategory(target, categoryType);
            if (existing == null) {
                target.add(category);
                added++;
                continue;
            }
            Object existingItems = callNoArgMethodQuietly(existing, "getItemList");
            Object supplementalItems = callNoArgMethodQuietly(category, "getItemList");
            if (!(existingItems instanceof List<?> current)
                    || !(supplementalItems instanceof List<?> extras)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<Object> mutableCurrent = (List<Object>) current;
            for (Object item : extras) {
                if (isRearPersonalizationAiItem(item)
                        && !containsRearPresetItem(mutableCurrent, item)) {
                    mutableCurrent.add(item);
                    added++;
                }
            }
        }
        return added;
    }

    private int mergeLeadingRearPersonalizationPresetCategories(
            @NonNull ArrayList<Object> target,
            @NonNull List<?> supplemental
    ) {
        int missing = supplemental.size() - target.size();
        if (missing <= 0) return 0;
        int limit = Math.min(missing, supplemental.size());
        for (int i = limit - 1; i >= 0; i--) {
            target.add(0, supplemental.get(i));
        }
        log(Log.INFO, Constants.LOG_TAG,
                "Rear personalization preset leading categories restored: added=" + limit
                        + ", source=" + supplemental.size()
                        + ", result=" + target.size());
        return limit;
    }

    @Nullable
    private Object findRearPresetCategory(
            @NonNull List<?> categories,
            @Nullable String categoryType
    ) {
        for (Object category : categories) {
            if (categoryType != null
                    && categoryType.equals(asString(
                    callNoArgMethodQuietly(category, "getCategoryType")))) {
                return category;
            }
        }
        return null;
    }

    private boolean hasRearPersonalizationAiPresets(@NonNull List<?> categories) {
        boolean hasAiCompanion = false;
        boolean hasGroupPhoto = false;
        boolean hasPhoneCase = false;
        for (Object category : categories) {
            String categoryType = asString(callNoArgMethodQuietly(category, "getCategoryType"));
            if (Constants.THEME_REAR_AI_COMPANION_RES_TYPE.equals(categoryType)) {
                hasAiCompanion = true;
            }
            Object items = callNoArgMethodQuietly(category, "getItemList");
            if (!(items instanceof List<?> list)) continue;
            for (Object item : list) {
                String resType = asString(callNoArgMethodQuietly(item, "getResType"));
                if (Constants.THEME_REAR_GROUP_PHOTO_RES_TYPE.equals(resType)) {
                    hasGroupPhoto = true;
                } else if (Constants.THEME_REAR_PHONE_CASE_RES_TYPE.equals(resType)) {
                    hasPhoneCase = true;
                }
            }
        }
        return hasAiCompanion && hasGroupPhoto && hasPhoneCase;
    }

    private int countRearPersonalizationPresetItems(@NonNull List<?> categories) {
        int count = 0;
        for (Object category : categories) {
            if (isRearPersonalizationAiCategory(asString(
                    callNoArgMethodQuietly(category, "getCategoryType")))) {
                count++;
            }
            Object items = callNoArgMethodQuietly(category, "getItemList");
            if (!(items instanceof List<?> list)) continue;
            for (Object item : list) {
                if (isRearPersonalizationAiItem(item)) count++;
            }
        }
        return count;
    }

    private boolean isRearPersonalizationAiCategory(@Nullable String categoryType) {
        return Constants.THEME_REAR_AI_COMPANION_RES_TYPE.equals(categoryType)
                || Constants.THEME_REAR_AI_WALLPAPER_CATEGORY_TYPE.equals(categoryType);
    }

    private boolean isRearPersonalizationAiItem(@Nullable Object item) {
        String resType = asString(callNoArgMethodQuietly(item, "getResType"));
        return Constants.THEME_REAR_AI_COMPANION_RES_TYPE.equals(resType)
                || Constants.THEME_REAR_GROUP_PHOTO_RES_TYPE.equals(resType)
                || Constants.THEME_REAR_PHONE_CASE_RES_TYPE.equals(resType)
                || Constants.THEME_REAR_AI_WALLPAPER_CATEGORY_TYPE.equals(resType);
    }

    private boolean containsRearPresetItem(
            @NonNull List<?> items,
            @Nullable Object targetItem
    ) {
        String targetKey = rearPresetItemKey(targetItem);
        for (Object item : items) {
            if (targetKey.equals(rearPresetItemKey(item))) return true;
        }
        return false;
    }

    @NonNull
    private String rearPresetItemKey(@Nullable Object item) {
        String resType = asString(callNoArgMethodQuietly(item, "getResType"));
        String resId = asString(callNoArgMethodQuietly(item, "getResId"));
        String resName = asString(callNoArgMethodQuietly(item, "getResName"));
        return (resType == null ? "" : resType)
                + ":"
                + (!isEmpty(resId) ? resId : (resName == null ? "" : resName));
    }

    private void installThemeSettingsShortcutHook(@NonNull ClassLoader classLoader) {
        Class<?> entryConfigCompanionClass = findClass(
                Constants.THEME_ENTRY_CONFIG_COMPANION_CLASS, classLoader);
        if (entryConfigCompanionClass == null) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Theme settings entry config target missing: "
                            + Constants.THEME_ENTRY_CONFIG_COMPANION_CLASS);
            return;
        }

        hookMethodIfPresent(
                entryConfigCompanionClass,
                "k",
                new Class[]{Context.class},
                Constants.THEME_ENTRY_CONFIG_COMPANION_CLASS + "#k",
                chain -> {
                    Object result = chain.proceed();
                    boolean showModuleShortcut = PrefsBridge.shouldShowThemeSettingsShortcut(this);
                    Intent aiIntent = PrefsBridge.shouldEnable18ProFeatures(this)
                            ? resolveRearScreenAiIntent(classLoader) : null;
                    if (!showModuleShortcut && aiIntent == null) {
                        return result;
                    }
                    Object context = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (context instanceof Context) {
                        return injectThemeSettingsShortcutList(
                                result, (Context) context, classLoader, showModuleShortcut, aiIntent);
                    }
                    return result;
                }
        );

        Class<?> adapterClass = findClass(Constants.THEME_REAR_SETTING_ADAPTER_CLASS, classLoader);
        Class<?> viewHolderClass = findClass(
                "androidx.recyclerview.widget.RecyclerView$ViewHolder", classLoader);
        if (adapterClass == null || viewHolderClass == null) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Theme settings adapter target missing");
            return;
        }
        hookMethodIfPresent(
                findDeclaredMethod(adapterClass, "onBindViewHolder",
                        new Class[]{viewHolderClass, int.class}),
                Constants.THEME_REAR_SETTING_ADAPTER_CLASS + "#onBindViewHolder",
                chain -> {
                    Object result = chain.proceed();
                    List<?> data = getThemeSettingsAdapterData(chain.getThisObject());
                    Object positionValue = chain.getArgs().size() > 1
                            ? chain.getArgs().get(1)
                            : null;
                    int position = positionValue instanceof Number
                            ? ((Number) positionValue).intValue()
                            : -1;
                    if (data != null && position >= 0 && position < data.size()
                            && themeSettingsShortcutControllers.contains(data.get(position))) {
                        Object holder = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                        bindThemeSettingsShortcutRow(holder);
                    } else if (data != null && position >= 0 && position < data.size()
                            && themeAiShortcutControllers.contains(data.get(position))) {
                        Object holder = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                        bindThemeAiShortcutRow(holder);
                    }
                    return result;
                }
        );
    }

    private void installThemeSettingsAiVisibilityHooks(@NonNull ClassLoader classLoader) {
        hookBooleanTrueIfPresent(
                classLoader,
                Constants.THEME_REAR_SCREEN_SETTING_MODULE_COMPANION_CLASS,
                "k");
        hookBooleanTrueIfPresent(
                classLoader,
                Constants.THEME_REAR_SCREEN_AI_APP_CONTROLLER_CLASS,
                "k");
    }

    private void hookBooleanTrueIfPresent(
            @NonNull ClassLoader classLoader,
            @NonNull String className,
            @NonNull String methodName
    ) {
        Class<?> targetClass = findClass(className, classLoader);
        if (targetClass == null) {
            log(Log.DEBUG, Constants.LOG_TAG, "AI entry hook target missing: " + className);
            return;
        }
        hookBooleanTrueIfPresent(targetClass, className, methodName);
    }

    private void hookBooleanTrueIfPresent(
            @NonNull Class<?> targetClass,
            @NonNull String label,
            @NonNull String methodName
    ) {
        hookMethodIfPresent(
                targetClass,
                methodName,
                new Class[]{},
                label + "#" + methodName,
                chain -> PrefsBridge.shouldEnable18ProFeatures(this) ? true : chain.proceed()
        );
    }

    private void hookStringReturnIfPresent(
            @NonNull ClassLoader classLoader,
            @NonNull String className,
            @NonNull String methodName,
            @NonNull String value
    ) {
        hookStringReturnIfPresent(
                classLoader,
                className,
                methodName,
                value,
                () -> PrefsBridge.shouldEnable18ProFeatures(this));
    }

    private void hookStringReturnIfPresent(
            @NonNull ClassLoader classLoader,
            @NonNull String className,
            @NonNull String methodName,
            @NonNull String value,
            @NonNull BooleanSupplier enabled
    ) {
        Class<?> targetClass = findClass(className, classLoader);
        if (targetClass == null) {
            log(Log.DEBUG, Constants.LOG_TAG, "String hook target missing: " + className);
            return;
        }
        hookMethodIfPresent(
                targetClass,
                methodName,
                new Class[]{},
                className + "#" + methodName,
                chain -> enabled.getAsBoolean() ? value : chain.proceed()
        );
    }

    private Object injectThemeSettingsShortcutList(
            @Nullable Object result,
            @NonNull Context context,
            @NonNull ClassLoader classLoader,
            boolean showModuleShortcut,
            @Nullable Intent aiIntent
    ) {
        if (!(result instanceof List<?> rawList)) return result;
        boolean hasModuleShortcut = containsTrackedController(rawList, themeSettingsShortcutControllers);
        boolean hasAiShortcut = containsTrackedController(rawList, themeAiShortcutControllers)
                || containsControllerKey(rawList, "ai_app_entry");
        boolean shouldInsertModule = showModuleShortcut && !hasModuleShortcut;
        boolean shouldInsertAi = aiIntent != null && !hasAiShortcut;
        if (!shouldInsertModule && !shouldInsertAi) return result;

        List<String> keys = new ArrayList<>();
        for (Object item : rawList) {
            Object key = callNoArgMethodQuietly(item, "g");
            if (key instanceof String) keys.add((String) key);
        }
        List<Integer> insertionIndexes = SettingsEntryPlacement.insertionIndexesAfterAnchor(
                keys, (shouldInsertAi ? 1 : 0) + (shouldInsertModule ? 1 : 0));
        if (insertionIndexes.isEmpty()) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Theme settings shortcut anchor missing in rear screen page");
            return result;
        }

        ArrayList<Object> copy = new ArrayList<>(rawList);
        int nextSlot = 0;
        if (shouldInsertAi) {
            Object shortcut = createThemeSettingsShortcutController(
                    context, classLoader, ModuleStrings.get(context, "ai_rear_screen_title", "AI陪伴", "AI Companion"), aiIntent);
            if (shortcut != null) {
                copy.add(Math.min(insertionIndexes.get(nextSlot++), copy.size()), shortcut);
                themeAiShortcutControllers.add(shortcut);
                log(Log.INFO, Constants.LOG_TAG, "AI rear screen entry inserted");
            }
        }
        if (shouldInsertModule) {
            Object shortcut = createThemeSettingsShortcutController(
                    context, classLoader, "MiBackscreen", moduleSettingsIntent());
            if (shortcut != null) {
                int insertionIndex = insertionIndexes.get(nextSlot);
                copy.add(Math.min(insertionIndex, copy.size()), shortcut);
                themeSettingsShortcutControllers.add(shortcut);
                log(Log.INFO, Constants.LOG_TAG,
                        "MiBackscreen theme settings entry inserted after "
                                + keys.get(insertionIndexes.get(0) - 1));
            }
        }
        return copy;
    }

    private boolean containsTrackedController(
            @NonNull List<?> list,
            @NonNull Set<Object> trackedControllers
    ) {
        for (Object item : list) {
            if (trackedControllers.contains(item)) return true;
        }
        return false;
    }

    private boolean containsControllerKey(@NonNull List<?> list, @NonNull String expectedKey) {
        for (Object item : list) {
            Object key = callNoArgMethodQuietly(item, "g");
            if (expectedKey.equals(key)) return true;
        }
        return false;
    }

    @Nullable
    private Object createThemeSettingsShortcutController(
            @NonNull Context context,
            @NonNull ClassLoader classLoader,
            @NonNull String title,
            @NonNull Intent intent
    ) {
        try {
            Class<?> controllerClass = findClass(
                    Constants.THEME_USER_GUIDE_CONTROLLER_CLASS, classLoader);
            if (controllerClass == null) return null;

            Constructor<?> constructor = controllerClass.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            Object controller = constructor.newInstance(context);
            boolean fieldsReady = setFirstFieldValue(
                    controller,
                    new String[]{
                            Constants.THEME_BASE_CONTROLLER_TITLE_FIELD_18PRO,
                            Constants.THEME_BASE_CONTROLLER_TITLE_FIELD
                    },
                    title)
                    && setFirstFieldValue(
                    controller,
                    new String[]{
                            Constants.THEME_USER_GUIDE_INTENT_FIELD_18PRO,
                            Constants.THEME_USER_GUIDE_INTENT_FIELD
                    },
                    intent);
            if (!fieldsReady) {
                log(Log.WARN, Constants.LOG_TAG,
                        "Theme settings shortcut fields missing: "
                                + Constants.THEME_USER_GUIDE_CONTROLLER_CLASS);
                return null;
            }
            return controller;
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG,
                    "createThemeSettingsShortcutController failed", e);
            return null;
        }
    }

    @Nullable
    private Intent resolveRearScreenAiIntent(@NonNull ClassLoader classLoader) {
        Class<?> targetClass = findFirstClass(
                classLoader,
                Constants.THEME_REAR_SCREEN_AI_APP_ACTIVITY,
                Constants.THEME_AI_REAR_SCREEN_LIST_ACTIVITY);
        return targetClass == null ? null : themeManagerIntent(targetClass.getName());
    }

    @Nullable
    private List<?> getThemeSettingsAdapterData(@Nullable Object adapter) {
        Object data = getFieldValue(adapter, Constants.THEME_REAR_SETTING_ADAPTER_DATA_FIELD_18PRO);
        if (!isThemeSettingsControllerList(data)) {
            data = getFieldValue(adapter, Constants.THEME_REAR_SETTING_ADAPTER_DATA_FIELD);
        }
        if (isThemeSettingsControllerList(data)) return (List<?>) data;

        if (adapter == null) return null;
        Class<?> owner = adapter.getClass();
        while (owner != null) {
            for (Field field : owner.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(adapter);
                    if (isThemeSettingsControllerList(value)) {
                        return (List<?>) value;
                    }
                } catch (Throwable ignored) {
                    // Try the next field.
                }
            }
            owner = owner.getSuperclass();
        }
        return null;
    }

    private boolean isThemeSettingsControllerList(@Nullable Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) return false;
        boolean hasKnownRearScreenEntry = false;
        for (Object item : list) {
            if (themeSettingsShortcutControllers.contains(item)) return true;
            Object key = callNoArgMethodQuietly(item, "g");
            if ("user_guide".equals(key) || "serve_assistant".equals(key)) {
                hasKnownRearScreenEntry = true;
            }
        }
        return hasKnownRearScreenEntry;
    }

    @SuppressLint("DiscouragedApi")
    private void bindThemeSettingsShortcutRow(@Nullable Object holder) {
        Object itemViewValue = getFieldValue(holder, "itemView");
        if (!(itemViewValue instanceof View row)) return;
        Context context = row.getContext();
        int titleId = context.getResources().getIdentifier(
                "title", "id", Constants.THEME_STORE_PACKAGE);
        int iconId = context.getResources().getIdentifier(
                "icon", "id", Constants.THEME_STORE_PACKAGE);
        if (titleId != 0) {
            View titleView = row.findViewById(titleId);
            if (titleView instanceof TextView) {
                ((TextView) titleView).setText("MiBackscreen");
            }
        }
        if (iconId != 0) {
            View iconView = row.findViewById(iconId);
            if (iconView instanceof ImageView) {
                try {
                    Drawable icon = context.getPackageManager()
                            .getApplicationIcon(Constants.MODULE_PACKAGE);
                    ((ImageView) iconView).setImageDrawable(icon);
                } catch (Throwable ignored) {
                    // Title and click behavior are still useful if icon lookup fails.
                }
            }
        }
        row.setOnClickListener(v -> {
            try {
                v.getContext().startActivity(moduleSettingsIntent());
            } catch (Throwable e) {
                log(Log.WARN, Constants.LOG_TAG,
                        "MiBackscreen theme settings entry launch failed", e);
                ModuleStrings.showOpenFailure(v.getContext());
            }
        });
    }

    @SuppressLint("DiscouragedApi")
    private void bindThemeAiShortcutRow(@Nullable Object holder) {
        Object itemViewValue = getFieldValue(holder, "itemView");
        if (!(itemViewValue instanceof View row)) return;
        Context context = row.getContext();
        int titleId = context.getResources().getIdentifier(
                "title", "id", Constants.THEME_STORE_PACKAGE);
        if (titleId != 0) {
            View titleView = row.findViewById(titleId);
            if (titleView instanceof TextView) {
                ((TextView) titleView).setText(ModuleStrings.get(context, "ai_rear_screen_title", "AI陪伴", "AI Companion"));
            }
        }
        row.setOnClickListener(v -> {
            try {
                Intent intent = resolveRearScreenAiIntent(v.getContext().getClassLoader());
                if (intent != null) {
                    v.getContext().startActivity(intent);
                } else {
                    ModuleStrings.showOpenFailure(v.getContext());
                }
            } catch (Throwable e) {
                log(Log.WARN, Constants.LOG_TAG,
                        "AI rear screen entry launch failed", e);
                ModuleStrings.showOpenFailure(v.getContext());
            }
        });
    }

    /**
     * 设置页的顶部预览来自主题商店数据库（按 position 降序），并不直接采用
     * theme_rear_widget 的 changed 标记。背屏中心完成切换后已经把当前 widget id
     * 同步到 Secure Settings；设置页冷启动前再据此提升数据库中的对应项。
     */
    private void installThemeSettingsSelectionSyncHook(@NonNull ClassLoader classLoader) {
        Class<?> activityClass = findClass(Constants.THEME_REAR_SETTING_ACTIVITY, classLoader);
        if (activityClass == null) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Theme settings sync target missing: " + Constants.THEME_REAR_SETTING_ACTIVITY);
            return;
        }

        hookMethodIfPresent(
                activityClass,
                "onCreate",
                new Class[]{Bundle.class},
                Constants.THEME_REAR_SETTING_ACTIVITY + "#onCreate",
                chain -> {
                    syncAiGeneratedAppCardsToSubscreenIndex();
                    if (PrefsBridge.shouldFixRearScreenApply(this)) {
                        grantRearScreenRuntimeAccess();
                        repairAiMateSnapshots();
                        if (chain.getThisObject() instanceof Activity activity) {
                            runThemeDatabaseSelectionSync(activity, classLoader);
                        }
                    }
                    return chain.proceed();
                }
        );

        hookMethodIfPresent(
                activityClass,
                "onResume",
                new Class[]{},
                Constants.THEME_REAR_SETTING_ACTIVITY + "#onResume",
                chain -> {
                    syncAiGeneratedAppCardsToSubscreenIndex();
                    if (PrefsBridge.shouldFixRearScreenApply(this)) {
                        grantRearScreenRuntimeAccess();
                        repairAiMateSnapshots();
                        if (chain.getThisObject() instanceof Activity activity) {
                            runThemeDatabaseSelectionSync(activity, classLoader);
                        }
                    }
                    return chain.proceed();
                }
        );
    }

    /**
     * AI 应用卡链路的「18 Pro Max 设备身份伪装」开关。
     * <p>
     * 这个伪装是**页面级**的：只要用户还在 AI 应用卡链路里，所有经 DeviceUtils 取设备名的请求
     * 都要声称自己是 18 Pro Max（服务端据此发 madrid 设备池）；离开后必须立刻恢复真实身份，
     * 否则主题商店的背屏壁纸列表与详情也会跟着声称自己是 18 Pro Max，服务端改发 madrid 池——
     * 表现就是「背屏创意设计大赛」点进去无法显示、刷新后整个列表换成 18 系列。
     * <p>
     * 这里踩过三个坑，都别再犯：
     * <ol>
     *   <li><b>复位钩子必须沿继承链找。</b>宿主只在真正覆写时才声明生命周期方法：
     *       {@code RearScreenAiAppListActivity} 声明 onCreate/onResume/onStop（无 onPause/onDestroy），
     *       {@code RearScreenAiAppDetailActivity} 只声明了 onCreate——它连 onStop 都没有，
     *       退出靠父类 {@code AbstractBaseActivity}。早期只在本类上找 onPause/onDestroy，
     *       找不到就静默跳过，于是标志位一旦置 true 就再也回不来。</li>
     *   <li><b>不能用单个 boolean。</b>列表页打开详情页时，列表页的 onStop 会晚于详情页的
     *       onResume，简单开关会被交错顺序反转。改成「活跃 AI 页面实例集合」，集合清空才算离开。</li>
     *   <li><b>进入钩子只能挂本类声明的。</b>退出钩子挂父类是安全的（不在集合里的实例删了等于没删），
     *       但若把父类的 onResume 拿来当进入钩子，主题商店每个 Activity 都会被塞进集合，恒定常开。</li>
     * </ol>
     */
    private void installThemeAiAppIndexSyncHooks(@NonNull ClassLoader classLoader) {
        boolean anyTarget = false;
        for (String candidate : Constants.THEME_AI_APP_FLOW_ACTIVITIES) {
            Class<?> activityClass = findClass(candidate, classLoader);
            if (activityClass == null) continue;
            anyTarget = true;
            installThemeAiAppLifecycleHooks(activityClass);
        }
        if (!anyTarget) {
            log(Log.DEBUG, Constants.LOG_TAG, "Theme AI app index sync target missing");
        }
    }

    private void installThemeAiAppLifecycleHooks(@NonNull Class<?> activityClass) {
        Method exit = findLifecycleHandlerInHierarchy(
                activityClass, Constants.THEME_AI_APP_EXIT_METHODS);
        if (exit == null) {
            log(Log.WARN, Constants.LOG_TAG,
                    "AI app page has no usable exit hook, skip identity spoof on "
                            + activityClass.getName());
            return;
        }
        hookMethodIfPresent(
                exit,
                exit.getDeclaringClass().getName() + "#" + exit.getName()
                        + " -> " + activityClass.getSimpleName(),
                chain -> {
                    markThemeAiAppFlowVisible(chain.getThisObject(), false);
                    return chain.proceed();
                }
        );
        hookMethodIfPresent(
                activityClass,
                "onCreate",
                new Class[]{Bundle.class},
                activityClass.getName() + "#onCreate",
                chain -> {
                    markThemeAiAppFlowVisible(chain.getThisObject(), true);
                    syncAiGeneratedAppCardsToSubscreenIndex();
                    return chain.proceed();
                }
        );
        hookMethodIfPresent(
                activityClass,
                "onResume",
                new Class[]{},
                activityClass.getName() + "#onResume",
                chain -> {
                    markThemeAiAppFlowVisible(chain.getThisObject(), true);
                    syncAiGeneratedAppCardsToSubscreenIndex();
                    return chain.proceed();
                }
        );
    }

    /**
     * 按「方法优先级优先、继承层级其次」找退出点：先沿整条继承链找 onStop，再找 onPause，
     * 最后才退到 onDestroy。这样即便某个页面自己声明了 onDestroy，只要父类有 onStop 也优先用
     * onStop（页面不可见就复位，比等销毁及时）。
     */
    @Nullable
    private static Method findLifecycleHandlerInHierarchy(
            @NonNull Class<?> targetClass,
            @NonNull String[] methodNames
    ) {
        for (String name : methodNames) {
            for (Class<?> current = targetClass;
                    current != null && current != Object.class;
                    current = current.getSuperclass()) {
                Method method = findDeclaredMethod(current, name);
                if (method != null) return method;
            }
        }
        return null;
    }

    private void markThemeAiAppFlowVisible(@Nullable Object activity, boolean entered) {
        if (activity == null) return;
        boolean visible;
        synchronized (themeAiAppFlowActivities) {
            if (entered) {
                themeAiAppFlowActivities.add(activity);
            } else {
                themeAiAppFlowActivities.remove(activity);
            }
            visible = !themeAiAppFlowActivities.isEmpty();
        }
        markThemeAiAppPageVisible(visible);
    }

    private void markThemeAiAppPageVisible(boolean visible) {
        if (themeAiAppPageVisible == visible) return;
        themeAiAppPageVisible = visible;
        log(Log.DEBUG, Constants.LOG_TAG,
                "AI app page identity spoof " + (visible ? "ON" : "OFF"));
    }

    private void runThemeDatabaseSelectionSync(
            @NonNull Activity activity,
            @NonNull ClassLoader classLoader
    ) {
        Integer selectedId = readSelectedRearWidgetId(activity);
        long now = SystemClock.elapsedRealtime();
        if (selectedId == null
                || (selectedId.equals(lastThemeDatabaseSyncedWidgetId)
                && now - lastThemeDatabaseSyncedAtElapsed < THEME_DATABASE_SYNC_DEDUP_WINDOW_MS)) {
            return;
        }

        pendingSelection.set(selectedId);
        startSelectionSync(classLoader);
    }

    private void startSelectionSync(ClassLoader classLoader) {
        if (!selectionSyncRunning.compareAndSet(false, true)) return;
        try {
            BackgroundTasks.execute(() -> {
                try {
                    Integer next;
                    while ((next = pendingSelection.getAndSet(null)) != null) {
                        if (syncThemeDatabaseToRearSelection(classLoader, next)) {
                            lastThemeDatabaseSyncedWidgetId = next;
                            lastThemeDatabaseSyncedAtElapsed = SystemClock.elapsedRealtime();
                        }
                    }
                } finally {
                    selectionSyncRunning.set(false);
                    if (pendingSelection.get() != null) startSelectionSync(classLoader);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException error) {
            selectionSyncRunning.set(false);
            log(Log.WARN, Constants.LOG_TAG, "Selection sync worker busy", error);
        }
    }

    @Nullable
    private Integer readSelectedRearWidgetId(@NonNull Activity activity) {
        try {
            String raw = Settings.Secure.getString(
                    activity.getContentResolver(), Constants.SECURE_THEME_REAR_WIDGET);
            if (isEmpty(raw)) return null;
            JSONArray data = new JSONObject(raw).optJSONArray("data");
            JSONObject selectedWidget = data != null ? data.optJSONObject(0) : null;
            return selectedWidget != null && selectedWidget.has("id")
                    ? selectedWidget.getInt("id")
                    : null;
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "readSelectedRearWidgetId failed", e);
            return null;
        }
    }

    private boolean syncThemeDatabaseToRearSelection(
            @NonNull ClassLoader classLoader,
            int selectedId
    ) {
        try {
            Class<?> managerClass = findClass(Constants.THEME_REAR_DATA_MANAGER_CLASS, classLoader);
            if (managerClass == null) return false;
            Field companionField = findStaticCompanionField(
                    managerClass, Constants.THEME_REAR_DATA_MANAGER_COMPANION_FIELD);
            if (companionField == null) return false;
            companionField.setAccessible(true);
            Object companion = companionField.get(null);
            Object manager = companion != null
                    ? callMethod(companion, Constants.THEME_REAR_DATA_MANAGER_GET_INSTANCE_METHOD)
                    : null;
            if (manager == null) return false;
            List<?> items = callRearListGetter(manager);
            if (items == null) return false;

            Object selectedItem = null;
            int selectedPosition = Integer.MIN_VALUE;
            int maxPosition = Integer.MIN_VALUE;
            for (Object item : items) {
                if (item == null) continue;
                Object positionValue = callMethod(item, "getPosition");
                if (!(positionValue instanceof Number)) continue;
                int position = ((Number) positionValue).intValue();
                maxPosition = Math.max(maxPosition, position);

                String resId = (String) callMethod(item, "getResId");
                String applyId = (String) callMethod(item, "getApplyId");
                int widgetId = (String.valueOf(resId) + String.valueOf(applyId)).hashCode();
                if (widgetId == selectedId) {
                    selectedItem = item;
                    selectedPosition = position;
                }
            }

            if (selectedItem == null) return false;
            if (selectedPosition >= maxPosition) return true;
            if (maxPosition == Integer.MAX_VALUE) return false;
            int promotedPosition = maxPosition + 1;
            callMethod(selectedItem, "setPosition", promotedPosition);
            callMethod(manager, Constants.THEME_REAR_DATA_MANAGER_UPSERT_METHOD, selectedItem);
            log(Log.INFO, Constants.LOG_TAG,
                    "Synced Theme DB to rear selection: id=" + selectedId
                            + ", position=" + selectedPosition + "->" + promotedPosition);
            return true;
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "syncThemeDatabaseToRearSelection failed", e);
            return false;
        }
    }

    private void syncAiGeneratedAppCardsToSubscreenIndex() {
        if (!PrefsBridge.shouldEnable18ProFeatures(this)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastAiAppIndexSyncedAtElapsed < AI_APP_INDEX_SYNC_DEDUP_WINDOW_MS
                || !aiIndexSyncRunning.compareAndSet(false, true)) return;
        try {
            BackgroundTasks.execute(() -> {
                try {
                    syncAiGeneratedAppCardsToSubscreenIndexNow();
                    lastAiAppIndexSyncedAtElapsed = SystemClock.elapsedRealtime();
                } finally {
                    aiIndexSyncRunning.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException error) {
            aiIndexSyncRunning.set(false);
            log(Log.WARN, Constants.LOG_TAG, "AI index sync worker busy", error);
        }
    }

    private void syncAiGeneratedAppCardsToSubscreenIndexNow() {
        if (!PrefsBridge.shouldEnable18ProFeatures(this)) return;
        File runtimeFile = new File(Constants.THEME_AI_APP_RUNTIME_FILE);
        File appInfoFile = new File(Constants.SUBSCREEN_APP_INFO_FILE);
        cleanupDeletedSubscreenAppInfoEntries();
        if (!runtimeFile.isFile()) {
            return;
        }
        try {
            JSONArray runtimeItems = new JSONArray(readUtf8(runtimeFile, MAX_THEME_INDEX_BYTES));
            JSONArray appInfoItems = appInfoFile.isFile()
                    ? new JSONArray(readUtf8(appInfoFile, MAX_THEME_INDEX_BYTES))
                    : new JSONArray();
            int[] deletedLocalRemoved = new int[1];
            appInfoItems = withoutDeletedLocalAppInfoItems(appInfoItems, deletedLocalRemoved);

            LinkedHashSet<String> runtimeIds = new LinkedHashSet<>();
            for (int i = 0; i < runtimeItems.length(); i++) {
                JSONObject aiItem = runtimeItems.optJSONObject(i);
                if (aiItem == null) continue;
                String productId = aiItem.optString("productId", "");
                if (!isEmpty(productId)) {
                    runtimeIds.add(productId);
                }
            }

            JSONArray syncedItems = new JSONArray();
            LinkedHashSet<String> existingIds = new LinkedHashSet<>();
            int removed = deletedLocalRemoved[0];
            for (int i = 0; i < appInfoItems.length(); i++) {
                JSONObject item = appInfoItems.optJSONObject(i);
                if (item == null) continue;
                String resId = item.optString("resId", "");
                if (isSyncedAiAppInfo(item) && !runtimeIds.contains(resId)) {
                    removed++;
                    continue;
                }
                syncedItems.put(item);
                if (!isEmpty(resId)) {
                    existingIds.add(resId);
                }
            }

            int added = 0;
            for (int i = 0; i < runtimeItems.length(); i++) {
                JSONObject aiItem = runtimeItems.optJSONObject(i);
                if (aiItem == null) continue;
                String productId = aiItem.optString("productId", "");
                if (isEmpty(productId) || existingIds.contains(productId)) {
                    continue;
                }
                JSONObject appInfoItem = createSubscreenAiAppInfo(aiItem);
                if (appInfoItem == null) {
                    continue;
                }
                syncedItems.put(appInfoItem);
                existingIds.add(productId);
                added++;
            }

            if (!PrefsBridge.shouldEnable18ProFeatures(this)) return;
            if (added <= 0 && removed <= 0) {
                grantAiAppIndexAccess(runtimeFile, appInfoFile, syncedItems);
                return;
            }

            backupFileOnce(appInfoFile);
            writeJsonArray(appInfoFile, syncedItems);
            grantAiAppIndexAccess(runtimeFile, appInfoFile, syncedItems);
            log(Log.INFO, Constants.LOG_TAG,
                    "Synced AI app cards to SubScreenCenter index: added=" + added
                            + ", removed=" + removed
                            + ", total=" + syncedItems.length());
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "syncAiGeneratedAppCardsToSubscreenIndex failed", e);
        }
    }

    private boolean isSyncedAiAppInfo(@NonNull JSONObject item) {
        String mtzPath = item.optString("mtzPath", "");
        return item.optInt("appCardType", -1) == 2
                && !isEmpty(mtzPath)
                && mtzPath.startsWith(Constants.THEME_MAGIC_AI_APP_DIR + "/");
    }

    private boolean cleanupDeletedSubscreenAppInfoEntries() {
        File appInfoFile = new File(Constants.SUBSCREEN_APP_INFO_FILE);
        if (!appInfoFile.isFile()) return false;
        try {
            JSONArray items = new JSONArray(readUtf8(appInfoFile, MAX_THEME_INDEX_BYTES));
            int[] removed = new int[1];
            JSONArray cleaned = withoutDeletedLocalAppInfoItems(items, removed);
            if (removed[0] <= 0) return false;
            backupFileOnce(appInfoFile);
            writeJsonArray(appInfoFile, cleaned);
            grantThemeResourceAssetAccess(appInfoFile);
            log(Log.INFO, Constants.LOG_TAG,
                    "Cleaned deleted app cards from SubScreenCenter index: removed="
                            + removed[0] + ", total=" + cleaned.length());
            return true;
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG,
                    "cleanupDeletedSubscreenAppInfoEntries failed", e);
            return false;
        }
    }

    private boolean syncOfficialPanelAppCardWithSubscreenIndex() {
        File appInfoFile = new File(Constants.SUBSCREEN_APP_INFO_FILE);
        boolean enabled = PrefsBridge.shouldEnableAppCard(this);
        boolean installRequested = PrefsBridge.shouldInstallAppCard(this);
        JSONObject officialItem = null;
        try {
            JSONArray current = appInfoFile.isFile()
                    ? new JSONArray(readUtf8(appInfoFile, MAX_THEME_INDEX_BYTES))
                    : new JSONArray();
            boolean installMarked = isOfficialPanelCardInstallMarked();
            boolean hasOfficialItem = containsOfficialPanelCard(current);
            if (enabled && !hasOfficialItem && !installRequested) {
                grantThemeResourceAssetAccess(appInfoFile);
                return false;
            }
            if (enabled) {
                if (!PanelAppCard.ensureOfficialResources()) {
                    log(Log.WARN, Constants.LOG_TAG,
                            "Official quick panel card resources unavailable");
                    return false;
                }
                officialItem = PanelAppCard.createOfficialAppInfo();
                if (officialItem == null) return false;
            }
            JSONArray updated = new JSONArray();
            boolean found = false;
            boolean changed = false;
            for (int i = 0; i < current.length(); i++) {
                JSONObject item = current.optJSONObject(i);
                if (item == null) continue;
                if (!PanelAppCard.isModuleCardInfo(item)) {
                    updated.put(item);
                    continue;
                }
                changed = true;
                if (enabled && !found) {
                    updated.put(officialItem);
                    found = true;
                    changed = !jsonObjectsEqual(item, officialItem);
                }
            }
            if (enabled && !found && installRequested) {
                JSONArray withOfficialFirst = new JSONArray();
                withOfficialFirst.put(officialItem);
                for (int i = 0; i < updated.length(); i++) {
                    withOfficialFirst.put(updated.opt(i));
                }
                updated = withOfficialFirst;
                changed = true;
            }
            if (!changed) {
                if (enabled) {
                    if (found && !installMarked) {
                        markOfficialPanelCardInstalled();
                    }
                    if (found && installRequested) {
                        PrefsBridge.clearAppCardInstallRequest(this);
                    }
                    grantOfficialPanelCardAccess();
                } else if (installMarked) {
                    clearOfficialPanelCardInstallMark();
                }
                return false;
            }
            backupFileOnce(appInfoFile);
            writeJsonArray(appInfoFile, updated);
            if (enabled && updatedContainsOfficialPanelCard(updated)) {
                markOfficialPanelCardInstalled();
                if (installRequested) {
                    PrefsBridge.clearAppCardInstallRequest(this);
                }
            } else if (!enabled) {
                clearOfficialPanelCardInstallMark();
            }
            grantOfficialPanelCardAccess();
            log(Log.INFO, Constants.LOG_TAG,
                    "Synced official quick panel app card: enabled=" + enabled
                            + ", total=" + updated.length());
            return true;
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG,
                    "syncOfficialPanelAppCardWithSubscreenIndex failed", e);
            return false;
        }
    }

    private boolean containsOfficialPanelCard(@NonNull JSONArray items) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && PanelAppCard.isModuleCardInfo(item)) return true;
        }
        return false;
    }

    private boolean updatedContainsOfficialPanelCard(@NonNull JSONArray items) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && PanelAppCard.isModuleCardInfo(item)) return true;
        }
        return false;
    }

    private boolean isOfficialPanelCardInstallMarked() {
        return new File(Constants.MODULE_PANEL_CARD_INSTALL_MARK).isFile();
    }

    private void markOfficialPanelCardInstalled() {
        File mark = new File(Constants.MODULE_PANEL_CARD_INSTALL_MARK);
        File parent = mark.getParentFile();
        try {
            if (parent != null && !parent.exists() && !parent.mkdirs()) return;
            try (FileOutputStream out = new FileOutputStream(mark, false)) {
                out.write("1".getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            grantThemeResourceAssetAccess(mark);
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG,
                    "markOfficialPanelCardInstalled failed", e);
        }
    }

    private void clearOfficialPanelCardInstallMark() {
        File mark = new File(Constants.MODULE_PANEL_CARD_INSTALL_MARK);
        if (mark.isFile() && !mark.delete()) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Failed to clear official panel card install mark");
        }
    }

    private boolean jsonObjectsEqual(@Nullable JSONObject a, @Nullable JSONObject b) {
        return a != null && b != null && a.toString().equals(b.toString());
    }

    private void grantOfficialPanelCardAccess() {
        grantThemeResourceAssetAccess(new File(Constants.MODULE_PANEL_CARD_ROOT));
        grantThemeResourceAssetAccess(new File(Constants.MODULE_PANEL_CARD_RESOURCE));
        grantThemeResourceAssetAccess(new File(Constants.MODULE_PANEL_CARD_DESCRIPTION));
        grantThemeResourceAssetAccess(new File(Constants.MODULE_PANEL_CARD_META));
        grantThemeResourceAssetAccess(new File(Constants.MODULE_PANEL_CARD_ICON));
        grantThemeResourceAssetAccess(new File(Constants.MODULE_PANEL_CARD_PREVIEW_LIGHT));
        grantThemeResourceAssetAccess(new File(Constants.MODULE_PANEL_CARD_PREVIEW_DARK));
        grantThemeResourceAssetAccess(new File(Constants.SUBSCREEN_APP_INFO_FILE));
    }

    @NonNull
    private JSONArray withoutDeletedLocalAppInfoItems(
            @NonNull JSONArray items,
            @NonNull int[] removed
    ) {
        JSONArray kept = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            if (isDeletedLocalAppInfo(item)) {
                removed[0]++;
                continue;
            }
            kept.put(item);
        }
        return kept;
    }

    private boolean isDeletedLocalAppInfo(@NonNull JSONObject item) {
        String mtzPath = item.optString("mtzPath", "");
        if (!isLocalAppCardPath(mtzPath)) return false;
        String iconPath = item.optString("appIconPath", "");
        String previewLightPath = item.optString("previewLightPath", "");
        String previewDarkPath = item.optString("previewDarkPath", "");
        return !pathExists(mtzPath)
                || (!isEmpty(iconPath) && !pathExists(iconPath))
                || (!isEmpty(previewLightPath) && !pathExists(previewLightPath))
                || (!isEmpty(previewDarkPath) && !pathExists(previewDarkPath));
    }

    private boolean isLocalAppCardPath(@Nullable String path) {
        return path != null
                && (path.startsWith(Constants.THEME_MAGIC_APP_CARD_DIR + "/")
                || path.startsWith(Constants.THEME_MAGIC_AI_APP_DIR + "/")
                || path.startsWith(Constants.THEME_MAGIC_MIBACKSCREEN_RESOURCE_DIR + "/"));
    }

    private boolean pathExists(@Nullable String path) {
        return !isEmpty(path) && new File(path).exists();
    }

    @Nullable
    private JSONObject createSubscreenAiAppInfo(@NonNull JSONObject aiItem) {
        String productId = aiItem.optString("productId", "");
        String resLocalPath = aiItem.optString("resLocalPath", "");
        if (isEmpty(productId) || isEmpty(resLocalPath)) {
            return null;
        }
        String appName = aiItem.optString("resName", productId);
        String appIconPath = aiItem.optString("appPicPath", "");
        String previewPath = aiItem.optString("previewImagePath", "");
        try {
            JSONObject item = new JSONObject();
            item.put("resId", productId);
            item.put("appName", isEmpty(appName) ? productId : appName);
            item.put("mtzPath", resLocalPath);
            if (!isEmpty(appIconPath)) {
                item.put("appIconPath", appIconPath);
            }
            if (!isEmpty(previewPath)) {
                item.put("previewLightPath", previewPath);
                item.put("previewDarkPath", previewPath);
            }
            item.put("isGame", aiItem.optBoolean("isGame", false));
            item.put("isPreset", false);
            item.put("appCardType", 2);
            return item;
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG,
                    "createSubscreenAiAppInfo failed: productId=" + productId, e);
            return null;
        }
    }

    private void grantAiAppIndexAccess(
            @NonNull File runtimeFile,
            @NonNull File appInfoFile,
            @NonNull JSONArray appInfoItems
    ) {
        grantThemeResourceAssetAccess(runtimeFile);
        grantThemeResourceAssetAccess(appInfoFile);
        File aiRoot = new File(Constants.THEME_MAGIC_AI_APP_DIR);
        grantThemeResourceAssetAccess(aiRoot);
        for (int i = 0; i < appInfoItems.length(); i++) {
            JSONObject item = appInfoItems.optJSONObject(i);
            if (item == null || item.optInt("appCardType", 0) != 2) continue;
            grantThemeResourceAssetAccess(new File(item.optString("mtzPath", "")));
            grantThemeResourceAssetAccess(new File(item.optString("appIconPath", "")));
            grantThemeResourceAssetAccess(new File(item.optString("previewLightPath", "")));
            grantThemeResourceAssetAccess(new File(item.optString("previewDarkPath", "")));
        }
    }

    private void backupFileOnce(@NonNull File file) {
        if (!file.isFile()) {
            return;
        }
        File backup = new File(file.getAbsolutePath() + ".mibackscreen.bak");
        if (backup.exists()) {
            return;
        }
        if (safeCopy(file, backup)) {
            grantThemeResourceAssetAccess(backup);
        }
    }

    private void writeJsonArray(@NonNull File file, @NonNull JSONArray array) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create parent directory: " + parent.getAbsolutePath());
        }
        File tmp = new File(file.getAbsolutePath() + ".mibackscreen.tmp");
        byte[] bytes = array.toString().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(tmp, false)) {
            out.write(bytes);
            out.getFD().sync();
        }
        grantThemeResourceAssetAccess(tmp);
        if (!tmp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Failed to replace file: " + file.getAbsolutePath());
        }
    }

    private void repairAiMateSnapshots() {
        repairAiMateSnapshot(
                Constants.AI_MATE_PEEKO_RES_ID,
                Constants.AI_MATE_PEEKO_PREVIEW_PATH);
        repairAiMateSnapshot(
                Constants.AI_MATE_LUMI_RES_ID,
                Constants.AI_MATE_LUMI_PREVIEW_PATH);
    }

    private void repairAiMateSnapshot(
            @NonNull String resId,
            @NonNull String previewPath
    ) {
        try {
            File preview = new File(previewPath);
            if (!preview.isFile() || preview.length() <= 0L) {
                return;
            }
            File root = new File(Constants.THEME_MAGIC_REAR_SCREEN_DIR);
            File[] runtimeDirs = root.listFiles((dir, name) -> name != null && name.startsWith(resId + "_"));
            if (runtimeDirs == null || runtimeDirs.length == 0) {
                return;
            }
            for (File runtimeDir : runtimeDirs) {
                if (runtimeDir == null || !runtimeDir.isDirectory()) continue;
                File snapshot = new File(runtimeDir, "rearScreenScreenshot");
                if (!shouldRepairAiMateSnapshot(snapshot)) {
                    continue;
                }
                if (snapshot.isFile()) {
                    File backup = new File(runtimeDir, "rearScreenScreenshot.mibackscreen.bak");
                    if (!backup.exists()) {
                        safeCopy(snapshot, backup);
                    }
                }
                File tmp = new File(runtimeDir, "rearScreenScreenshot.mibackscreen.tmp");
                if (safeCopy(preview, tmp) && tmp.renameTo(snapshot)) {
                    grantThemeResourceAssetAccess(snapshot);
                    log(Log.INFO, Constants.LOG_TAG,
                            "AI mate snapshot repaired: resId=" + resId);
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG,
                    "repairAiMateSnapshot failed: resId=" + resId, e);
        }
    }

    private static boolean shouldRepairAiMateSnapshot(@NonNull File snapshot) {
        return !snapshot.isFile()
                || snapshot.length() <= 0L
                || snapshot.length() < Constants.AI_MATE_BLANK_SNAPSHOT_MAX_BYTES;
    }

    /**
     * 主题商店重新应用已经存在于“我的背屏”中的壁纸时，只会更新 changed 标记，
     * 不会把该项的 position 移到列表首位。背屏服务能识别 changed，但系统设置页直接
     * 预览 position 最大的第一项，因此会一直显示之前的壁纸。
     *
     * 在宿主原流程写数据库、runtime.json 和 theme_rear_widget 之前提升当前项，保证
     * 三份状态使用同一顺序。只处理已经存在且当前不在首位的 resId + applyId。
     */
    private void promoteReappliedWallpaper(Object bean, ClassLoader classLoader) {
        try {
            Class<?> managerClass = findClass(Constants.THEME_REAR_DATA_MANAGER_CLASS, classLoader);
            if (managerClass == null) {
                log(Log.WARN, Constants.LOG_TAG,
                        "Theme hook target missing: " + Constants.THEME_REAR_DATA_MANAGER_CLASS);
                return;
            }

            Field companionField = findStaticCompanionField(
                    managerClass, Constants.THEME_REAR_DATA_MANAGER_COMPANION_FIELD);
            if (companionField == null) {
                throw new NoSuchFieldException(
                        Constants.THEME_REAR_DATA_MANAGER_COMPANION_FIELD);
            }
            companionField.setAccessible(true);
            Object companion = companionField.get(null);
            if (companion == null) return;

            Object manager = callMethod(
                    companion, Constants.THEME_REAR_DATA_MANAGER_GET_INSTANCE_METHOD);
            if (manager == null) return;

            List<?> items = callRearListGetter(manager);
            if (items == null) return;

            String targetResId = (String) callMethod(bean, "getResId");
            String targetApplyId = (String) callMethod(bean, "getApplyId");
            int maxPosition = Integer.MIN_VALUE;
            int currentPosition = Integer.MIN_VALUE;
            boolean existingItem = false;

            for (Object item : items) {
                if (item == null) continue;
                Object positionValue = callMethod(item, "getPosition");
                if (!(positionValue instanceof Number)) continue;
                int position = ((Number) positionValue).intValue();
                maxPosition = Math.max(maxPosition, position);

                String resId = (String) callMethod(item, "getResId");
                String applyId = (String) callMethod(item, "getApplyId");
                if (java.util.Objects.equals(targetResId, resId)
                        && java.util.Objects.equals(targetApplyId, applyId)) {
                    existingItem = true;
                    currentPosition = position;
                }
            }

            if (!existingItem || currentPosition >= maxPosition) return;
            if (maxPosition == Integer.MAX_VALUE) {
                log(Log.WARN, Constants.LOG_TAG,
                        "Current rear wallpaper not promoted: position overflow");
                return;
            }

            int promotedPosition = maxPosition + 1;
            callMethod(bean, "setPosition", promotedPosition);
            log(Log.INFO, Constants.LOG_TAG,
                    "Promoted current rear wallpaper: resId=" + targetResId
                            + ", position=" + currentPosition + "->" + promotedPosition);
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "promoteReappliedWallpaper failed", e);
        }
    }

    @Nullable
    private static Field findStaticCompanionField(Class<?> owner, String preferredName) {
        try {
            return owner.getDeclaredField(preferredName);
        } catch (NoSuchFieldException ignored) {
            String companionClassName = owner.getName() + "$Companion";
            for (Field field : owner.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        && companionClassName.equals(field.getType().getName())) {
                    return field;
                }
            }
            return null;
        }
    }

    private void fillSnapshotPaths(Object bean) {
        try {
            String localPath = (String) callMethod(bean, "getResLocalPath");
            String snapshotPath = (String) callMethod(bean, "getResSnapshotPath");
            if (isEmpty(snapshotPath) && !isEmpty(localPath) && new File(localPath).exists()) {
                callMethod(bean, "setResSnapshotPath", localPath);
            }
            String metaSrc = (String) callMethod(bean, "getMetaPath");
            String metaSnap = (String) callMethod(bean, "getMetaSnapshotPath");
            if (isEmpty(metaSnap) && !isEmpty(metaSrc) && new File(metaSrc).exists()) {
                callMethod(bean, "setMetaSnapshotPath", metaSrc);
            }
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "fillSnapshotPaths failed", e);
        }
    }

    private void patchRightsPath(Object bean, ClassLoader classLoader) {
        try {
            String rightPath = (String) callMethod(bean, "getRightPath");
            if (isEmpty(rightPath) || !rightPath.endsWith(".mra")) {
                return;
            }
            String localPath = (String) callMethod(bean, "getResLocalPath");
            if (isEmpty(localPath) || !localPath.contains("/rearscreen/")) {
                return;
            }

            String rightsDir = resolveRightsDir(classLoader);
            String baseName = new File(rightPath).getName();
            if (!baseName.startsWith("rearscreen_")) {
                baseName = "rearscreen_" + baseName;
            }
            String target = rightsDir + baseName;

            File targetFile = new File(target);
            if (targetFile.exists()) {
                grantReadAccess(targetFile);
                callMethod(bean, "setRightPath", target);
                return;
            }

            File sourceFile = new File(rightPath);
            if (!sourceFile.exists()) {
                File fallback = findNewestRightsFile(rightsDir, target);
                if (fallback != null) {
                    safeCopy(fallback, targetFile);
                    grantReadAccess(targetFile);
                    callMethod(bean, "setRightPath", target);
                } else {
                    callMethod(bean, "setRightPath", target);
                }
                return;
            }

            if (!targetFile.exists()) {
                safeCopy(sourceFile, targetFile);
                grantReadAccess(targetFile);
            }
            callMethod(bean, "setRightPath", target);
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "patchRightsPath failed", e);
        }
    }

    private void patchMtzPath(Object bean) {
        try {
            String srcMtz = (String) callMethod(bean, "getResLocalPath");
            if (isEmpty(srcMtz)) {
                return;
            }
            File srcFile = new File(srcMtz);
            if (!srcFile.exists() || srcFile.isDirectory() || !srcMtz.endsWith(".mrc")) {
                return;
            }
            if (!srcMtz.startsWith("/product/") && !srcMtz.startsWith("/system/") && !srcMtz.startsWith("/vendor/")) {
                return;
            }
            String destMtz = (String) callMethod(bean, "getRuntimeDirWithAuth");
            if (isEmpty(destMtz)) {
                return;
            }
            File destFile = new File(destMtz);
            if (!destFile.exists()) {
                safeCopy(srcFile, destFile);
                grantReadAccess(destFile);
            }
            callMethod(bean, "setResLocalPath", destMtz);
            callMethod(bean, "setResSnapshotPath", destMtz);
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "patchMtzPath failed", e);
        }
    }

    private void patchThemeMagicAssetAccess(Object bean) {
        try {
            LinkedHashSet<File> files = new LinkedHashSet<>();
            addThemeResourceFile(files, asString(callNoArgMethodQuietly(bean, "getResLocalPath")));
            addThemeResourceFile(files, asString(callNoArgMethodQuietly(bean, "getResSnapshotPath")));
            String editConfigPath = asString(callNoArgMethodQuietly(bean, "getMamlEditConfigPath"));
            addThemeResourceFile(files, editConfigPath);
            addThemeResourceFile(files, asString(callNoArgMethodQuietly(bean, "getSnapshotPreviewPath")));
            addThemeResourceFile(files, asString(callNoArgMethodQuietly(bean, "getMetaPath")));
            addThemeResourceFile(files, asString(callNoArgMethodQuietly(bean, "getMetaSnapshotPath")));

            if (!isEmpty(editConfigPath)) {
                collectThemeMagicFilesFromEditConfig(new File(editConfigPath), files);
            }

            int existingFiles = 0;
            int missingFiles = 0;
            for (File file : files) {
                if (file.exists()) {
                    existingFiles++;
                } else {
                    missingFiles++;
                }
                grantThemeResourceAssetAccess(file);
            }
            if (!files.isEmpty()) {
                log(Log.INFO, Constants.LOG_TAG,
                        "Patched rear screen theme resources: total=" + files.size()
                                + ", existing=" + existingFiles
                                + ", missing=" + missingFiles);
            }
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "patchThemeMagicAssetAccess failed", e);
        }
    }

    private void scheduleThemeMagicAssetRepair(@NonNull ThemeMagicRepairTarget target) {
        if (isEmpty(target.editConfigPath)) return;
        synchronized (pendingRepairs) {
            if (pendingRepairs.contains(target.editConfigPath)) return;
            if (pendingRepairs.size() >= 16) {
                log(Log.WARN, Constants.LOG_TAG, "Theme repair queue full");
                return;
            }
            pendingRepairs.add(target.editConfigPath);
        }
        scheduleThemeRepairAttempt(target, 0);
    }

    private void scheduleThemeRepairAttempt(ThemeMagicRepairTarget target, int attempt) {
        long[] delays = {0L, 300L, 1000L, 2500L};
        repairExecutor.schedule(() -> {
            boolean enabled = PrefsBridge.shouldFixRearScreenApply(this);
            try {
                if (enabled) {
                    repairThemeMagicAssets(target);
                    grantRearScreenRuntimeAccess();
                }
            } catch (Throwable error) {
                log(Log.WARN, Constants.LOG_TAG, "Theme repair failed", error);
            } finally {
                if (enabled && attempt + 1 < delays.length) {
                    scheduleThemeRepairAttempt(target, attempt + 1);
                } else {
                    synchronized (pendingRepairs) { pendingRepairs.remove(target.editConfigPath); }
                }
            }
        }, delays[attempt], TimeUnit.MILLISECONDS);
    }

    private void repairThemeMagicAssets(@NonNull ThemeMagicRepairTarget target) {
        if (isEmpty(target.editConfigPath)) {
            return;
        }
        File editConfig = new File(target.editConfigPath);
        if (!editConfig.exists() || !editConfig.isFile()) {
            return;
        }
        long length = editConfig.length();
        if (length < 0 || length > MAX_EDIT_CONFIG_BYTES) {
            return;
        }
        try {
            String raw = readUtf8(editConfig).trim();
            if (raw.isEmpty()) {
                return;
            }
            Object root = raw.startsWith("[") ? new JSONArray(raw) : new JSONObject(raw);
            RepairStats stats = new RepairStats();
            boolean changed = rewriteThemeMagicFileReferences(root, target, stats);
            if (changed) {
                byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
                try (FileOutputStream out = new FileOutputStream(editConfig, false)) {
                    out.write(bytes);
                    out.getFD().sync();
                }
                grantThemeResourceAssetAccess(editConfig);
                log(Log.INFO, Constants.LOG_TAG,
                        "Rewrote rear screen theme_magic asset references: copied="
                                + stats.copied + ", missing=" + stats.missing);
            }
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "repairThemeMagicAssets failed", e);
        }
    }

    private boolean rewriteThemeMagicFileReferences(
            @Nullable Object node,
            @NonNull ThemeMagicRepairTarget target,
            @NonNull RepairStats stats
    ) throws Throwable {
        boolean changed = false;
        if (node instanceof JSONObject object) {
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = object.opt(key);
                if (value instanceof String) {
                    String rewritten = rewriteThemeMagicFileReference((String) value, target, stats);
                    if (!((String) value).equals(rewritten)) {
                        object.put(key, rewritten);
                        changed = true;
                    }
                } else if (rewriteThemeMagicFileReferences(value, target, stats)) {
                    changed = true;
                }
            }
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                Object value = array.opt(i);
                if (value instanceof String) {
                    String rewritten = rewriteThemeMagicFileReference((String) value, target, stats);
                    if (!((String) value).equals(rewritten)) {
                        array.put(i, rewritten);
                        changed = true;
                    }
                } else if (rewriteThemeMagicFileReferences(value, target, stats)) {
                    changed = true;
                }
            }
        }
        return changed;
    }

    @NonNull
    private String rewriteThemeMagicFileReference(
            @NonNull String path,
            @NonNull ThemeMagicRepairTarget target,
            @NonNull RepairStats stats
    ) {
        if (!ThemeResourceAccess.isThemeMagicUserPath(path)) {
            return path;
        }
        File source = new File(path);
        if (!source.exists() || !source.isFile()) {
            stats.missing++;
            return path;
        }
        File destination = resolveRuntimeAssetPath(source, target);
        if (!destination.exists() || destination.length() != source.length()) {
            if (!safeCopy(source, destination)) {
                return path;
            }
        }
        grantThemeResourceAssetAccess(destination);
        stats.copied++;
        return destination.getAbsolutePath();
    }

    @NonNull
    private File resolveRuntimeAssetPath(
            @NonNull File source,
            @NonNull ThemeMagicRepairTarget target
    ) {
        String resId = sanitizeFilePart(target.resId, "unknown");
        String applyId = sanitizeFilePart(target.applyId, "current");
        String name = sanitizeFilePart(source.getName(), "asset");
        return new File(
                "/data/system/theme/rearScreen",
                "mibackscreen_" + resId + "_" + applyId + "_" + name);
    }

    private void collectThemeMagicFilesFromEditConfig(@NonNull File editConfig, @NonNull Set<File> files) {
        if (!editConfig.exists() || !editConfig.isFile()) {
            return;
        }
        long length = editConfig.length();
        if (length < 0 || length > MAX_EDIT_CONFIG_BYTES) {
            return;
        }
        try {
            String raw = readUtf8(editConfig).trim();
            if (raw.isEmpty()) {
                return;
            }
            Object root = raw.startsWith("[") ? new JSONArray(raw) : new JSONObject(raw);
            collectThemeMagicFilesFromJson(root, files);
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG,
                    "Failed to parse rear screen editConfig: " + editConfig.getAbsolutePath(), e);
        }
    }

    private void collectThemeMagicFilesFromJson(@Nullable Object node, @NonNull Set<File> files) {
        if (node instanceof JSONObject object) {
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                Object value = object.opt(keys.next());
                if (value instanceof String) {
                    addThemeResourceFile(files, (String) value);
                } else {
                    collectThemeMagicFilesFromJson(value, files);
                }
            }
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                Object value = array.opt(i);
                if (value instanceof String) {
                    addThemeResourceFile(files, (String) value);
                } else {
                    collectThemeMagicFilesFromJson(value, files);
                }
            }
        }
    }

    private void addThemeResourceFile(@NonNull Set<File> files, @Nullable String path) {
        if (isEmpty(path) || !ThemeResourceAccess.isGrantablePath(path)) {
            return;
        }
        files.add(new File(path));
    }

    private void grantThemeResourceAssetAccess(@NonNull File file) {
        grantThemeResourceDirectoryAccess(file.getParentFile());
        if (!file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            grantThemeResourceDirectoryAccess(file);
        } else {
            grantReadAccess(file);
        }
    }

    @SuppressLint("SetWorldReadable")
    private void grantThemeResourceDirectoryAccess(@Nullable File dir) {
        File current = dir;
        while (current != null && ThemeResourceAccess.isGrantablePath(current.getAbsolutePath())) {
            current.setReadable(true, false);
            current.setWritable(true, true);
            current.setExecutable(true, false);
            current = current.getParentFile();
        }
    }

    /**
     * 保证背屏运行目录可被背屏中心读取：目录 other 需 r-x，目录内 rearscreen_*.mrc 需 other r。
     * 背屏中心按普通应用 uid 运行、不是该目录属主；目录一旦停在 700，它就读不到壁纸文件，
     * 会把收到的 widget 全部判为无效并回退系统默认壁纸（表现为"应用成功但界面没变"）。
     * 只在主题商店进程调用——它才是 /data/system/theme 的属主，其它进程改不动。
     */
    private void grantRearScreenRuntimeAccess() {
        try {
            File dir = new File(ThemeResourceAccess.REAR_SCREEN_RUNTIME_DIR);
            if (!dir.isDirectory()) {
                return;
            }
            grantThemeResourceDirectoryAccess(dir);
            File[] children = dir.listFiles();
            int grantedFiles = 0;
            if (children != null) {
                for (File child : children) {
                    if (child == null || !child.isFile()) continue;
                    String name = child.getName();
                    if (!name.startsWith("rearscreen_") || !name.endsWith(".mrc")) continue;
                    grantReadAccess(child);
                    grantedFiles++;
                }
            }
            log(Log.DEBUG, Constants.LOG_TAG,
                    "Rear screen runtime access ensured: dir=" + dir.getName()
                            + ", files=" + grantedFiles);
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "grantRearScreenRuntimeAccess failed", e);
        }
    }

    private String resolveRightsDir(ClassLoader classLoader) {
        String[] fieldNames = {
                Constants.THEME_RIGHTS_DIR_FIELD_OS4,
                Constants.THEME_RIGHTS_DIR_FIELD_PRIMARY,
                Constants.THEME_RIGHTS_DIR_FIELD_FALLBACK
        };
        for (String fieldName : fieldNames) {
            String dir = getStaticStringField(
                    classLoader, Constants.THEME_RESOURCE_CONSTANTS_CLASS, fieldName);
            if (isValidRightsDir(dir)) {
                return dir.endsWith("/") ? dir : dir + "/";
            }
            if (!isEmpty(dir)) {
                log(Log.WARN, Constants.LOG_TAG,
                        "Ignored invalid theme rights dir from " + fieldName + ": " + dir);
            }
        }
        return Constants.THEME_RIGHTS_DIR_DEFAULT;
    }

    private static boolean isValidRightsDir(@Nullable String dir) {
        if (isEmpty(dir) || !dir.startsWith("/")) return false;
        String normalized = dir.endsWith("/") ? dir : dir + "/";
        return normalized.contains("/theme/rights/");
    }

    private File findNewestRightsFile(String dirPath, String excludePath) {
        File dir = new File(dirPath);
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }
        File newest = null;
        long newestTime = Long.MIN_VALUE;
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            String name = f.getName();
            if (!name.startsWith("rearscreen_") || !name.endsWith(".mra")) continue;
            if (excludePath != null && excludePath.equals(f.getAbsolutePath())) continue;
            if (f.lastModified() > newestTime) {
                newest = f;
                newestTime = f.lastModified();
            }
        }
        return newest;
    }

    @SuppressLint("SetWorldReadable")
    private void grantReadAccess(File file) {
        // 仅设为全局可读（供主题服务跨进程读取）；写权限限所有者，且不置可执行位
        file.setReadable(true, false);
        file.setWritable(true, true);
        file.setExecutable(false, false);
    }

    private static String readUtf8(@NonNull File file) throws IOException {
        return readUtf8(file, MAX_EDIT_CONFIG_BYTES);
    }

    private static String readUtf8(@NonNull File file, long maxChars) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                content.append(buffer, 0, count);
                if (content.length() > maxChars) {
                    throw new IOException("JSON exceeds size limit");
                }
            }
        }
        return content.toString();
    }

    private static final class ThemeMagicRepairTarget {
        @Nullable
        final String resId;
        @Nullable
        final String applyId;
        @Nullable
        final String editConfigPath;

        private ThemeMagicRepairTarget(
                @Nullable String resId,
                @Nullable String applyId,
                @Nullable String editConfigPath
        ) {
            this.resId = resId;
            this.applyId = applyId;
            this.editConfigPath = editConfigPath;
        }

        @Nullable
        static ThemeMagicRepairTarget from(@Nullable Object bean) {
            if (bean == null) return null;
            String editConfigPath = asString(callNoArgMethodQuietly(bean, "getMamlEditConfigPath"));
            if (isEmpty(editConfigPath) || !ThemeResourceAccess.isThemeMagicUserPath(editConfigPath)) {
                return null;
            }
            return new ThemeMagicRepairTarget(
                    asString(callNoArgMethodQuietly(bean, "getResId")),
                    asString(callNoArgMethodQuietly(bean, "getApplyId")),
                    editConfigPath);
        }
    }

    private static final class RepairStats {
        int copied;
        int missing;
    }

    @Nullable
    private static String asString(@Nullable Object value) {
        return value instanceof String ? (String) value : null;
    }

    @NonNull
    private static String sanitizeFilePart(@Nullable String value, @NonNull String fallback) {
        if (isEmpty(value)) {
            return fallback;
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isEmpty() ? fallback : sanitized;
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

    @NonNull
    private static String[] resolveForegroundPackages(@Nullable Object powerManagerServiceImpl) {
        LinkedHashSet<String> packages = new LinkedHashSet<>();
        addPackageName(packages, getFieldValue(
                powerManagerServiceImpl,
                Constants.SYSTEM_FOREGROUND_APP_PACKAGE_FIELD));
        Object activityTaskManager = getFieldValue(
                powerManagerServiceImpl,
                Constants.SYSTEM_ACTIVITY_TASK_MANAGER_FIELD);
        // Only the current main-display task: older tasks are not foreground candidates.
        Object tasks = callMethodQuietly(activityTaskManager, "getTasks", 1, false, false, 0);
        if (tasks instanceof List<?> list) {
            for (Object task : list) {
                addComponentPackageName(packages, getFieldValue(
                        task,
                        Constants.SYSTEM_RUNNING_TASK_TOP_ACTIVITY_FIELD));
                addComponentPackageName(packages, getFieldValue(
                        task,
                        Constants.SYSTEM_RUNNING_TASK_BASE_ACTIVITY_FIELD));
                addComponentPackageName(packages, getFieldValue(
                        task,
                        Constants.SYSTEM_RUNNING_TASK_ORIG_ACTIVITY_FIELD));
                addComponentPackageName(packages, getFieldValue(
                        task,
                        Constants.SYSTEM_RUNNING_TASK_REAL_ACTIVITY_FIELD));
            }
        }
        return packages.toArray(new String[0]);
    }

    private static void addComponentPackageName(
            @NonNull LinkedHashSet<String> packages,
            @Nullable Object component
    ) {
        if (component instanceof ComponentName) {
            addPackageName(packages, ((ComponentName) component).getPackageName());
        }
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
        log(Log.DEBUG, Constants.LOG_TAG,
                "Rear screen wake decision: groupId=" + groupId
                        + ", details=" + String.valueOf(details)
                        + ", foreground=" + joinPackageNames(packageNames)
                        + ", skipped=" + skipped);
    }

    private boolean safeCopy(File src, File dst) {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.getFD().sync();
            return true;
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "safeCopy failed", e);
            return false;
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static Object getFieldValue(Object target, String field) {
        if (target == null) return null;
        Class<?> owner = target.getClass();
        while (owner != null) {
            try {
                Field f = owner.getDeclaredField(field);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                owner = owner.getSuperclass();
            } catch (Throwable e) {
                return null;
            }
        }
        return null;
    }

    @Nullable
    private static Object getStaticFieldValueByType(
            @NonNull Class<?> owner,
            @NonNull Class<?> expectedType
    ) {
        Class<?> current = owner;
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                try {
                    if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                            || !expectedType.isAssignableFrom(field.getType())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (expectedType.isInstance(value)) {
                        return value;
                    }
                } catch (Throwable ignored) {
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static boolean setFieldValue(Object target, String field, Object value) {
        if (target == null) return false;
        Class<?> owner = target.getClass();
        while (owner != null) {
            try {
                Field f = owner.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return true;
            } catch (NoSuchFieldException ignored) {
                owner = owner.getSuperclass();
            } catch (Throwable e) {
                return false;
            }
        }
        return false;
    }

    @Nullable
    private static Object getFieldValueByType(
            @Nullable Object target,
            @NonNull Class<?> expectedType,
            @NonNull String... fieldNames
    ) {
        for (String fieldName : fieldNames) {
            Object value = getFieldValue(target, fieldName);
            if (expectedType.isInstance(value)) {
                return value;
            }
        }
        return null;
    }

    /** 混淆字段名逐版本重排，按候选顺序写入第一个真实存在的字段。 */
    private static boolean setFirstFieldValue(
            @Nullable Object target,
            @NonNull String[] fieldNames,
            @NonNull Object value
    ) {
        for (String fieldName : fieldNames) {
            if (setFieldValue(target, fieldName, value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 「我的背屏」列表取值方法逐版本改名，按候选顺序取第一个返回非空列表的方法。
     */
    @Nullable
    private static List<?> callRearListGetter(@Nullable Object manager) {
        if (manager == null) return null;
        String[] methodNames = {
                Constants.THEME_REAR_DATA_MANAGER_GET_LIST_METHOD_18PRO,
                Constants.THEME_REAR_DATA_MANAGER_GET_LIST_METHOD_FALLBACK,
                Constants.THEME_REAR_DATA_MANAGER_GET_LIST_METHOD
        };
        for (String methodName : methodNames) {
            Object value = callMethodQuietly(manager, methodName);
            if (value instanceof List<?> items && !items.isEmpty()) {
                return items;
            }
        }
        return null;
    }

    private static Object callMethod(Object target, String method, Object... args) throws Throwable {
        Class<?>[] paramTypes = new Class[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) {
                paramTypes[i] = Object.class;
                continue;
            }
            Class<?> c = a.getClass();
            if (c == Boolean.class) c = boolean.class;
            else if (c == Integer.class) c = int.class;
            else if (c == Long.class) c = long.class;
            else if (c == Float.class) c = float.class;
            else if (c == Double.class) c = double.class;
            else if (c == Short.class) c = short.class;
            else if (c == Byte.class) c = byte.class;
            else if (c == Character.class) c = char.class;
            paramTypes[i] = c;
        }
        Method m = findMethod(target.getClass(), method, paramTypes);
        if (m == null) throw new NoSuchMethodException(method);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static Object callMethodCompatible(Object target, String method, Object... args)
            throws Throwable {
        try {
            return callMethod(target, method, args);
        } catch (NoSuchMethodException ignored) {
        }
        Method m = findCompatibleMethod(target.getClass(), method, args);
        if (m == null) throw new NoSuchMethodException(method);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    @Nullable
    private static Object callNoArgMethodQuietly(@Nullable Object target, @NonNull String method) {
        if (target == null) return null;
        try {
            return callMethod(target, method);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Object callMethodQuietly(@Nullable Object target, @NonNull String method, Object... args) {
        if (target == null) return null;
        try {
            return callMethod(target, method, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>[] paramTypes) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    @Nullable
    private static Method findCompatibleMethod(
            @Nullable Class<?> clazz,
            @NonNull String name,
            @NonNull Object[] args
    ) {
        while (clazz != null) {
            for (Method method : clazz.getDeclaredMethods()) {
                if (!name.equals(method.getName())) continue;
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length != args.length) continue;
                boolean matches = true;
                for (int i = 0; i < parameterTypes.length; i++) {
                    if (!isReflectionArgumentCompatible(parameterTypes[i], args[i])) {
                        matches = false;
                        break;
                    }
                }
                if (matches) return method;
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private static boolean isReflectionArgumentCompatible(
            @NonNull Class<?> parameterType,
            @Nullable Object arg
    ) {
        if (arg == null) return !parameterType.isPrimitive();
        Class<?> argType = arg.getClass();
        if (parameterType.isPrimitive()) {
            return primitiveWrapperType(parameterType) == argType;
        }
        return parameterType.isAssignableFrom(argType);
    }

    @NonNull
    private static Class<?> primitiveWrapperType(@NonNull Class<?> primitiveType) {
        if (primitiveType == boolean.class) return Boolean.class;
        if (primitiveType == int.class) return Integer.class;
        if (primitiveType == long.class) return Long.class;
        if (primitiveType == float.class) return Float.class;
        if (primitiveType == double.class) return Double.class;
        if (primitiveType == short.class) return Short.class;
        if (primitiveType == byte.class) return Byte.class;
        if (primitiveType == char.class) return Character.class;
        return Void.class;
    }

    private static String getStaticStringField(ClassLoader classLoader, String className, String fieldName) {
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof String) {
                return (String) value;
            }
            return null;
        } catch (Throwable e) {
            return null;
        }
    }

    private void hookLongPressMethod(
            @NonNull Class<?> targetClass,
            @NonNull String methodName,
            @NonNull Class<?>[] parameterTypes,
            @Nullable Object blockedReturn
    ) {
        hookMethodIfPresent(
                targetClass,
                methodName,
                parameterTypes,
                targetClass.getName() + "#" + methodName,
                chain -> {
                    if (PrefsBridge.shouldBlockLongPressEdit(this)) {
                        return blockedReturn;
                    }
                    return chain.proceed();
                }
        );
    }

    private boolean hookMethodIfPresent(
            @NonNull Class<?> targetClass,
            @NonNull String methodName,
            @NonNull Class<?>[] parameterTypes,
            @NonNull String label,
            @NonNull HookCallback callback
    ) {
        return hookMethodIfPresent(findDeclaredMethod(targetClass, methodName, parameterTypes), label, callback);
    }

    private synchronized boolean hookMethodIfPresent(
            @Nullable Method method,
            @NonNull String label,
            @NonNull HookCallback callback
    ) {
        if (method == null) {
            log(Log.WARN, Constants.LOG_TAG, "Hook target missing: " + label);
            return false;
        }
        if (installedMethods.contains(method)) return true;
        try {
            hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(callback::onHook);
            installedMethods.add(method);
            log(Log.DEBUG, Constants.LOG_TAG, "Hook installed: " + label);
            return true;
        } catch (Throwable throwable) {
            log(Log.ERROR, Constants.LOG_TAG, "Hook install failed: " + label, throwable);
            return false;
        }
    }

    private synchronized boolean hookConstructorIfPresent(
            @Nullable Constructor<?> constructor,
            @NonNull String label,
            @NonNull HookCallback callback
    ) {
        if (constructor == null) {
            log(Log.WARN, Constants.LOG_TAG, "Hook target missing: " + label);
            return false;
        }
        if (installedConstructors.contains(constructor)) return true;
        try {
            hook(constructor)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(callback::onHook);
            installedConstructors.add(constructor);
            log(Log.DEBUG, Constants.LOG_TAG, "Hook installed: " + label);
            return true;
        } catch (Throwable throwable) {
            log(Log.ERROR, Constants.LOG_TAG, "Hook install failed: " + label, throwable);
            return false;
        }
    }

    @Nullable
    private static Class<?> findClass(@NonNull String className, @NonNull ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @Nullable
    private static Class<?> findFirstClass(
            @NonNull ClassLoader classLoader,
            @NonNull String... classNames
    ) {
        for (String className : classNames) {
            Class<?> targetClass = findClass(className, classLoader);
            if (targetClass != null) {
                return targetClass;
            }
        }
        return null;
    }

    @Nullable
    private static Method findFirstDeclaredMethod(
            @NonNull Class<?> targetClass,
            @NonNull Class<?>[] parameterTypes,
            @NonNull String... methodNames
    ) {
        for (String methodName : methodNames) {
            Method method = findDeclaredMethod(targetClass, methodName, parameterTypes);
            if (method != null) {
                return method;
            }
        }
        return null;
    }

    @Nullable
    private static Method findDeclaredMethodWithFirstParam(
            @NonNull Class<?> targetClass,
            @NonNull String methodName,
            @NonNull Class<?> firstParameterType
    ) {
        for (Method method : targetClass.getDeclaredMethods()) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length == 0
                    || !parameterTypes[0].equals(firstParameterType)
                    || !method.getName().equals(methodName)) {
                continue;
            }
            method.setAccessible(true);
            return method;
        }
        return null;
    }

    @Nullable
    private static Method findDeclaredMethod(
            @NonNull Class<?> targetClass,
            @NonNull String methodName,
            @NonNull Class<?>... parameterTypes
    ) {
        try {
            Method method = targetClass.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    @Nullable
    private static Constructor<?> findDeclaredConstructor(
            @NonNull Class<?> targetClass,
            @NonNull Class<?>... parameterTypes
    ) {
        try {
            Constructor<?> constructor = targetClass.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private interface HookCallback {
        Object onHook(Chain chain) throws Throwable;
    }
}
