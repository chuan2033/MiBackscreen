package hook.HyperBackscreen.common;

public final class Constants {
    public static final String MODULE_PACKAGE = "hook.HyperBackscreen";
    public static final String TARGET_PACKAGE = "com.xiaomi.subscreencenter";
    public static final String PREF_GROUP = "module_config";
    public static final String KEY_DISABLE_LONG_PRESS_EDIT = "disable_long_press_edit";
    public static final String KEY_REMOVE_WALLPAPER_LIMIT = "remove_wallpaper_limit";
    public static final String KEY_FIX_REAR_SCREEN_APPLY = "fix_rear_screen_apply";
    public static final String KEY_FLOATING_NAV_BAR = "floating_nav_bar";
    public static final String KEY_LIQUID_GLASS = "liquid_glass";

    // Theme store hook targets
    // 注意：以下 R8 混淆名（o5、cmzf、qp5l 等）与目标应用版本绑定，主题商店一更新即可能失效。
    public static final String THEME_STORE_PACKAGE = "com.android.thememanager";
    public static final String THEME_REAR_VIEWMODEL_CLASS = "com.rearScreen.viewModel.RearScreenDetailViewModel";
    public static final String THEME_APPLY_CHECK_METHOD = "o5";

    // 背屏资源应用流程（修复应用失败）
    public static final String THEME_APPLY_RESULT_CLASS =
            "com.rearScreen.manager.RearScreenResOperationHelper$Companion$apply$applyResult$1";
    public static final String THEME_APPLY_RESULT_METHOD = "invokeSuspend";
    public static final String THEME_APPLY_BEAN_FIELD = "$bean";

    // 主题权限文件目录：优先读混淆字段，失败则回落到固定路径
    public static final String THEME_RESOURCE_CONSTANTS_CLASS =
            "com.android.thememanager.basemodule.resource.constants.ThemeResourceConstants";
    public static final String THEME_RIGHTS_DIR_FIELD_PRIMARY = "cmzf";
    public static final String THEME_RIGHTS_DIR_FIELD_FALLBACK = "qp5l";
    public static final String THEME_RIGHTS_DIR_DEFAULT = "/data/system/theme/rights/";

    // Z1.t: old gesture class
    public static final String HOOK_CLASS = "Z1.t";
    // Z1.v: new gesture class
    public static final String HOOK_CLASS_LONG_PRESS_NEW = "Z1.v";

    public static final String HOOK_METHOD_GATE = "e";
    public static final String HOOK_METHOD_GATE_NEW = "g";
    public static final String HOOK_METHOD_LONG_PRESS_TOUCH_NEW = "f";
    public static final String HOOK_METHOD_RUN = "run";

    // logcat 过滤用：旧名 RearScreenLongPressToggle 仅覆盖长按开关，已不符实际功能范围
    public static final String LOG_TAG = "MiBackscreen";

    private Constants() {
    }
}
