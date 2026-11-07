package hook.HyperBackscreen.common;

public final class ThemeResourceAccess {
    private static final String THEME_MAGIC_DIR = Constants.THEME_MAGIC_DIR;
    private static final String THEME_MAGIC_USERS_DIR = Constants.THEME_MAGIC_USERS_DIR;
    /** 背屏运行目录。背屏中心按普通应用 uid 运行，目录对 other 无 r-x 时它读不到其中的 .mrc，
     *  会把收到的 widget 全部判为无效并回退系统默认壁纸。 */
    public static final String REAR_SCREEN_RUNTIME_DIR = "/data/system/theme/rearScreen";

    private ThemeResourceAccess() {
    }

    public static boolean isGrantablePath(String path) {
        return isNormalizedAbsolute(path) && (isThemeMagicPath(path) || isRearScreenRuntimePath(path));
    }

    public static boolean isThemeMagicUserPath(String path) {
        return isNormalizedAbsolute(path) && path.startsWith(THEME_MAGIC_USERS_DIR);
    }

    /**
     * 路径来自下载的主题资源 JSON，必须先挡掉 {@code ..} / {@code .} 段和重复斜杠，
     * 否则 {@code /data/system/theme_magic/users/0/../../..} 这样的路径能绕过前缀判断。
     */
    static boolean isNormalizedAbsolute(String path) {
        if (path == null || !path.startsWith("/") || path.indexOf('\u0000') >= 0) return false;
        if (path.contains("//")) return false;
        for (String segment : path.split("/")) {
            if (segment.equals("..") || segment.equals(".")) return false;
        }
        return true;
    }

    private static boolean isThemeMagicPath(String path) {
        return path != null && (path.equals(THEME_MAGIC_DIR) || path.startsWith(THEME_MAGIC_DIR + "/"));
    }

    private static boolean isRearScreenRuntimePath(String path) {
        return path != null && (path.equals(REAR_SCREEN_RUNTIME_DIR)
                || path.startsWith(REAR_SCREEN_RUNTIME_DIR + "/"));
    }
}
