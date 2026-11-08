package hook.HyperBackscreen.hook.subscreen;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import hook.HyperBackscreen.common.PackageListCodec;

/**
 * 背屏音乐控件白名单的纯逻辑：解析用户名单（按原始串缓存，避免每次判定都重新解析）、
 * 判定单个包、把用户名单并入宿主返回的白名单集合。
 *
 * <p>名单为空时所有方法都等价于「完全放行原逻辑」：判定不改、集合原样返回。
 */
public final class MusicWidgetWhitelist {
    private volatile String cachedRaw;
    private volatile Set<String> cachedPackages = Collections.emptySet();

    /** 解析后的用户名单；原始串没变时直接复用上一次的结果。 */
    @NonNull
    public Set<String> packages(@Nullable String raw) {
        String value = raw == null ? "" : raw;
        if (value.equals(cachedRaw)) return cachedPackages;
        Set<String> parsed = Collections.unmodifiableSet(PackageListCodec.parse(value));
        cachedPackages = parsed;
        cachedRaw = value;
        return parsed;
    }

    /** 包名在用户名单里才返回 true；不在名单里交给宿主原逻辑判断。 */
    public static boolean isExtraMusicApp(@NonNull Set<String> extra, @Nullable Object packageName) {
        return packageName instanceof String && !extra.isEmpty() && extra.contains(packageName);
    }

    /**
     * 把用户名单并入宿主 b() 的返回值。返回新的 HashSet，不改宿主原集合；
     * 名单为空、或返回值不是 HashSet（宿主改了实现）时原样返回。
     */
    @Nullable
    public static Object merge(@Nullable Object original, @NonNull Set<String> extra) {
        if (extra.isEmpty()) return original;
        if (original == null) return new HashSet<>(extra);
        if (!(original instanceof HashSet)) return original;
        @SuppressWarnings("unchecked")
        HashSet<Object> merged = new HashSet<>((HashSet<Object>) original);
        merged.addAll(extra);
        return merged;
    }
}
