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
import hook.HyperBackscreen.common.BackgroundTasks;
import hook.HyperBackscreen.common.RearScreenWakeMatcher;
import hook.HyperBackscreen.common.ThemeResourceAccess;
import hook.HyperBackscreen.ui.SwipePanelHost;
import io.github.libxposed.api.XposedModule;

public class ModuleMain extends XposedModule {
    private final Set<Method> installedMethods = new HashSet<>();
    private final AtomicReference<Integer> pendingSelection = new AtomicReference<>();
    private final AtomicBoolean selectionSyncRunning = new AtomicBoolean();
    private final AtomicBoolean aiIndexSyncRunning = new AtomicBoolean();
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
    private volatile boolean pickupHooksInstalled = false;
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
                || Constants.THEME_STORE_PACKAGE.equals(packageName)
                || Constants.VOICE_ASSIST_PACKAGE.equals(packageName))) {
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

        if (Constants.VOICE_ASSIST_PACKAGE.equals(packageName)) {
            synchronized (this) {
                if (!pickupHooksInstalled) {
                    pickupHooksInstalled = PickupCodeHook.install(this, param.getClassLoader());
                }
            }
            return;
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
                    installLongPressHooks(param.getClassLoader());
                    installSubscreenAppWidgetGateHooks(param.getClassLoader());
                    installAppCardLimitHook(param.getClassLoader());
                    installModuleEntryClickHook(param.getClassLoader());
                    installQuickPanelDispatchHook(param.getClassLoader());
                    installRearScreenSelectionSyncHook(param.getClassLoader());
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
                    installThemeFaceEnrollmentHook(param.getClassLoader());
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
                        logBackScreenStoreData("preset", result);
                        return result;
                    }
            );
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
    private void installSubscreenAppWidgetGateHooks(@NonNull ClassLoader classLoader) {
        hookBooleanTrueIfPresent(
                classLoader,
                Constants.SUBSCREEN_DEVICE_CONFIG_CLASS,
                Constants.SUBSCREEN_DEVICE_CONFIG_APP_WIDGET_METHOD);

        Class<?> guideSettingsClass = findClass(Constants.SUBSCREEN_GUIDE_SETTINGS_CLASS, classLoader);
        if (guideSettingsClass != null) {
            hookMethodIfPresent(
                    guideSettingsClass,
                    Constants.SUBSCREEN_GUIDE_HIDDEN_METHOD,
                    new Class[]{String.class},
                    Constants.SUBSCREEN_GUIDE_SETTINGS_CLASS + "#"
                            + Constants.SUBSCREEN_GUIDE_HIDDEN_METHOD,
                    chain -> {
                        Object key = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                        if (Constants.SUBSCREEN_APP_CARD_GUIDE_KEY.equals(key)) {
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
        Class<?> binderClass = findClass(Constants.SUBSCREEN_SERVICE_BINDER_CLASS, classLoader);
        Class<?> listManagerClass = findClass(Constants.SUBSCREEN_APP_LIST_MANAGER_CLASS, classLoader);
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

        // 背屏上滑面板通过这条分发链取数据，条目要在分发副本里补上。
        hookMethodIfPresent(
                findDeclaredMethod(listManagerClass,
                        Constants.SUBSCREEN_APP_LIST_DISPATCH_METHOD, Consumer.class),
                Constants.SUBSCREEN_APP_LIST_MANAGER_CLASS + "#"
                        + Constants.SUBSCREEN_APP_LIST_DISPATCH_METHOD,
                chain -> {
                    Object consumer = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (!(consumer instanceof Consumer)) return chain.proceed();
                    if (!PrefsBridge.shouldEnableAppCard(this)) return chain.proceed();
                    List<Object> args = new ArrayList<>(chain.getArgs());
                    args.set(0, PanelAppCard.wrapDispatchConsumer(
                            classLoader, (Consumer<?>) consumer));
                    return chain.proceed(args.toArray());
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
                    if (!(list instanceof ArrayList)) return chain.proceed();
                    List<Object> stripped = PanelAppCard.withoutModuleEntry(
                            classLoader, (List<?>) list);
                    if (stripped == null && !(list instanceof SizeCappedList)) {
                        return chain.proceed();
                    }
                    List<Object> args = new ArrayList<>(chain.getArgs());
                    args.set(0, stripped != null
                            ? stripped
                            : new ArrayList<>((SizeCappedList) list));
                    return chain.proceed(args.toArray());
                }
        );

        log(Log.INFO, Constants.LOG_TAG, "App card limit hook installed");
    }

    /**
     * 模块卡片的点击。
     *
     * 宿主 {@code e2.l.onClick} 对 {@code k != 3} 的条目直接 {@code startActivity(item.m)}，
     * 那会把模块设置页开到主屏上；这里改成在背屏当前窗口里展开快捷面板，和原来的上滑面板
     * 是同一套 UI，只是入口换成卡片。
     */
    private void installModuleEntryClickHook(@NonNull ClassLoader classLoader) {
        Class<?> holderClass = findClass(Constants.SUBSCREEN_LAUNCHER_HOLDER_CLASS, classLoader);
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
                        return chain.proceed();
                    });
        }
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
                chain -> AppWidgetFeatureGate.isSubscreenAppWidgetSecureKey(
                        chain.getArgs().size() > 1 ? chain.getArgs().get(1) : null)
                        ? 1
                        : chain.proceed()
        );
        hookMethodIfPresent(
                findDeclaredMethod(Settings.Secure.class, "getInt",
                        ContentResolver.class, String.class),
                "Settings.Secure#getInt(ContentResolver,String)",
                chain -> AppWidgetFeatureGate.isSubscreenAppWidgetSecureKey(
                        chain.getArgs().size() > 1 ? chain.getArgs().get(1) : null)
                        ? 1
                        : chain.proceed()
        );
    }

    private void hookSubscreenLauncherPanelGesture(@NonNull ClassLoader classLoader) {
        Class<?> gestureClass = findClass(
                Constants.SUBSCREEN_LAUNCHER_PANEL_GESTURE_CLASS, classLoader);
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
                    if (PrefsBridge.shouldFixRearScreenApply(this)
                            && chain.getThisObject() instanceof View panel) {
                        consumePendingRearSelection(panel);
                    } else if (chain.getThisObject() instanceof View panel) {
                        pendingRearSelections.remove(panel);
                    }
                    return result;
                }
        );
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
                SystemClock.elapsedRealtime());
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

        PendingRearSelection(int wallpaperId, int selectedIndex, long requestedAtElapsed) {
            this.wallpaperId = wallpaperId;
            this.selectedIndex = selectedIndex;
            this.requestedAtElapsed = requestedAtElapsed;
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
        Method applyCheckMethod = findFirstDeclaredMethod(
                viewModelClass,
                new Class[]{List.class},
                Constants.THEME_APPLY_CHECK_METHOD_18PRO,
                Constants.THEME_APPLY_CHECK_METHOD_OS4,
                Constants.THEME_APPLY_CHECK_METHOD);
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
        hookBooleanTrueIfPresent(
                classLoader,
                Constants.THEME_DEVICE_UTILS_CLASS,
                "a");
        hookStringReturnIfPresent(
                classLoader,
                Constants.THEME_DEVICE_UTILS_CLASS,
                "y",
                Constants.THEME_REAR_DEVICE);
        hookStringReturnIfPresent(
                classLoader,
                Constants.THEME_DEVICE_UTILS_CLASS,
                "cdj",
                Constants.THEME_REAR_MODEL);
        hookStringReturnIfPresent(
                classLoader,
                Constants.THEME_DEVICE_UTILS_CLASS,
                "z",
                Constants.THEME_REAR_VERSION);
        hookStringReturnIfPresent(
                classLoader,
                Constants.THEME_ONLINE_SERVICE_CLASS,
                "fnq8",
                Constants.THEME_REAR_VERSION);
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
                    Object request = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    Object params = chain.getArgs().size() > 1 ? chain.getArgs().get(1) : null;
                    if (shouldSpoofThemeAiAppRequest(request) && params instanceof Map<?, ?> rawMap) {
                        @SuppressWarnings("unchecked")
                        Map<String, String> map = (Map<String, String>) rawMap;
                        map.put("device", Constants.THEME_REAR_DEVICE);
                        map.put("model", Constants.THEME_REAR_MODEL);
                        map.put("version", Constants.THEME_REAR_VERSION);
                        map.put("isSupportRearScreen", "true");
                        log(Log.DEBUG, Constants.LOG_TAG,
                                "Spoofed Theme AI app request as " + Constants.THEME_REAR_DEVICE);
                    }
                    return chain.proceed();
                }
        );
    }

    private boolean shouldSpoofThemeAiAppRequest(@Nullable Object request) {
        Object url = callMethodQuietly(request, "cdj");
        String raw = String.valueOf(url);
        return raw.contains(Constants.THEME_AI_APP_PAGE_PATH)
                || raw.contains(Constants.THEME_AI_APP_SUBJECT_PATH)
                || raw.contains(Constants.THEME_AI_APP_DETAIL_PATH);
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
                    Object requestUrl = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (isThemeRearDownloadRequest(requestUrl)) {
                        addThemeRearDownloadParams(requestUrl);
                        log(Log.DEBUG, Constants.LOG_TAG,
                                "Theme AI app download request: "
                                        + summarizeThemeDownloadRequest(requestUrl));
                    }
                    Object result = chain.proceed();
                    if (isThemeRearDownloadRequest(requestUrl)) {
                        log(Log.DEBUG, Constants.LOG_TAG,
                                "Theme AI app download response length="
                                        + (result instanceof String ? ((String) result).length() : -1));
                    }
                    return result;
                }
        );
    }

    private boolean isThemeRearDownloadRequest(@Nullable Object requestUrl) {
        Object baseUrl = callMethodQuietly(requestUrl, "getBaseUrl");
        return String.valueOf(baseUrl).contains(Constants.THEME_DOWNLOAD_PATH);
    }

    private void addThemeRearDownloadParams(@Nullable Object requestUrl) {
        callMethodQuietly(requestUrl, "addParameter", "device", Constants.THEME_REAR_DEVICE);
        callMethodQuietly(requestUrl, "addParameter", "model", Constants.THEME_REAR_MODEL);
        callMethodQuietly(requestUrl, "addParameter", "version", Constants.THEME_REAR_VERSION);
        callMethodQuietly(requestUrl, "addParameter", "isSupportRearScreen", "true");
    }

    @NonNull
    private String summarizeThemeDownloadRequest(@Nullable Object requestUrl) {
        String baseUrl = String.valueOf(callMethodQuietly(requestUrl, "getBaseUrl"));
        return "baseUrl=" + baseUrl
                + ", category=" + String.valueOf(callMethodQuietly(requestUrl, "getParameter", "category"))
                + ", device=" + String.valueOf(callMethodQuietly(requestUrl, "getParameter", "device"))
                + ", model=" + String.valueOf(callMethodQuietly(requestUrl, "getParameter", "model"))
                + ", version=" + String.valueOf(callMethodQuietly(requestUrl, "getParameter", "version"))
                + ", isSupportRearScreen="
                + String.valueOf(callMethodQuietly(requestUrl, "getParameter", "isSupportRearScreen"));
    }

    private void installThemeFaceEnrollmentHook(@NonNull ClassLoader classLoader) {
        // Theme Manager 11.5.3.1: k(Context) is the enrollment-count query only.
        // Keep the credential gate, enrollment UI, content audit and authentication intact.
        Class<?> helper = findClass("com.rearScreen.helper.RearScreenBiometricHelper", classLoader);
        if (helper == null) return;
        Method method = findDeclaredMethod(helper, "k", Context.class);
        if (method == null || method.getReturnType() != int.class
                || !java.lang.reflect.Modifier.isStatic(method.getModifiers())) return;
        hookMethodIfPresent(method, "RearScreenBiometricHelper#k", chain -> {
            Object original = chain.proceed();
            if (!(original instanceof Integer count) || count > 0
                    || !PrefsBridge.shouldFixRearScreenApply(this)) return original;
            if (chain.getArgs().isEmpty() || !(chain.getArgs().get(0) instanceof Context context)) {
                return original;
            }
            int actual = ThemeFaceEnrollment.read(context);
            return actual >= 0 ? actual : original;
        });
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
                    Intent aiIntent = resolveRearScreenAiIntent(classLoader);
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
        hookMethodIfPresent(
                targetClass,
                methodName,
                new Class[]{},
                className + "#" + methodName,
                chain -> true
        );
    }

    private void hookStringReturnIfPresent(
            @NonNull ClassLoader classLoader,
            @NonNull String className,
            @NonNull String methodName,
            @NonNull String value
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
                chain -> value
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
                    context, classLoader, "AI 背屏", aiIntent);
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
                ((TextView) titleView).setText("AI 背屏");
            }
        }
        row.setOnClickListener(v -> {
            try {
                Intent intent = resolveRearScreenAiIntent(v.getContext().getClassLoader());
                if (intent != null) {
                    v.getContext().startActivity(intent);
                }
            } catch (Throwable e) {
                log(Log.WARN, Constants.LOG_TAG,
                        "AI rear screen entry launch failed", e);
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

    private void installThemeAiAppIndexSyncHooks(@NonNull ClassLoader classLoader) {
        Class<?> activityClass = findFirstClass(
                classLoader,
                Constants.THEME_REAR_SCREEN_AI_APP_ACTIVITY,
                Constants.THEME_AI_REAR_SCREEN_LIST_ACTIVITY);
        if (activityClass == null) {
            log(Log.DEBUG, Constants.LOG_TAG, "Theme AI app index sync target missing");
            return;
        }

        hookMethodIfPresent(
                activityClass,
                "onCreate",
                new Class[]{Bundle.class},
                activityClass.getName() + "#onCreate",
                chain -> {
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
                    syncAiGeneratedAppCardsToSubscreenIndex();
                    return chain.proceed();
                }
        );
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
        File runtimeFile = new File(Constants.THEME_AI_APP_RUNTIME_FILE);
        File appInfoFile = new File(Constants.SUBSCREEN_APP_INFO_FILE);
        if (!runtimeFile.isFile()) {
            return;
        }
        try {
            JSONArray runtimeItems = new JSONArray(readUtf8(runtimeFile, MAX_THEME_INDEX_BYTES));
            JSONArray appInfoItems = appInfoFile.isFile()
                    ? new JSONArray(readUtf8(appInfoFile, MAX_THEME_INDEX_BYTES))
                    : new JSONArray();

            LinkedHashSet<String> existingIds = new LinkedHashSet<>();
            for (int i = 0; i < appInfoItems.length(); i++) {
                JSONObject item = appInfoItems.optJSONObject(i);
                if (item == null) continue;
                String resId = item.optString("resId", "");
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
                appInfoItems.put(appInfoItem);
                existingIds.add(productId);
                added++;
            }

            if (added <= 0) {
                grantAiAppIndexAccess(runtimeFile, appInfoFile, appInfoItems);
                return;
            }

            backupFileOnce(appInfoFile);
            writeJsonArray(appInfoFile, appInfoItems);
            grantAiAppIndexAccess(runtimeFile, appInfoFile, appInfoItems);
            log(Log.INFO, Constants.LOG_TAG,
                    "Synced AI app cards to SubScreenCenter index: added=" + added
                            + ", total=" + appInfoItems.length());
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "syncAiGeneratedAppCardsToSubscreenIndex failed", e);
        }
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

    private interface HookCallback {
        Object onHook(Chain chain) throws Throwable;
    }
}
