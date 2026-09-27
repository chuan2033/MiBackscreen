package hook.HyperBackscreen.common;

public final class Constants {
    public static final String MODULE_PACKAGE = "hook.HyperBackscreen";
    public static final String SYSTEM_PACKAGE = "system";
    public static final String TARGET_PACKAGE = "com.xiaomi.subscreencenter";
    public static final String VOICE_ASSIST_PACKAGE = "com.miui.voiceassist";
    public static final String PERSONAL_ASSISTANT_PACKAGE = "com.miui.personalassistant";
    public static final String ACTION_REFRESH_PICKUP_ISLAND = MODULE_PACKAGE + ".REFRESH_PICKUP_ISLAND";
    public static final String PREF_GROUP = "module_config";
    public static final String KEY_DISABLE_LONG_PRESS_EDIT = "disable_long_press_edit";
    public static final String KEY_REMOVE_WALLPAPER_LIMIT = "remove_wallpaper_limit";
    public static final String KEY_ENABLE_APP_CARD = "enable_app_card";
    public static final String KEY_REMOVE_APP_CARD_LIMIT = "remove_app_card_limit";
    public static final String KEY_FIX_REAR_SCREEN_APPLY = "fix_rear_screen_apply";
    public static final String KEY_FLOATING_NAV_BAR = "floating_nav_bar";
    public static final String KEY_LIQUID_GLASS = "liquid_glass";
    public static final String KEY_BOTTOM_BAR_BLUR = "bottom_bar_blur";
    public static final String KEY_CHECK_UPDATES = "check_updates";
    public static final String KEY_DISABLE_REAR_SCREEN_COVER = "disable_rear_screen_cover";
    public static final String KEY_DISABLE_DOUBLE_TAP_WAKE = "disable_double_tap_wake";
    public static final String KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES = "double_tap_wake_disabled_packages";
    public static final String KEY_THEME_SETTINGS_SHORTCUT = "theme_settings_shortcut";
    // 小爱记忆岛取件码增强的总开关。
    public static final String KEY_ENABLE_PICKUP = "enable_pickup";
    // 取件码页选择：记录"哪些取件码显示在记忆岛卡片上"，按驿站或无站点码组区分。
    public static final String KEY_PICKUP_ISLAND_SELECTION = "pickup_island_selection";
    public static final String PANEL_PREFERENCE_AUTHORITY = MODULE_PACKAGE + ".PanelPreferences";
    public static final String PANEL_PREFERENCE_METHOD_SET = "set_panel_preference";
    public static final String PANEL_DIAGNOSTIC_METHOD_APPEND = "append_diagnostic";
    public static final String EXTRA_PREFERENCE_VALUE = "preference_value";
    public static final String EXTRA_PREFERENCE_ACCEPTED = "preference_accepted";
    public static final String EXTRA_DIAGNOSTIC_MESSAGE = "diagnostic_message";

