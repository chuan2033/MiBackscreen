package hook.HyperBackscreen.hook.util;

import android.annotation.SuppressLint;
import android.util.Log;

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
import java.util.List;

import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.common.ThemeResourceAccess;
import hook.HyperBackscreen.hook.target.MethodTarget;
import hook.HyperBackscreen.hook.target.TargetMembers;
import hook.HyperBackscreen.hook.target.TargetResolver;

import io.github.libxposed.api.XposedModule;

/**
 * 各作用域共用的无状态静态工具：反射取值 / 赋值 / 调用、按名字或签名查找类与方法、
 * UTF-8 读文件、拷贝文件、theme 资源文件的读权限。没有任何状态，任何进程都可以直接用；
 * 唯一需要写日志的 {@link #safeCopy} 由调用方把模块实例传进来。
 */
public final class HookReflection {
    public static final long MAX_EDIT_CONFIG_BYTES = 512 * 1024L;
    public static final long MAX_THEME_INDEX_BYTES = 2 * 1024 * 1024L;

    private HookReflection() {
    }

    @Nullable
    public static Object invokeNoArg(@Nullable Object target, @NonNull String methodName) {
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
     * MainPanel 的生命周期 / 选择回调都是 void 方法；名字被宿主重排给了有返回值的方法时，
     * 说明已经不是同一个目标，宁可不挂。
     */
    @Nullable
    public static Method voidOnly(@Nullable Method method) {
        return method != null && method.getReturnType() == void.class ? method : null;
    }

    /** 用 DexKit 方法体锚点定位无参方法并调用；未命中时返回 null。 */
    @Nullable
    public static Object callMethodResolvedByTarget(
            @Nullable Object target, @NonNull MethodTarget spec) {
        if (target == null) return null;
        Method method = TargetResolver.resolveMethod(target.getClass().getClassLoader(), spec);
        if (method == null) return null;
        try {
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    public static Field findStaticCompanionField(Class<?> owner, String preferredName) {
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

    public static void grantThemeResourceAssetAccess(@NonNull File file) {
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
    public static void grantThemeResourceDirectoryAccess(@Nullable File dir) {
        File current = dir;
        while (current != null && ThemeResourceAccess.isGrantablePath(current.getAbsolutePath())) {
            current.setReadable(true, false);
            current.setWritable(true, true);
            current.setExecutable(true, false);
            current = current.getParentFile();
        }
    }

    @SuppressLint("SetWorldReadable")
    public static void grantReadAccess(File file) {
        // 仅设为全局可读（供主题服务跨进程读取）；写权限限所有者，且不置可执行位
        file.setReadable(true, false);
        file.setWritable(true, true);
        file.setExecutable(false, false);
    }

    public static String readUtf8(@NonNull File file) throws IOException {
        return readUtf8(file, MAX_EDIT_CONFIG_BYTES);
    }

    public static String readUtf8(@NonNull File file, long maxChars) throws IOException {
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

    @Nullable
    public static String asString(@Nullable Object value) {
        return value instanceof String ? (String) value : null;
    }

    /** 拷贝文件并 fsync；失败时经 [module] 写 WARN 日志（与拆分前 ModuleMain 实例方法的输出相同）。 */
    public static boolean safeCopy(@NonNull XposedModule module, File src, File dst) {
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
            module.log(Log.WARN, Constants.LOG_TAG, "safeCopy failed", e);
            return false;
        }
    }

    public static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    public static Object getFieldValue(Object target, String field) {
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
    public static Object getStaticFieldValueByType(
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

    public static boolean setFieldValue(Object target, String field, Object value) {
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
    public static Object getFieldValueByType(
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
    public static boolean setFirstFieldValue(
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
    public static List<?> callRearListGetter(@Nullable Object manager) {
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

    public static Object callMethod(Object target, String method, Object... args) throws Throwable {
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

    public static Object callMethodCompatible(Object target, String method, Object... args)
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
    public static Object callNoArgMethodQuietly(@Nullable Object target, @NonNull String method) {
        if (target == null) return null;
        try {
            return callMethod(target, method);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 按返回类型结构定位无参方法并调用；不唯一或未命中时回退到指定混淆名。
     */
    @Nullable
    public static Object callNoArgMethodResolved(
            @Nullable Object target, @NonNull Class<?> returnType, @NonNull String fallbackName) {
        if (target == null) return null;
        Method method = TargetMembers.resolveNoArgMethod(target.getClass(), returnType, fallbackName);
        if (method == null) return null;
        try {
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 按签名结构定位方法并调用；不唯一或未命中时回退到指定混淆名。
     */
    @Nullable
    public static Object callMethodResolved(
            @Nullable Object target,
            @Nullable Class<?> returnType,
            @NonNull String fallbackName,
            Object... args) {
        if (target == null) return null;
        Class<?>[] paramTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            paramTypes[i] = args[i] != null ? args[i].getClass() : Object.class;
        }
        Method method = TargetMembers.resolveMethod(target.getClass(), returnType, paramTypes, fallbackName);
        if (method == null) return null;
        try {
            return method.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    public static Object callMethodQuietly(@Nullable Object target, @NonNull String method, Object... args) {
        if (target == null) return null;
        try {
            return callMethod(target, method, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Method findMethod(Class<?> clazz, String name, Class<?>[] paramTypes) {
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
    public static Method findCompatibleMethod(
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

    public static String getStaticStringField(ClassLoader classLoader, String className, String fieldName) {
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

    @Nullable
    public static Object defaultReturnFor(@NonNull Class<?> type, @Nullable Object preferred) {
        if (type == void.class || type == Void.class) return null;
        if (type == boolean.class) return preferred instanceof Boolean ? preferred : Boolean.FALSE;
        if (type == Boolean.class) return preferred instanceof Boolean ? preferred : null;
        if (!type.isPrimitive()) return type.isInstance(preferred) ? preferred : null;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    @Nullable
    public static Class<?> findClass(@NonNull String className, @NonNull ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @Nullable
    public static Class<?> findFirstClass(
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
    public static Method findFirstDeclaredMethod(
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
    public static Method findDeclaredMethodWithFirstParam(
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
    public static Method findDeclaredMethod(
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
    public static Constructor<?> findDeclaredConstructor(
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
}
