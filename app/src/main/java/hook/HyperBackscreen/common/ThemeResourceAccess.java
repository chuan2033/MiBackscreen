package hook.HyperBackscreen.common;

public final class ThemeResourceAccess {
    private static final String THEME_MAGIC_DIR = "/data/system/theme_magic";
    private static final String THEME_MAGIC_USERS_DIR = THEME_MAGIC_DIR + "/users/";
    /** 背屏运行目录。背屏中心按普通应用 uid 运行，目录对 other 无 r-x 时它读不到其中的 .mrc，
     *  会把收到的 widget 全部判为无效并回退系统默认壁纸。 */
    public static final String REAR_SCREEN_RUNTIME_DIR = "/data/system/theme/rearScreen";

    private ThemeResourceAccess() {
    }

    public static boolean isGrantablePath(String path) {
        return isThemeMagicPath(path) || isRearScreenRuntimePath(path);
    }

    public static boolean isThemeMagicUserPath(String path) {
        return path != null && path.startsWith(THEME_MAGIC_USERS_DIR);
    }

    private static boolean isThemeMagicPath(String path) {
        return path != null && (path.equals(THEME_MAGIC_DIR) || path.startsWith(THEME_MAGIC_DIR + "/"));
    }

    private static boolean isRearScreenRuntimePath(String path) {
        return path != null && (path.equals(REAR_SCREEN_RUNTIME_DIR)
                || path.startsWith(REAR_SCREEN_RUNTIME_DIR + "/"));
    }
}
