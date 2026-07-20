package hook.HyperBackscreen.hook;

import android.util.Log;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;

import hook.HyperBackscreen.bridge.PrefsBridge;
import hook.HyperBackscreen.bridge.SettingsSyncBridge;
import hook.HyperBackscreen.common.Constants;
import io.github.libxposed.api.XposedModule;

public class ModuleMain extends XposedModule {
    private static volatile ModuleMain runningInstance;
    private volatile boolean hooksInstalled = false;
    private volatile boolean hookContextReady = false;

    @Nullable
    public static ModuleMain getRunningInstance() {
        return runningInstance;
    }

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        runningInstance = this;
        log(Log.INFO, Constants.LOG_TAG, "onModuleLoaded: " + param.getProcessName());
    }

    private volatile boolean themeStoreHooksInstalled = false;

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String packageName = param.getPackageName();

        if (Constants.TARGET_PACKAGE.equals(packageName)) {
            if (hooksInstalled) return;
            synchronized (this) {
                if (hooksInstalled) return;
                try {
                    installHookContextBootstrap(param.getClassLoader());
                    installLongPressHooks(param.getClassLoader());
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
                    installRearScreenApplyFixHook(param.getClassLoader());
                    themeStoreHooksInstalled = true;
                    log(Log.INFO, Constants.LOG_TAG, "Theme store hooks installed");
                } catch (Throwable throwable) {
                    log(Log.ERROR, Constants.LOG_TAG, "Failed to install theme store hooks", throwable);
                }
            }
        }
    }

    private void installHookContextBootstrap(@NonNull ClassLoader classLoader) {
        Class<?> mainPanelClass = findClass(Constants.MAIN_PANEL_CLASS, classLoader);
        if (mainPanelClass == null) {
            log(Log.WARN, Constants.LOG_TAG, "Hook target missing: " + Constants.MAIN_PANEL_CLASS);
            return;
        }
        hookMethodIfPresent(
                mainPanelClass,
                "dispatchTouchEvent",
                new Class[]{MotionEvent.class},
                "MainPanel#dispatchTouchEvent",
                chain -> {
                    if (!hookContextReady) {
                        Object thisObject = chain.getThisObject();
                        if (thisObject instanceof android.view.View view) {
                            PrefsBridge.setHookContext(view.getContext());
                            SettingsSyncBridge.ensureHookReceiverInstalled(view.getContext());
                            hookContextReady = true;
                        }
                    }
                    return chain.proceed();
                }
        );
    }

    private void installLongPressHooks(@NonNull ClassLoader classLoader) {
        Class<?> newGestureClass = findClass(Constants.HOOK_CLASS_LONG_PRESS_NEW, classLoader);
        if (newGestureClass != null) {
            hookLongPressMethod(newGestureClass, Constants.HOOK_METHOD_GATE_NEW, new Class[]{MotionEvent.class}, false);
            hookLongPressMethod(newGestureClass, Constants.HOOK_METHOD_LONG_PRESS_TOUCH_NEW, new Class[]{MotionEvent.class}, null);
            hookLongPressMethod(newGestureClass, Constants.HOOK_METHOD_RUN, new Class[]{}, null);
        }

        Class<?> legacyGestureClass = findClass(Constants.HOOK_CLASS, classLoader);
        if (legacyGestureClass != null && legacyGestureClass != newGestureClass) {
            hookLongPressMethod(legacyGestureClass, Constants.HOOK_METHOD_GATE, new Class[]{MotionEvent.class}, false);
            hookLongPressMethod(legacyGestureClass, Constants.HOOK_METHOD_RUN, new Class[]{}, null);
        }
    }

    private void installWallpaperLimitHook(@NonNull ClassLoader classLoader) {
        Class<?> viewModelClass = findClass(Constants.THEME_REAR_VIEWMODEL_CLASS, classLoader);
        if (viewModelClass == null) {
            log(Log.WARN, Constants.LOG_TAG, "Theme hook target missing: " + Constants.THEME_REAR_VIEWMODEL_CLASS);
            return;
        }
        hookMethodIfPresent(
                viewModelClass,
                Constants.THEME_APPLY_CHECK_METHOD,
                new Class[]{java.util.List.class},
                "RearScreenDetailViewModel#o5",
                chain -> {
                    if (PrefsBridge.shouldRemoveWallpaperLimit()) {
                        return true;
                    }
                    return chain.proceed();
                }
        );
    }

    private void installRearScreenApplyFixHook(@NonNull ClassLoader classLoader) {
        if (!PrefsBridge.shouldFixRearScreenApply()) {
            return;
        }

        Class<?> applyResultClass = findClass("com.rearScreen.manager.RearScreenResOperationHelper$Companion$apply$applyResult$1", classLoader);
        if (applyResultClass == null) {
            return;
        }

        hookMethodIfPresent(
                applyResultClass,
                "invokeSuspend",
                new Class[]{Object.class},
                "RearScreenResOperationHelper$Companion$apply$applyResult$1#invokeSuspend",
                chain -> {
                    Object bean = getFieldValue(chain.getThisObject(), "$bean");
                    if (bean != null) {
                        fillSnapshotPaths(bean);
                        patchRightsPath(bean, classLoader);
                        patchMtzPath(bean);
                    }
                    return chain.proceed();
                }
        );
    }

    private void fillSnapshotPaths(Object bean) {
        try {
            String localPath = (String) callMethod(bean, "getResLocalPath");
            String snapshotPath = (String) callMethod(bean, "getResSnapshotPath");
            if (isEmpty(snapshotPath) && !isEmpty(localPath) && new java.io.File(localPath).exists()) {
                callMethod(bean, "setResSnapshotPath", localPath);
            }
            String metaSrc = (String) callMethod(bean, "getMetaPath");
            String metaSnap = (String) callMethod(bean, "getMetaSnapshotPath");
            if (isEmpty(metaSnap) && !isEmpty(metaSrc) && new java.io.File(metaSrc).exists()) {
                callMethod(bean, "setMetaSnapshotPath", metaSrc);
            }
        } catch (Throwable ignored) {
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
            String baseName = new java.io.File(rightPath).getName();
            if (!baseName.startsWith("rearscreen_")) {
                baseName = "rearscreen_" + baseName;
            }
            String target = rightsDir + baseName;

            java.io.File targetFile = new java.io.File(target);
            if (targetFile.exists()) {
                setWorldAccessible(targetFile);
                callMethod(bean, "setRightPath", target);
                return;
            }

            java.io.File sourceFile = new java.io.File(rightPath);
            if (!sourceFile.exists()) {
                java.io.File fallback = findNewestRightsFile(rightsDir, target);
                if (fallback != null) {
                    safeCopy(fallback, targetFile);
                    setWorldAccessible(targetFile);
                    callMethod(bean, "setRightPath", target);
                } else {
                    callMethod(bean, "setRightPath", target);
                }
                return;
            }

            if (!targetFile.exists()) {
                safeCopy(sourceFile, targetFile);
                setWorldAccessible(targetFile);
            }
            callMethod(bean, "setRightPath", target);
        } catch (Throwable ignored) {
        }
    }

    private void patchMtzPath(Object bean) {
        try {
            String srcMtz = (String) callMethod(bean, "getResLocalPath");
            if (isEmpty(srcMtz)) {
                return;
            }
            java.io.File srcFile = new java.io.File(srcMtz);
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
            java.io.File destFile = new java.io.File(destMtz);
            if (!destFile.exists()) {
                safeCopy(srcFile, destFile);
                setWorldAccessible(destFile);
            }
            callMethod(bean, "setResLocalPath", destMtz);
            callMethod(bean, "setResSnapshotPath", destMtz);
        } catch (Throwable ignored) {
        }
    }

    private String resolveRightsDir(ClassLoader classLoader) {
        String dir = getStaticStringField(classLoader,
                "com.android.thememanager.basemodule.resource.constants.ThemeResourceConstants", "cmzf");
        if (!isEmpty(dir)) return dir;
        dir = getStaticStringField(classLoader,
                "com.android.thememanager.basemodule.resource.constants.ThemeResourceConstants", "qp5l");
        if (!isEmpty(dir)) return dir;
        return "/data/system/theme/rights/";
    }

    private java.io.File findNewestRightsFile(String dirPath, String excludePath) {
        java.io.File dir = new java.io.File(dirPath);
        java.io.File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }
        java.io.File newest = null;
        long newestTime = Long.MIN_VALUE;
        for (java.io.File f : files) {
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

    private void setWorldAccessible(java.io.File file) {
        file.setReadable(true, false);
        file.setWritable(true, false);
        file.setExecutable(true, false);
    }

    private void safeCopy(java.io.File src, java.io.File dst) {
        java.io.File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.getFD().sync();
        } catch (Throwable ignored) {
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static Object getFieldValue(Object target, String field) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Object callMethod(Object target, String method, Object... args) throws Throwable {
        Class<?>[] paramTypes = new Class[args.length];
        for (int i = 0; i < args.length; i++) {
            paramTypes[i] = args[i].getClass();
            if (paramTypes[i] == Boolean.class) paramTypes[i] = boolean.class;
            else if (paramTypes[i] == Integer.class) paramTypes[i] = int.class;
            else if (paramTypes[i] == Long.class) paramTypes[i] = long.class;
        }
        java.lang.reflect.Method m = findMethod(target.getClass(), method, paramTypes);
        if (m == null) throw new NoSuchMethodException(method);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static java.lang.reflect.Method findMethod(Class<?> clazz, String name, Class<?>[] paramTypes) {
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
            java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
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

    private void hookMethodIfPresent(
            @NonNull Class<?> targetClass,
            @NonNull String methodName,
            @NonNull Class<?>[] parameterTypes,
            @NonNull String label,
            @NonNull HookCallback callback
    ) {
        hookMethodIfPresent(findDeclaredMethod(targetClass, methodName, parameterTypes), label, callback);
    }

    private void hookMethodIfPresent(
            @Nullable Method method,
            @NonNull String label,
            @NonNull HookCallback callback
    ) {
        if (method == null) {
            log(Log.WARN, Constants.LOG_TAG, "Hook target missing: " + label);
            return;
        }
        try {
            hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(callback::onHook);
            log(Log.DEBUG, Constants.LOG_TAG, "Hook installed: " + label);
        } catch (Throwable throwable) {
            log(Log.ERROR, Constants.LOG_TAG, "Hook install failed: " + label, throwable);
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
