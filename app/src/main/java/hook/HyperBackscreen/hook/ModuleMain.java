package hook.HyperBackscreen.hook;

import static hook.HyperBackscreen.hook.util.HookReflection.findDeclaredMethod;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.HashSet;

import hook.HyperBackscreen.bridge.PrefsBridge;
import hook.HyperBackscreen.BuildConfig;
import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.panel.SwipePanelHost;
import hook.HyperBackscreen.hook.target.TargetMembers;
import hook.HyperBackscreen.hook.target.TargetResolver;
import hook.HyperBackscreen.hook.appindex.SubscreenAppIndex;
import hook.HyperBackscreen.hook.assistant.PersonalAssistantHooks;
import hook.HyperBackscreen.hook.subscreen.RearLyricHooks;
import hook.HyperBackscreen.hook.subscreen.RearVideoHooks;
import hook.HyperBackscreen.hook.subscreen.SubScreenCenterHooks;
import hook.HyperBackscreen.hook.system.SystemServerHooks;
import hook.HyperBackscreen.hook.theme.RearVideoImportHooks;
import hook.HyperBackscreen.hook.theme.ThemeManagerHooks;
import hook.HyperBackscreen.hook.theme.ThemeResourceRepair;

import io.github.libxposed.api.XposedModule;

/**
 * LSPosed 模块入口（{@code META-INF/xposed/java_init.list} 按类名加载，类名与包名不能改）。
 *
 * <p>这里只做两件事：
 * <ul>
 *   <li>按进程分发：system_server → {@link SystemServerHooks}；背屏中心
 *       {@code com.xiaomi.subscreencenter} → {@link SubScreenCenterHooks}；主题商店
 *       {@code com.android.thememanager} → {@link ThemeManagerHooks} 与 {@link ThemeResourceRepair}；
 *       智能助理 {@code com.miui.personalassistant} → {@link PersonalAssistantHooks}。
 *       背屏中心与主题商店共用的 appInfo.json 应用卡索引在 {@link SubscreenAppIndex}。</li>
 *   <li>公共 Hook 设施：{@link #hookMethodIfPresent}（带已安装方法去重）、DexKit 目标定位器初始化、
 *       debug 宿主探针。日志直接用框架的 {@code log}。</li>
 * </ul>
 * LSPosed 每个进程只创建一个本类实例；各作用域对象作为它的字段各建一份，
 * 原来挂在本类上的缓存与状态因此仍是「每进程一份」。
 */
public class ModuleMain extends XposedModule {
    private final Set<Method> installedMethods = new HashSet<>();
    private final Set<Constructor<?>> installedConstructors = new HashSet<>();
    private volatile boolean hooksInstalled = false;
    private volatile boolean themeStoreHooksInstalled = false;
    private volatile boolean personalAssistantHooksInstalled = false;

