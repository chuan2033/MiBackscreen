package hook.HyperBackscreen.hook;

import android.util.Log;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import hook.HyperBackscreen.bridge.PrefsBridge;
import hook.HyperBackscreen.common.Constants;
import io.github.libxposed.api.XposedModule;

public class ModuleMain extends XposedModule {
    private volatile boolean hooksInstalled = false;
    private volatile boolean themeStoreHooksInstalled = false;

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        log(Log.INFO, Constants.LOG_TAG, "onModuleLoaded: " + param.getProcessName());
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String packageName = param.getPackageName();

        if (Constants.TARGET_PACKAGE.equals(packageName)) {
            if (hooksInstalled) return;
            synchronized (this) {
                if (hooksInstalled) return;
                try {
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
                new Class[]{List.class},
                Constants.THEME_REAR_VIEWMODEL_CLASS + "#" + Constants.THEME_APPLY_CHECK_METHOD,
                chain -> {
                    if (PrefsBridge.shouldRemoveWallpaperLimit(this)) {
                        return true;
                    }
                    return chain.proceed();
                }
        );
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
                    if (PrefsBridge.shouldFixRearScreenApply(this)) {
                        Object bean = getFieldValue(chain.getThisObject(), Constants.THEME_APPLY_BEAN_FIELD);
                        if (bean != null) {
                            fillSnapshotPaths(bean);
                            patchRightsPath(bean, classLoader);
                            patchMtzPath(bean);
                        }
                    }
                    return chain.proceed();
                }
        );
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

    private String resolveRightsDir(ClassLoader classLoader) {
        String dir = getStaticStringField(classLoader,
                Constants.THEME_RESOURCE_CONSTANTS_CLASS, Constants.THEME_RIGHTS_DIR_FIELD_PRIMARY);
        if (!isEmpty(dir)) return dir;
        dir = getStaticStringField(classLoader,
                Constants.THEME_RESOURCE_CONSTANTS_CLASS, Constants.THEME_RIGHTS_DIR_FIELD_FALLBACK);
        if (!isEmpty(dir)) return dir;
        return Constants.THEME_RIGHTS_DIR_DEFAULT;
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

    private void grantReadAccess(File file) {
        // 仅设为全局可读（供主题服务跨进程读取）；写权限限所有者，且不置可执行位
        file.setReadable(true, false);
        file.setWritable(true, true);
        file.setExecutable(false, false);
    }

    private void safeCopy(File src, File dst) {
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
        } catch (Throwable e) {
            log(Log.WARN, Constants.LOG_TAG, "safeCopy failed", e);
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static Object getFieldValue(Object target, String field) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable e) {
            return null;
        }
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