    // Theme store hook targets
    // 注意：以下 R8 混淆名（o5、cmzf、qp5l 等）与目标应用版本绑定，主题商店一更新即可能失效。
    public static final String THEME_STORE_PACKAGE = "com.android.thememanager";
    public static final String SYSTEM_DUAL_SCREEN_COVER_MANAGER_CLASS =
            "com.android.server.power.DualScreenCoverManager";
    public static final String SYSTEM_POWER_MANAGER_SERVICE_IMPL_CLASS =
            "com.android.server.power.PowerManagerServiceImpl";
    public static final String SYSTEM_SHOW_COVER_VIEW_METHOD = "showCoverView";
    public static final String SYSTEM_IS_SCREEN_SKIPPED_WAKEUP_METHOD = "isScreenSkippedWakeup";
    public static final String SYSTEM_POWER_MANAGER_SERVICE_IMPL_FIELD = "mPowerManagerServiceImpl";
    public static final String SYSTEM_ACTIVITY_TASK_MANAGER_FIELD = "mActivityTaskManager";
    public static final String SYSTEM_FOREGROUND_APP_PACKAGE_FIELD = "mForegroundAppPackageName";
    public static final String SYSTEM_RUNNING_TASK_TOP_ACTIVITY_FIELD = "topActivity";
    public static final String SYSTEM_RUNNING_TASK_BASE_ACTIVITY_FIELD = "baseActivity";
    public static final String SYSTEM_RUNNING_TASK_ORIG_ACTIVITY_FIELD = "origActivity";
    public static final String SYSTEM_RUNNING_TASK_REAL_ACTIVITY_FIELD = "realActivity";
    public static final String WAKE_REASON_DOUBLE_TAP = "android.policy:KEY";
    public static final String THEME_REAR_VIEWMODEL_CLASS = "com.rearScreen.viewModel.RearScreenDetailViewModel";
    // Theme Manager 11.5.3.1 (18 Pro Max)：遍历列表统计非 NFC 项后 return count < 15。
    public static final String THEME_APPLY_CHECK_METHOD_18PRO = "wt";
    // Theme Manager 11.0.8.0 (HyperOS 4)
    public static final String THEME_APPLY_CHECK_METHOD_OS4 = "yp31";
    // Theme Manager 10.9.2.0
    public static final String THEME_APPLY_CHECK_METHOD = "o5";

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
    // 11.5.3.1 起 ni7() 变成 PresetRepository getter，列表取值方法按版本依次回退。
    public static final String THEME_REAR_DATA_MANAGER_GET_LIST_METHOD_18PRO = "fu4";
    public static final String THEME_REAR_DATA_MANAGER_GET_LIST_METHOD_FALLBACK = "mcp";
    public static final String THEME_REAR_DATA_MANAGER_GET_LIST_METHOD = "ni7";
    public static final String THEME_REAR_DATA_MANAGER_UPSERT_METHOD = "jp0y";
    public static final String THEME_REAR_SETTING_ACTIVITY =
            "com.rearScreen.RearScreenSettingActivity";
    public static final String THEME_ENTRY_CONFIG_COMPANION_CLASS =
            "com.rearScreen.subscreen.EntryConfig$Companion";
    public static final String THEME_REAR_SCREEN_AI_APP_CONTROLLER_CLASS =
            "com.rearScreen.subscreen.RearScreenAiAppController";
    public static final String THEME_REAR_SCREEN_SETTING_MODULE_COMPANION_CLASS =
            "com.personalizedEditor.helper.settings.RearScreenSettingModule$Companion";
    public static final String THEME_REAR_SCREEN_AI_APP_ACTIVITY =
            "com.rearScreen.aiapp.activity.RearScreenAiAppListActivity";
    public static final String THEME_AI_REAR_SCREEN_LIST_ACTIVITY =
            "com.android.thememanager.activity.ai.AiRearScreenListActivity";
    public static final String THEME_PARAM_INTERCEPTOR_CLASS =
            "com.android.thememanager.basemodule.network.theme.interceptors.ParamInterceptor";
    public static final String THEME_NETWORK_HELPER_CLASS =
            "com.android.thememanager.controller.online.NetworkHelper";
    public static final String THEME_ONLINE_SERVICE_CLASS =
            "com.android.thememanager.controller.online.OnlineService";
    public static final String THEME_REQUEST_URL_CLASS =
            "com.android.thememanager.controller.online.RequestUrl";
    public static final String THEME_NETWORK_REQUEST_CLASS = "okhttp3.Request";
    public static final String THEME_NETWORK_REWRITE_METHOD = "y";
    public static final String THEME_AI_APP_PAGE_PATH = "/native/page/v3/AI_GENERATED_APP";
    public static final String THEME_AI_APP_SUBJECT_PATH = "/native/page/v3/subjects/";
    public static final String THEME_AI_APP_DETAIL_PATH = "/native/page/v3/theme/";
    public static final String THEME_DOWNLOAD_PATH = "/download/v2/";
    public static final String THEME_REAR_DEVICE = "madrid";
    public static final String THEME_REAR_MODEL = "Xiaomi 18 Pro Max";
    public static final String THEME_REAR_VERSION = "17_OS4.0.9.0.XEOCNXM";
    public static final String THEME_DEVICE_UTILS_CLASS =
            "com.android.thememanager.basemodule.utils.DeviceUtils";
    public static final String THEME_AI_WALLPAPER_UTILS_COMPANION_CLASS =
            "com.android.thememanager.utils.AIWallpaperUtils$Companion";
    public static final String THEME_AI_WALLPAPER_FILTER_METHOD = "o1t";
    public static final String THEME_USER_GUIDE_CONTROLLER_CLASS =
            "com.rearScreen.subscreen.UserGuideController";
    public static final String THEME_REAR_SETTING_ADAPTER_CLASS =
            "com.rearScreen.adapter.RearScreenSettingAdapter";
    // 11.5.3.1 (18 Pro Max)
    public static final String THEME_REAR_SETTING_ADAPTER_DATA_FIELD_18PRO = "s";
    public static final String THEME_REAR_SETTING_ADAPTER_DATA_FIELD = "f57409a";
    // 11.5.3.1 (18 Pro Max)：标题字段 o1t，入口 Intent 字段 fu4。
    public static final String THEME_BASE_CONTROLLER_TITLE_FIELD_18PRO = "o1t";
    public static final String THEME_USER_GUIDE_INTENT_FIELD_18PRO = "fu4";
    public static final String THEME_BASE_CONTROLLER_TITLE_FIELD = "f58418q";
    public static final String THEME_USER_GUIDE_INTENT_FIELD = "f58443t8r";

