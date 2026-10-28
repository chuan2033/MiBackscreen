package hook.HyperBackscreen.hook.target;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 按签名或字段类型定位宿主成员。
 *
 * 用于「类名稳定、成员名被混淆」的场景（主题商店的 com.rearScreen.* 几乎都是这种）：
 * 类名照常写死，成员靠参数类型、返回类型、字段类型判断，宿主重新混淆名字不影响命中。
 * 判定要求唯一，命中多个视为失败，避免误挂到同签名的其他方法上。
 */
public final class TargetMembers {
    private TargetMembers() {
    }

    /** 参数类型与返回类型都匹配的方法；命中多个返回 null。 */
    @Nullable
    public static Method uniqueMethod(
            @NonNull Class<?> owner,
            @Nullable Class<?> returnType,
            @NonNull Class<?>... parameterTypes) {
        Method found = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (method.isBridge() || method.isSynthetic()) continue;
            if (returnType != null && method.getReturnType() != returnType) continue;
            if (!sameTypes(method.getParameterTypes(), parameterTypes)) continue;
            if (found != null) return null;
            found = method;
        }
        return found;
    }

    /** 无参、返回类型匹配的方法；命中多个返回 null。 */
    @Nullable
    public static Method uniqueNoArgMethod(@NonNull Class<?> owner, @NonNull Class<?> returnType) {
        return uniqueMethod(owner, returnType);
    }

    /**
     * 参数个数匹配、且参数类型里同时包含 [first] 与 [second] 的方法；命中多个返回 null。
     *
     * 给「返回类型是协程句柄、无法用返回值判断，但参数里有宿主数据对象」的入口方法用。
     */
    @Nullable
    public static Method uniqueMethodContaining(
            @NonNull Class<?> owner,
            int parameterCount,
            @NonNull Class<?> first,
            @NonNull Class<?> second) {
        Method found = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (method.isBridge() || method.isSynthetic()) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length != parameterCount) continue;
            boolean hasFirst = false;
            boolean hasSecond = false;
            for (Class<?> type : types) {
                if (type == first) hasFirst = true;
                if (type == second) hasSecond = true;
            }
            if (!hasFirst || !hasSecond) continue;
            if (found != null) return null;
            found = method;
        }
        return found;
    }

    /**
     * 返回类型、参数个数都匹配，且参数类型里包含 [requiredParam] 的方法；命中多个返回 null。
     *
     * 用于「参数列表里插了新参数、下标不确定，但个数与关键类型没变」的宿主方法，
     * 例如小爱 8.3.30 给记忆卡片方法加了 List 参数，场景对象的位置与个数仍然稳定。
     */
    @Nullable
    public static Method uniqueMethodReturning(
            @NonNull Class<?> owner,
            @NonNull Class<?> returnType,
            int parameterCount,
            @NonNull Class<?> requiredParam) {
        Method found = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (method.isBridge() || method.isSynthetic()) continue;
            if (method.getReturnType() != returnType) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length != parameterCount) continue;
            boolean contains = false;
            for (Class<?> type : types) {
                if (type == requiredParam) {
                    contains = true;
                    break;
                }
            }
            if (!contains) continue;
            if (found != null) return null;
            found = method;
        }
        return found;
    }

    /** 只有一个参数、参数类型与返回类型都匹配的方法；命中多个返回 null。 */
    @Nullable
    public static Method uniqueSingleArgMethod(
            @NonNull Class<?> owner,
            @NonNull Class<?> parameterType,
            @Nullable Class<?> returnType) {
        return uniqueMethod(owner, returnType, parameterType);
    }

    /** 静态字段，类型匹配；命中多个返回 null。 */
    @Nullable
    public static Field uniqueStaticFieldOfType(@NonNull Class<?> owner, @NonNull Class<?> type) {
        Field found = null;
        for (Field field : owner.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            if (field.getType() != type) continue;
            if (found != null) return null;
            found = field;
        }
        return found;
    }

    /** 实例字段，类型匹配；命中多个返回 null。 */
    @Nullable
    public static Field uniqueFieldOfType(@NonNull Class<?> owner, @NonNull Class<?> type) {
        Field found = null;
        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (field.getType() != type) continue;
            if (found != null) return null;
            found = field;
        }
        return found;
    }

    // ---- 以下为「结构定位优先、混淆名兜底」的解析入口，供业务代码逐处替换内联混淆名 ----

    /**
     * 按签名定位方法，不唯一或未命中时回退到指定混淆名。
     *
     * @param fallbackName 签名定位失败时使用的混淆方法名（当前宿主版本的已知名字）。
     */
    @Nullable
    public static Method resolveMethod(
            @NonNull Class<?> owner,
            @Nullable Class<?> returnType,
            @NonNull Class<?>[] parameterTypes,
            @NonNull String fallbackName) {
        Method method = uniqueMethod(owner, returnType, parameterTypes);
        if (method != null) {
            method.setAccessible(true);
            return method;
        }
        try {
            Method m = owner.getDeclaredMethod(fallbackName, parameterTypes);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** 无参、返回类型匹配的方法；不唯一或未命中时回退到指定混淆名。 */
    @Nullable
    public static Method resolveNoArgMethod(
            @NonNull Class<?> owner,
            @NonNull Class<?> returnType,
            @NonNull String fallbackName) {
        return resolveMethod(owner, returnType, new Class<?>[0], fallbackName);
    }

    /**
     * 按类型定位实例字段，不唯一或未命中时回退到指定混淆名。
     *
     * @param fallbackName 结构定位失败时使用的混淆字段名。
     */
    @Nullable
    public static Field resolveField(
            @NonNull Class<?> owner,
            @NonNull Class<?> type,
            @NonNull String fallbackName) {
        Field field = uniqueFieldOfType(owner, type);
        if (field != null) {
            field.setAccessible(true);
            return field;
        }
        try {
            Field f = owner.getDeclaredField(fallbackName);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /** 从解析结果取成员名；解析失败时返回兜底名。用于只需名字的旧接口。 */
    @NonNull
    public static String methodName(@Nullable Method method, @NonNull String fallbackName) {
        return method != null ? method.getName() : fallbackName;
    }

    /** 从解析结果取字段名；解析失败时返回兜底名。 */
    @NonNull
    public static String fieldName(@Nullable Field field, @NonNull String fallbackName) {
        return field != null ? field.getName() : fallbackName;
    }

    private static boolean sameTypes(@NonNull Class<?>[] actual, @NonNull Class<?>[] expected) {
        if (actual.length != expected.length) return false;
        for (int index = 0; index < actual.length; index++) {
            if (actual[index] != expected[index]) return false;
        }
        return true;
    }
}