    // 各作用域的 Hook 实现，随本实例各建一份（LSPosed 每进程一个 ModuleMain）。
    private final SystemServerHooks systemServerHooks = new SystemServerHooks(this);
    private final SubscreenAppIndex subscreenAppIndex = new SubscreenAppIndex(this);
    private final SubScreenCenterHooks subScreenCenterHooks =
            new SubScreenCenterHooks(this, subscreenAppIndex);
    private final ThemeResourceRepair themeResourceRepair = new ThemeResourceRepair(this);
    private final ThemeManagerHooks themeManagerHooks =
            new ThemeManagerHooks(this, subscreenAppIndex, themeResourceRepair);
    private final PersonalAssistantHooks personalAssistantHooks = new PersonalAssistantHooks(this);
    private final RearVideoHooks rearVideoHooks = new RearVideoHooks(this);
    private final RearLyricHooks rearLyricHooks = new RearLyricHooks(this);
    private final RearVideoImportHooks rearVideoImportHooks = new RearVideoImportHooks(this);

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        // 供被 Hook 进程（背屏）内的 PrefsBridge 取远程偏好使用
        PrefsBridge.attachModule(this);
        log(Log.INFO, Constants.LOG_TAG, "onModuleLoaded: " + param.getProcessName());
        if (param.isSystemServer()) {
            ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
            if (contextClassLoader != null) {
                systemServerHooks.installSystemHooksOnce(contextClassLoader);
            }
            systemServerHooks.installSystemHooksOnce(ClassLoader.getSystemClassLoader());
        }
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        systemServerHooks.installSystemHooksOnce(param.getClassLoader());
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
                    personalAssistantHooks.installPersonalAssistantStoreHooks(param.getClassLoader());
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
            systemServerHooks.installSystemHooksOnce(param.getClassLoader());
            return;
        }

        if (Constants.TARGET_PACKAGE.equals(packageName)) {
            if (hooksInstalled) return;
            synchronized (this) {
                if (hooksInstalled) return;
                try {
                    SwipePanelHost.setLoadedModuleApkPath(getModuleApplicationInfo().sourceDir);
                    installTargetResolver(packageName);
                    subScreenCenterHooks.installLongPressHooks(param.getClassLoader());
                    subScreenCenterHooks.installSubscreenAppWidgetGateHooks(param.getClassLoader());
                    subScreenCenterHooks.installAppCardLimitHook(param.getClassLoader());
                    subScreenCenterHooks.installModuleEntryClickHook(param.getClassLoader());
                    subScreenCenterHooks.installQuickPanelDispatchHook(param.getClassLoader());
                    subScreenCenterHooks.installRearScreenSelectionSyncHook(param.getClassLoader());
                    subScreenCenterHooks.installAutoWallpaperRotationHooks(param.getClassLoader());
                    subScreenCenterHooks.installMusicWidgetWhitelistHooks(param.getClassLoader());
                    // 以下两项各自吞掉异常，失败只影响自己。
                    rearVideoHooks.install(param.getClassLoader());
                    rearLyricHooks.install(param.getClassLoader());
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
                    installTargetResolver(packageName);
                    themeManagerHooks.installWallpaperLimitHook(param.getClassLoader());
                    themeManagerHooks.installThemeDeviceIdentityHooks(param.getClassLoader());
                    themeManagerHooks.installThemeNetworkDeviceSpoofHook(param.getClassLoader());
                    themeManagerHooks.installThemeLegacyDownloadHook(param.getClassLoader());
                    themeResourceRepair.installRearScreenApplyFixHook(param.getClassLoader());
                    themeManagerHooks.installAiCompanionReuseApplyHook(param.getClassLoader());
                    themeManagerHooks.installThemeFaceEnrollmentHook(param.getClassLoader());
                    themeManagerHooks.installRearScreenPersonalizationPresetHook(param.getClassLoader());
                    themeManagerHooks.installThemeSettingsSelectionSyncHook(param.getClassLoader());
                    themeManagerHooks.installThemeAiAppIndexSyncHooks(param.getClassLoader());
                    themeManagerHooks.installThemeSettingsAiVisibilityHooks(param.getClassLoader());
                    themeManagerHooks.installThemeSettingsShortcutHook(param.getClassLoader());
                    rearVideoImportHooks.install(param.getClassLoader());
                    themeStoreHooksInstalled = true;
                    log(Log.INFO, Constants.LOG_TAG, "Theme store hooks installed");
                } catch (Throwable throwable) {
                    log(Log.ERROR, Constants.LOG_TAG, "Failed to install theme store hooks", throwable);
                }
            }
        }

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
        TargetResolver.install(context, apkPath, versionCode, message -> {
            // 定位失败与查询变慢是排障入口，按 WARN 记录，避免被 DEBUG 过滤掉。
            boolean problem = message.startsWith("Target not found")
                    || message.startsWith("Slow target query")
                    || message.contains("failed");
            log(problem ? Log.WARN : Log.DEBUG, Constants.LOG_TAG, message);
        });
        log(Log.INFO, Constants.LOG_TAG,
                "Target resolver: " + TargetResolver.state() + ", host version " + versionCode
                        + ", apk " + apkPath);
    }

    /**
     * 把无参 boolean 方法挂成「18 Pro 功能开启时恒为 true」；方法名按返回类型结构定位，
     * 定位不到时回退到给定的混淆名。背屏中心与主题商店两个作用域共用。
     */
    public void hookBooleanTrueIfPresent(
            @NonNull Class<?> targetClass,
            @NonNull String label,
            @NonNull String methodName
    ) {
        String resolved = TargetMembers.methodName(
                TargetMembers.resolveNoArgMethod(targetClass, boolean.class, methodName),
                methodName);
        hookMethodIfPresent(
                targetClass,
                resolved,
                new Class[]{},
                label + "#" + resolved,
                chain -> PrefsBridge.shouldEnable18ProFeatures(this) ? true : chain.proceed()
        );
    }

    public boolean hookMethodIfPresent(
            @NonNull Class<?> targetClass,
            @NonNull String methodName,
            @NonNull Class<?>[] parameterTypes,
            @NonNull String label,
            @NonNull HookCallback callback
    ) {
        return hookMethodIfPresent(findDeclaredMethod(targetClass, methodName, parameterTypes), label, callback);
    }

    public synchronized boolean hookMethodIfPresent(
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

    public synchronized boolean hookConstructorIfPresent(
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

    public interface HookCallback {
        Object onHook(Chain chain) throws Throwable;
    }
}