    // 主题权限文件目录：优先读混淆字段，失败则回落到固定路径
    public static final String THEME_RESOURCE_CONSTANTS_CLASS =
            "com.android.thememanager.basemodule.resource.constants.ThemeResourceConstants";
    // Theme Manager 11.0.8.0 (HyperOS 4): ThemeApplicationConstants.lsos 的转发字段。
    public static final String THEME_RIGHTS_DIR_FIELD_OS4 = "ol";
    public static final String THEME_RIGHTS_DIR_FIELD_PRIMARY = "cmzf";
    public static final String THEME_RIGHTS_DIR_FIELD_FALLBACK = "qp5l";
    public static final String THEME_RIGHTS_DIR_DEFAULT = "/data/system/theme/rights/";
    public static final String THEME_MAGIC_REAR_SCREEN_DIR =
            "/data/system/theme_magic/users/0/rearScreen";
    public static final String THEME_MAGIC_AI_APP_DIR =
            "/data/system/theme_magic/users/0/rearScreenAiApp_Theme";
    public static final String THEME_AI_APP_RUNTIME_FILE =
            THEME_MAGIC_AI_APP_DIR + "/runtimeAiApp.json";
    public static final String SUBSCREEN_APP_INFO_FILE =
            "/data/system/theme_magic/users/0/subscreencenter/config/appInfo.json";
    public static final long AI_MATE_BLANK_SNAPSHOT_MAX_BYTES = 256L * 1024L;
    public static final String AI_MATE_PEEKO_RES_ID =
            "317d9477-3731-4636-b526-15f914647826";
    public static final String AI_MATE_PEEKO_PREVIEW_PATH =
            "/product/etc/precust_theme/theme/.data/preview/theme/8e2cd3b6-63d6-47df-995b-f56914acaf43/preview_rearscreen_0.png";
    public static final String AI_MATE_LUMI_RES_ID =
            "b4deb767-f817-4376-b965-dd037e21953b";
    public static final String AI_MATE_LUMI_PREVIEW_PATH =
            "/product/etc/precust_theme/theme/.data/preview/theme/9374c6eb-7742-496e-8180-683e9da0e9ea/preview_rearscreen_0.png";

    // Z1.t: old gesture class
    public static final String HOOK_CLASS = "Z1.t";
    // Z1.v: subscreencenter RELEASE-1.0.2605272226 gesture class
    public static final String HOOK_CLASS_LONG_PRESS_NEW = "Z1.v";
    // k2.s: subscreencenter RELEASE-1.0.2607201627 gesture class (HyperOS 4)
    public static final String HOOK_CLASS_LONG_PRESS_OS4 = "k2.s";
    // U1.C: subscreencenter RELEASE-1.0.2609181659 (18 Pro Max) 长按手势类。
    // 该版本 R8 把 k2.s 让给了背屏设置页类，长按处理器换成 U1.C（持有 MainPanel + Handler）。
    public static final String HOOK_CLASS_LONG_PRESS_18PRO = "U1.C";

    public static final String HOOK_METHOD_GATE = "e";
    public static final String HOOK_METHOD_GATE_NEW = "g";
    public static final String HOOK_METHOD_LONG_PRESS_TOUCH_NEW = "f";
    public static final String HOOK_METHOD_RUN = "run";

