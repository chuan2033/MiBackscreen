package hook.HyperBackscreen.common;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 背屏桌面的纯逻辑：密度档位、会话现场的编码，模块 App 与 system_server 共用。
 *
 * <p>背屏 976×596、450 dpi，左侧 296 px 留给摄像头，应用可用区域只有约 241×212 dp，
 * 多数应用按 320 dp 以上设计。会话期间把背屏密度调低，让应用拿到更宽的布局；会话结束恢复原值。
 */
public final class RearDesktopPolicy {
    /** 不调整背屏密度。 */
    public static final int DENSITY_UNCHANGED = 0;
    /** 可选档位，按应用可用宽度从窄到宽排列；0 表示不调整。 */
    public static final int[] DENSITY_OPTIONS = {DENSITY_UNCHANGED, 320, 280, 240};
    public static final int DEFAULT_DENSITY = 280;
    /** 会话现场里「会话开始前没有强制密度」的取值。 */
    public static final int NO_FORCED_DENSITY = -1;

    private RearDesktopPolicy() {
    }

    /** 偏好值 → dpi；不认识的值按默认档位。 */
    public static int parseDensity(@Nullable String value) {
        Integer parsed = matchOption(value);
        return parsed == null ? DEFAULT_DENSITY : parsed;
    }

    /** 备份恢复时校验：合法档位返回规范字符串，否则 null。 */
    @Nullable
    public static String normalizeDensity(@Nullable String value) {
        Integer parsed = matchOption(value);
        return parsed == null ? null : String.valueOf(parsed);
    }

    @Nullable
    private static Integer matchOption(@Nullable String value) {
        if (value == null) return null;
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            return null;
        }
        for (int option : DENSITY_OPTIONS) {
            if (option == parsed) return parsed;
        }
        return null;
    }

    /** 请求里带的密度：只接受档位里的值（防止被传入极端值把背屏弄得不可用）。 */
    public static boolean isAcceptedDensity(int density) {
        for (int option : DENSITY_OPTIONS) {
            if (option == density) return true;
        }
        return false;
    }

    /**
     * 会话开始前背屏的设置，会话结束或开机时据此恢复。
     * 编码为「强制密度:是否忽略方向请求」，例如 {@code -1:0}。
     */
    public static final class RestoreState {
        /** 会话开始前的强制密度；{@link #NO_FORCED_DENSITY} 表示当时没有强制密度。 */
        public final int forcedDensity;
        public final boolean ignoreOrientationRequest;

        public RestoreState(int forcedDensity, boolean ignoreOrientationRequest) {
            this.forcedDensity = forcedDensity;
            this.ignoreOrientationRequest = ignoreOrientationRequest;
        }

        @NonNull
        public String encode() {
            return forcedDensity + ":" + (ignoreOrientationRequest ? 1 : 0);
        }

        /** 解析失败返回 null（按没有需要恢复的现场处理）。 */
        @Nullable
        public static RestoreState decode(@Nullable String value) {
            if (value == null) return null;
            String[] parts = value.trim().split(":");
            if (parts.length != 2) return null;
            try {
                int density = Integer.parseInt(parts[0]);
                int ignore = Integer.parseInt(parts[1]);
                if (density != NO_FORCED_DENSITY && density <= 0) return null;
                if (ignore != 0 && ignore != 1) return null;
                return new RestoreState(density, ignore == 1);
            } catch (NumberFormatException error) {
                return null;
            }
        }
    }

    /** 从基础密度与初始密度判断会话开始前是否已有强制密度。 */
    public static int forcedDensityOf(int baseDensity, int initialDensity) {
        return baseDensity > 0 && baseDensity != initialDensity ? baseDensity : NO_FORCED_DENSITY;
    }
}
