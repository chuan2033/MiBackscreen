package hook.HyperBackscreen.hook.target;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;

/**
 * 背屏中心音乐控件判定类（当前混淆名 m2.a / A2.a）的方法形状校验。
 *
 * <p>DexKit 只按字符串「unified.music」「music」初筛，同时引用这两个常量的类不止一个
 * （调用方 SmartAssistantManager 一带也会用到），所以命中后还要按形状确认：
 * <ul>
 *   <li>唯一的 {@code static boolean x(String)}：判断某个包是不是音乐应用；</li>
 *   <li>唯一的 {@code static HashSet x()}：返回交给 MAML 音乐控件的白名单。</li>
 * </ul>
 * 同类里另有 {@code a(String): String}、{@code d(String, String): boolean}，返回值或参数个数不同，
 * 不会干扰判定。任何一项不唯一都视为不匹配，宁可不装也不挂错方法。
 */
public final class MusicWhitelistShape {
    private MusicWhitelistShape() {
    }

    /** 唯一的 static (String) boolean；没有或不止一个返回 null。 */
    @Nullable
    public static Method memberCheckMethod(@NonNull Class<?> owner) {
        return uniqueStatic(owner, boolean.class, String.class);
    }

    /** 唯一的 static () HashSet；没有或不止一个返回 null。 */
    @Nullable
    public static Method whitelistMethod(@NonNull Class<?> owner) {
        return uniqueStatic(owner, HashSet.class);
    }

    /** 两个方法都能唯一定位才算是目标类。 */
    public static boolean matches(@Nullable Class<?> owner) {
        if (owner == null) return false;
        try {
            return memberCheckMethod(owner) != null && whitelistMethod(owner) != null;
        } catch (Throwable ignored) {
            // getDeclaredMethods 可能因宿主依赖缺失抛 NoClassDefFoundError，按不匹配处理。
            return false;
        }
    }

    @Nullable
    private static Method uniqueStatic(
            @NonNull Class<?> owner,
            @NonNull Class<?> returnType,
            @NonNull Class<?>... parameterTypes) {
        Method found = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (method.isBridge() || method.isSynthetic()) continue;
            if (!Modifier.isStatic(method.getModifiers())) continue;
            if (method.getReturnType() != returnType) continue;
            Class<?>[] actual = method.getParameterTypes();
            if (actual.length != parameterTypes.length) continue;
            boolean same = true;
            for (int i = 0; i < actual.length; i++) {
                if (actual[i] != parameterTypes[i]) {
                    same = false;
                    break;
                }
            }
            if (!same) continue;
            if (found != null) return null;
            found = method;
        }
        if (found != null) found.setAccessible(true);
        return found;
    }
}
