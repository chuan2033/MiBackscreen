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
    public static final String KEY_ENABLE_SWIPE_PANEL = "enable_swipe_panel";
    public static final String PANEL_PREFERENCE_AUTHORITY = MODULE_PACKAGE + ".PanelPreferences";
    public static final String PANEL_PREFERENCE_METHOD_SET = "set_panel_preference";
    public static final String PANEL_DIAGNOSTIC_METHOD_APPEND = "append_diagnostic";
    public static final String EXTRA_PREFERENCE_VALUE = "preference_value";
    public static final String EXTRA_PREFERENCE_ACCEPTED = "preference_accepted";
    public static final String EXTRA_DIAGNOSTIC_MESSAGE = "diagnostic_message";

    // Theme store hook targets
    // 注意：以下 R8 混淆名（o5、cmzf、qp5l 等）与目标应用版本绑定，主题商店一更新即可能失效。
    public static final String THEME_STORE_PACKAGE = "com.android.thememanager";
    public static final String THEME_REAR_VIEWMODEL_CLASS = "com.rearScreen.viewModel.RearScreenDetailViewModel";
    // Theme Manager 10.9.2.0
    public static final String THEME_APPLY_CHECK_METHOD = "o5";
    // Theme Manager 11.0.8.0 (HyperOS 4)
    public static final String THEME_APPLY_CHECK_METHOD_OS4 = "yp31";

    // 背屏资源应用流程（修复应用失败）
    public static final String THEME_APPLY_RESULT_CLASS =
            "com.rearScreen.manager.RearScreenResOperationHelper$Companion$apply$applyResult$1";
    public static final String THEME_APPLY_RESULT_METHOD = "invokeSuspend";
    public static final String THEME_APPLY_BEAN_FIELD = "$bean";

    // 主题商店在重新应用“我的背屏”中的已有壁纸时不会提升 position，导致系统设置页仍预览旧的首项。
    // 以下目标用于读取当前列表，并在原应用流程落库前把当前项移到首位。
    public static final String THEME_REAR_DATA_MANAGER_CLASS =
            "com.rearScreen.manager.RearListDataManager";
    public static final String THEME_REAR_DATA_MANAGER_COMPANION_FIELD = "s";
    public static final String THEME_REAR_DATA_MANAGER_GET_INSTANCE_METHOD = "k";
    public static final String THEME_REAR_DATA_MANAGER_GET_LIST_METHOD = "ni7";
    public static final String THEME_REAR_DATA_MANAGER_UPSERT_METHOD = "jp0y";
    public static final String THEME_REAR_SETTING_ACTIVITY =
            "com.rearScreen.RearScreenSettingActivity";

    // 主题权限文件目录：优先读混淆字段，失败则回落到固定路径
    public static final String THEME_RESOURCE_CONSTANTS_CLASS =
            "com.android.thememanager.basemodule.resource.constants.ThemeResourceConstants";
    // Theme Manager 11.0.8.0 (HyperOS 4): ThemeApplicationConstants.lsos 的转发字段。
    public static final String THEME_RIGHTS_DIR_FIELD_OS4 = "ol";
    public static final String THEME_RIGHTS_DIR_FIELD_PRIMARY = "cmzf";
    public static final String THEME_RIGHTS_DIR_FIELD_FALLBACK = "qp5l";
    public static final String THEME_RIGHTS_DIR_DEFAULT = "/data/system/theme/rights/";

    // Z1.t: old gesture class
    public static final String HOOK_CLASS = "Z1.t";
    // Z1.v: subscreencenter RELEASE-1.0.2605272226 gesture class
    public static final String HOOK_CLASS_LONG_PRESS_NEW = "Z1.v";
    // k2.s: subscreencenter RELEASE-1.0.2607201627 gesture class (HyperOS 4)
    public static final String HOOK_CLASS_LONG_PRESS_OS4 = "k2.s";

    public static final String HOOK_METHOD_GATE = "e";
    public static final String HOOK_METHOD_GATE_NEW = "g";
    public static final String HOOK_METHOD_LONG_PRESS_TOUCH_NEW = "f";
    public static final String HOOK_METHOD_RUN = "run";

    // 背屏长按切换只保存 user_select，不会更新系统设置页读取的 theme_rear_widget。
    // 这些均为 subscreencenter 的原始 R8 名称（不是 JADX 展示别名）。
    public static final String HOOK_CLASS_MAIN_PANEL = "com.xiaomi.subscreencenter.MainPanel";
    public static final String HOOK_METHOD_SAVE_USER_SELECTION = "I";
    // RELEASE-1.0.2605272226
    public static final String HOOK_FIELD_WIDGET_LIST = "i";
    public static final String HOOK_FIELD_SELECTED_INDEX = "l";
    // RELEASE-1.0.2607201627 (HyperOS 4)
    public static final String HOOK_FIELD_WIDGET_LIST_OS4 = "j";
    public static final String HOOK_FIELD_SELECTED_INDEX_OS4 = "m";
    public static final String HOOK_FIELD_WIDGET_BEAN = "c";
    public static final String HOOK_FIELD_WIDGET_ID = "a";
    public static final String SECURE_THEME_REAR_WIDGET = "theme_rear_widget";

    // 背屏上滑面板：手势挂在背屏主 Activity 上（manifest 中的清晰类名，相对稳定但仍受版本影响）
    public static final String HOOK_CLASS_SUBSCREEN_LAUNCHER = "com.xiaomi.subscreencenter.SubScreenLauncher";
    // 从屏幕 70% 高度以下起手；32dp 可在 MIUI 底部手势窗口抢走事件前完成识别。
    public static final float GESTURE_BOTTOM_EDGE_RATIO = 0.7f;
    public static final float GESTURE_SWIPE_UP_DP = 32f;
    public static final long GESTURE_TRIGGER_COOLDOWN_MS = 800L;

    // logcat 过滤用：旧名 RearScreenLongPressToggle 仅覆盖长按开关，已不符实际功能范围
    public static final String LOG_TAG = "MiBackscreen";

    private Constants() {
    }
}
