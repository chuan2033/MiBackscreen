package hook.HyperBackscreen.common;

public final class Constants {
    public static final String MODULE_PACKAGE = "hook.HyperBackscreen";
    public static final String TARGET_PACKAGE = "com.xiaomi.subscreencenter";
    public static final String MAIN_PANEL_CLASS = "com.xiaomi.subscreencenter.MainPanel";
    public static final String PREF_GROUP = "module_config";
    public static final String ACTION_SETTING_CHANGED = "com.backscreen.helper.ACTION_SETTING_CHANGED";
    public static final String EXTRA_SETTING_KEY = "setting_key";
    public static final String EXTRA_SETTING_VALUE = "setting_value";
    public static final String KEY_DISABLE_LONG_PRESS_EDIT = "disable_long_press_edit";
    public static final String KEY_REMOVE_WALLPAPER_LIMIT = "remove_wallpaper_limit";
    public static final String KEY_FIX_REAR_SCREEN_APPLY = "fix_rear_screen_apply";
    public static final String KEY_FLOATING_NAV_BAR = "floating_nav_bar";
    public static final String KEY_LIQUID_GLASS = "liquid_glass";

    // Theme store hook targets
    public static final String THEME_STORE_PACKAGE = "com.android.thememanager";
    public static final String THEME_REAR_VIEWMODEL_CLASS = "com.rearScreen.viewModel.RearScreenDetailViewModel";
    public static final String THEME_REAR_TOOL_SERVICE_CLASS = "com.rearScreen.miclaw.RearScreenToolService";
    public static final String THEME_APPLY_CHECK_METHOD = "o5";

    // Z1.t: old gesture class
    public static final String HOOK_CLASS = "Z1.t";
    // Z1.v: new gesture class
    public static final String HOOK_CLASS_LONG_PRESS_NEW = "Z1.v";

    public static final String HOOK_METHOD_GATE = "e";
    public static final String HOOK_METHOD_GATE_NEW = "g";
    public static final String HOOK_METHOD_LONG_PRESS_TOUCH_NEW = "f";
    public static final String HOOK_METHOD_RUN = "run";

    public static final String LOG_TAG = "RearScreenLongPressToggle";

    private Constants() {
    }
}