    // 背屏长按切换只保存 user_select，不会更新系统设置页读取的 theme_rear_widget。
    // 这些均为 subscreencenter 的原始 R8 名称（不是 JADX 展示别名）。
    public static final String HOOK_CLASS_MAIN_PANEL = "com.xiaomi.subscreencenter.MainPanel";
    // v(false) 表示用户确认退出编辑模式；v(true) 表示暂停/AOD 等场景取消编辑。
    public static final String HOOK_METHOD_REQUEST_EXIT_EDIT = "v";
    // RELEASE-1.0.2609181659：onPause / onAodStateChanged 改为调用 w(Z)。
    public static final String HOOK_METHOD_REQUEST_EXIT_EDIT_18PRO = "w";
    public static final String HOOK_METHOD_SAVE_USER_SELECTION = "I";
    // RELEASE-1.0.2609181659：写 user_select 的方法从 I() 变为 J()。
    public static final String HOOK_METHOD_SAVE_USER_SELECTION_18PRO = "J";
    // RELEASE-1.0.2605272226
    public static final String HOOK_FIELD_WIDGET_LIST = "i";
    public static final String HOOK_FIELD_SELECTED_INDEX = "l";
    public static final String HOOK_FIELD_PREVIEW_INDEX = "k";
    // RELEASE-1.0.2607201627 (HyperOS 4)
    public static final String HOOK_FIELD_WIDGET_LIST_OS4 = "j";
    public static final String HOOK_FIELD_SELECTED_INDEX_OS4 = "m";
    public static final String HOOK_FIELD_PREVIEW_INDEX_OS4 = "l";
    public static final String HOOK_FIELD_WIDGET_BEAN = "c";
    public static final String HOOK_FIELD_WIDGET_ID = "a";
    public static final String SECURE_THEME_REAR_WIDGET = "theme_rear_widget";
    public static final String SECURE_SUBSCREEN_APP_WIDGET_ENABLE = "subscreen_app_widget_enable";
    public static final String SUBSCREEN_LAUNCHER_ACTIVITY_CLASS =
            "com.xiaomi.subscreencenter.SubScreenLauncher";

    // 背屏应用卡总闸：18 Pro 包会通过这些目标判断/隐藏应用卡运行时。
    public static final String SUBSCREEN_DEVICE_CONFIG_CLASS = "o2.j";
    public static final String SUBSCREEN_DEVICE_CONFIG_APP_WIDGET_METHOD = "d";
    public static final String SUBSCREEN_GUIDE_SETTINGS_CLASS = "k2.s";
    public static final String SUBSCREEN_GUIDE_HIDDEN_METHOD = "h0";
    public static final String SUBSCREEN_APP_CARD_GUIDE_KEY = "app_card";
    public static final String SUBSCREEN_LAUNCHER_PANEL_GESTURE_CLASS = "Z1.f";
    public static final String SUBSCREEN_LAUNCHER_PANEL_GESTURE_METHOD = "g";
    // LauncherPanelGestureImpl.g 的第 0 条指令读该字段：false 时直接打
    // "precheck: app widget disabled" 并返回，应用卡运行时因此从不启动。
    public static final String SUBSCREEN_LAUNCHER_PANEL_WIDGET_ENABLED_FIELD = "F";

    // 背屏应用卡数量上限：SubScreenService 的 Binder 在 insertAppWidget 交易里以 15 判定，
    // 超限直接回 -2，客户端随即提示「应用卡数量已达上限」。
    public static final String SUBSCREEN_SERVICE_BINDER_CLASS = "T1.c";
    public static final String SUBSCREEN_APP_LIST_MANAGER_CLASS = "e2.k";
    public static final String SUBSCREEN_APP_LIST_GETTER_METHOD = "c";
    public static final String SUBSCREEN_APP_LIST_DISPATCH_METHOD = "d";
    public static final String SUBSCREEN_APP_LIST_SAVE_METHOD = "e";
    public static final String SUBSCREEN_ON_TRANSACT_METHOD = "onTransact";
    public static final int SUBSCREEN_INSERT_APP_WIDGET_TRANSACTION = 11;
    public static final int SUBSCREEN_APP_CARD_LIMIT = 15;

    // 背屏上滑面板里的模块条目。面板条目是 e2.a，由 e2.k 单例持有：
    // a=名称、c=多语言名称表、e=图标路径、g/h=明暗预览图、l=bindApp、k=appCardType、m=Intent。
    // e2.l.onClick 对 k != 3 的条目直接 startActivity(m)，所以条目自己就能带点击行为。
    public static final String SUBSCREEN_LAUNCHER_ENTRY_CLASS = "e2.a";
    public static final String SUBSCREEN_LAUNCHER_ENTRY_NAME_FIELD = "a";
    public static final String SUBSCREEN_LAUNCHER_ENTRY_NAME_MAP_FIELD = "c";
    public static final String SUBSCREEN_LAUNCHER_ENTRY_ICON_FIELD = "e";
    public static final String SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_LIGHT_FIELD = "g";
    public static final String SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_DARK_FIELD = "h";
    public static final String SUBSCREEN_LAUNCHER_ENTRY_BIND_APP_FIELD = "l";
    public static final String SUBSCREEN_LAUNCHER_ENTRY_CARD_TYPE_FIELD = "k";
    public static final String SUBSCREEN_LAUNCHER_ENTRY_INTENT_FIELD = "m";
    // 条目点击由 e2.o.g(...)（onBindViewHolder）每次绑定 new 出来的 e2.l 处理，
    // e2.l.b 就是绑定的 e2.a 条目。
    public static final String SUBSCREEN_LAUNCHER_HOLDER_CLASS = "e2.l";
    public static final String SUBSCREEN_LAUNCHER_HOLDER_ITEM_FIELD = "b";
    public static final String SUBSCREEN_LAUNCHER_HOLDER_CLICK_METHOD = "onClick";
    // 0 只是"不等于 3"：k == 3 会走引导/NFC 分支而不是启动 Intent。
    public static final int SUBSCREEN_LAUNCHER_ENTRY_ACTIVITY_CARD_TYPE = 0;
    // 图标与预览图都是 Glide 按路径加载的，跨包读不到模块资源，
    // 所以模块图标先渲染成 PNG 落到宿主 cache 目录再引用。尺寸对齐官方卡片资源：
    // app_icon 为满幅不透明正方形（裁成圆形后是实心圆），preview 为整块背屏 976×596。
    public static final int SUBSCREEN_LAUNCHER_ENTRY_ICON_SIZE = 240;
    public static final int SUBSCREEN_LAUNCHER_ENTRY_ICON_BACKGROUND = 0xFFFFFFFF;
    public static final int SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_WIDTH = 976;
    public static final int SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_HEIGHT = 596;
    public static final int SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_LIGHT_BACKGROUND = 0xFFF3F3F7;
    public static final int SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_DARK_BACKGROUND = 0xFF2B2B2F;
    // 预览图里图标占短边的比例。
    public static final float SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_ICON_RATIO = 0.62f;
    public static final String PERSONAL_ASSISTANT_COMMON_PARAMS_CLASS =
            "com.miui.personalassistant.network.util.a";
    public static final String PERSONAL_ASSISTANT_ENVIRONMENT_SIGNAL_METHOD = "a";
    public static final String PERSONAL_ASSISTANT_STORE_REPOSITORY_CLASS =
            "com.miui.personalassistant.backscreen.store.data.repository.BackScreenStoreRepository";
    public static final String PERSONAL_ASSISTANT_STORE_CONVERT_METHOD = "a";
    public static final String PERSONAL_ASSISTANT_STORE_RESPONSE_CLASS =
            "com.miui.personalassistant.backscreen.store.model.BackScreenStorePageResponse";
    public static final String PERSONAL_ASSISTANT_PRESET_PARSER_CLASS = "s5.a";
    public static final String PERSONAL_ASSISTANT_PRESET_PARSE_METHOD = "b";
    public static final String PERSONAL_ASSISTANT_REAR_DEVICE = "madrid";
    public static final String PERSONAL_ASSISTANT_REAR_MODEL = "Xiaomi 18 Pro Max";

    // logcat 过滤用：旧名 RearScreenLongPressToggle 仅覆盖长按开关，已不符实际功能范围
    public static final String LOG_TAG = "MiBackscreen";

    private Constants() {
    }
}
