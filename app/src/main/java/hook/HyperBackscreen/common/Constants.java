package hook.HyperBackscreen.common;

public final class Constants {
    public static final String MODULE_PACKAGE = "hook.HyperBackscreen";
    public static final String SYSTEM_PACKAGE = "system";
    public static final String TARGET_PACKAGE = "com.xiaomi.subscreencenter";
    public static final String PERSONAL_ASSISTANT_PACKAGE = "com.miui.personalassistant";
    public static final String PREF_GROUP = "module_config";
    public static final String KEY_DISABLE_LONG_PRESS_EDIT = "disable_long_press_edit";
    public static final String KEY_REMOVE_WALLPAPER_LIMIT = "remove_wallpaper_limit";
    public static final String KEY_ENABLE_APP_CARD = "enable_app_card";
    public static final String KEY_APP_CARD_INSTALL_REQUEST = "app_card_install_request";
    /** 每次「添加到我的应用卡」生成的一次性编号；宿主按它判断请求是否已处理过。 */
    public static final String KEY_APP_CARD_INSTALL_REQUEST_ID = "app_card_install_request_id";
    public static final String KEY_ENABLE_18_PRO_FEATURES = "enable_18_pro_features";
    public static final String KEY_ENABLE_BATTERY_RING = "enable_battery_ring";
    // 背屏电量显示颜色覆盖，格式 #AARRGGBB；空字符串表示跟随系统（SystemUI 电池资源色）。
    public static final String KEY_BATTERY_COLOR_IDLE = "battery_color_idle";
    public static final String KEY_BATTERY_COLOR_CHARGING = "battery_color_charging";
    public static final String KEY_BATTERY_COLOR_LOW = "battery_color_low";
    public static final String KEY_BATTERY_PERCENT_POSITION = "battery_percent_position";
    // 背屏电量显示的位置 / 尺寸微调，整数百分比的字符串形式；解析失败回默认值。
    // 数字偏移以摄像头模组宽（X）/ 高（Y）为基准，线条缩放 100 表示严格贴模组轮廓。
    // 历史键：只有一个水平偏移槽，切左右会互相覆盖，已被下面两个分侧键取代，只读不写。
    public static final String KEY_BATTERY_PERCENT_OFFSET_X = "battery_percent_offset_x";
    // 水平偏移按「数字在左 / 在右」分开存：两侧的可用空间与各自贴靠位置不同，
    // 共用一个槽会导致切一次就把另一侧调好的值冲掉。
    public static final String KEY_BATTERY_PERCENT_OFFSET_X_LEFT = "battery_percent_offset_x_left";
    public static final String KEY_BATTERY_PERCENT_OFFSET_X_RIGHT = "battery_percent_offset_x_right";
    public static final String KEY_BATTERY_PERCENT_OFFSET_Y = "battery_percent_offset_y";
    public static final String KEY_BATTERY_RING_SCALE = "battery_ring_scale";
    // 线条笔画粗细与数字字号，单位是背屏像素（背屏按 976x596 参考尺寸绘制，不用折算）。
    public static final String KEY_BATTERY_RING_STROKE = "battery_ring_stroke";
    public static final String KEY_BATTERY_PERCENT_TEXT_SIZE = "battery_percent_text_size";
    // 百分比数字字重，100~900（400 常规 / 700 加粗），默认 700 —— 与旧版本固定 BOLD 的观感一致。
    public static final String KEY_BATTERY_PERCENT_TEXT_WEIGHT = "battery_percent_text_weight";
    // 是否在未充部分画一圈低透明度底轨；"1" 开 / "0" 关。
    public static final String KEY_BATTERY_RING_TRACK = "battery_ring_track";
    // 电量线条样式：contour = 绕模组一整圈的连体轮廓，twin = 上下两个独立圆环。
    public static final String KEY_BATTERY_RING_STYLE = "battery_ring_style";
    /** 低于这个百分比（且未充电）时背屏电量用「低电量」颜色。 */
    public static final String KEY_BATTERY_LOW_THRESHOLD = "battery_low_threshold";
    /** 充电时沿电量线条转动的流光："1" 开（默认）/ "0" 关。 */
    public static final String KEY_BATTERY_CHARGE_SWEEP = "battery_charge_sweep";
    public static final String KEY_AUTO_WALLPAPER_INTERVAL_MINUTES =
            "auto_wallpaper_interval_minutes";
    // 定时切换的挑图方式：sequential = 按列表顺序循环，random = 每次随机且不与当前重复。
    public static final String KEY_AUTO_WALLPAPER_ORDER = "auto_wallpaper_order";
    // 背屏息屏超时覆盖。"0" = 跟随系统（不写 subscreen_display_time），其余为毫秒值。
    // 背屏（display group 1）的息屏倒计时只由系统设置 subscreen_display_time 决定
    // （默认 10 秒，游戏模式会把它改写成 30 秒并在游戏进程退出后重置回 10 秒）。
    public static final String KEY_REAR_SCREEN_OFF_TIMEOUT = "rear_screen_off_timeout";
    // 游戏（主屏高负载）时系统会主动关背屏；开启后拦掉那次主动关屏，
    // 并在游戏期间把背屏超时抬到「一直亮屏」。默认关：会让游戏功耗优化失效。
    public static final String KEY_KEEP_REAR_SCREEN_ON_IN_GAME = "keep_rear_screen_on_in_game";
    // 定时关闭背屏：在每天的时间窗内把背屏压成熄灭状态并拒绝唤醒请求，到点恢复。
    // 时间值统一存 "HH:mm"，时间窗允许跨零点（如 23:00 → 07:00）。
    public static final String KEY_REAR_SCREEN_SCHEDULE_ENABLED = "rear_screen_schedule_enabled";
    public static final String KEY_REAR_SCREEN_SCHEDULE_START = "rear_screen_schedule_start";
    public static final String KEY_REAR_SCREEN_SCHEDULE_END = "rear_screen_schedule_end";
    // 时间窗内的形态：off = 背屏直接黑屏（休眠），doze = 停在息屏（AOD 可见但不亮）。
    public static final String KEY_REAR_SCREEN_SCHEDULE_MODE = "rear_screen_schedule_mode";
    public static final String REAR_SCREEN_SCHEDULE_MODE_OFF = "off";
    public static final String REAR_SCREEN_SCHEDULE_MODE_DOZE = "doze";
    public static final String DEFAULT_REAR_SCREEN_SCHEDULE_MODE = REAR_SCREEN_SCHEDULE_MODE_OFF;
    public static final String KEY_REMOVE_APP_CARD_LIMIT = "remove_app_card_limit";
    public static final String KEY_FIX_REAR_SCREEN_APPLY = "fix_rear_screen_apply";
    // 主题商店人脸录入计数兼容。原来挂在 fix_rear_screen_apply（名字与实现不符），
    // 现已独立；未写过该键时回退读 fix_rear_screen_apply，保证老用户行为不变。
    public static final String KEY_FACE_ENROLLMENT_COMPAT = "face_enrollment_compat";
    public static final String KEY_FLOATING_NAV_BAR = "floating_nav_bar";
    public static final String KEY_LIQUID_GLASS = "liquid_glass";
    public static final String KEY_BOTTOM_BAR_STYLE = "bottom_bar_style";
    public static final String KEY_BOTTOM_BAR_BLUR = "bottom_bar_blur";
    public static final String KEY_SHOW_FUNCTION_COUNT = "show_function_count";
    public static final String KEY_CHECK_UPDATES = "check_updates";
    public static final String KEY_DISABLE_REAR_SCREEN_COVER = "disable_rear_screen_cover";
    public static final String KEY_DISABLE_DOUBLE_TAP_WAKE = "disable_double_tap_wake";
    public static final String KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES = "double_tap_wake_disabled_packages";
    /**
     * 用户追加的背屏音乐控件白名单（包名列表，PackageListCodec 编码）。名单里的应用会被
     * 背屏中心当成「支持的音乐应用」，播放时显示背屏音乐卡片。空 = 完全沿用宿主内置名单。
     */
    public static final String KEY_MUSIC_WIDGET_WHITELIST_PACKAGES = "music_widget_whitelist_packages";
    /** 背屏小部件：用户选中的 AppWidget 提供方（ComponentName 扁平串，一行一个），见 RearWidgetList。 */
    public static final String KEY_REAR_APP_WIDGETS = "rear_app_widgets";
    /**
     * 按应用禁用背屏「双击熄屏」（包名列表，PackageListCodec 编码）。与上面的双击唤醒名单
     * 互不相干：那份管「熄屏时双击不亮」，这份管「亮屏时双击不灭」。
     */
    public static final String KEY_DOUBLE_TAP_SLEEP_DISABLED_PACKAGES = "double_tap_sleep_disabled_packages";
    public static final String KEY_THEME_SETTINGS_SHORTCUT = "theme_settings_shortcut";
    /** 系统「设置」首页里的模块入口位置：off / top / middle / bottom。 */
    public static final String KEY_SETTINGS_ENTRY = "settings_entry";
    public static final String SETTINGS_PACKAGE = "com.android.settings";
    /** 系统设置首页 Activity 与它的条目列表；条目类来自 settingslib，类名与字段都不混淆。 */
    public static final String SETTINGS_MIUI_SETTINGS_CLASS = "com.android.settings.MiuiSettings";
    public static final String SETTINGS_HEADER_ADAPTER_CLASS = "com.android.settings.MiuiSettings$HeaderAdapter";
    public static final String SETTINGS_HEADER_VIEW_HOLDER_CLASS = "com.android.settings.MiuiSettings$HeaderViewHolder";
    public static final String SETTINGS_HEADER_CLASS =
            "com.android.settingslib.miuisettings.preference.PreferenceActivity$Header";
    /**
     * 背屏视频壁纸续播（背屏中心）：重开播放器时从上次的位置接着播，不再每次亮屏从头开始。
     * 视频三项都只在宿主进程启动时决定是否安装 Hook（关着就完全不挂），所以改完要重启对应作用域；
     * Hook 回调里也会现读一次，关掉后不必等重启就停止改写。
     */
    public static final String KEY_REAR_VIDEO_RESUME = "rear_video_resume";
    /** 解除背屏自定义视频的时长 / 帧率 / 分辨率限制（主题商店），HDR / 杜比 / 非 MP4、MOV 仍拒绝。 */
    public static final String KEY_REAR_VIDEO_UNLOCK_LIMITS = "rear_video_unlock_limits";
    /** 背屏视频壁纸保留声音：主题商店不再剥音轨，背屏中心按媒体音量出声（不申请音频焦点）。 */
    public static final String KEY_REAR_VIDEO_KEEP_AUDIO = "rear_video_keep_audio";
    /** 背屏实时歌词：订阅 SuperLyric，把当前歌词行显示在背屏音乐卡片的歌手行上。 */
    public static final String KEY_ENABLE_REAR_LYRIC = "enable_rear_lyric";
    /** 背屏实时歌词在 AOD（背屏息屏显示）时也显示；关着时息屏恢复原歌手名。 */
    public static final String KEY_REAR_LYRIC_IN_AOD = "rear_lyric_in_aod";
    public static final String PANEL_PREFERENCE_AUTHORITY = MODULE_PACKAGE + ".PanelPreferences";
    public static final String PANEL_PREFERENCE_METHOD_SET = "set_panel_preference";
    /** 模块 → 宿主同步广播的签名级权限：宿主注册接收器时要求发送方持有，挡住第三方应用。 */
    public static final String PERMISSION_SYNC_HOST = MODULE_PACKAGE + ".permission.SYNC_HOST";
    /** 背屏面板写字符串偏好（息屏档位、换壁纸间隔、电量环样式），键与取值都有白名单。 */
    public static final String PANEL_PREFERENCE_METHOD_SET_STRING = "set_panel_string_preference";
    public static final String ACTION_SYNC_BATTERY_RING =
            MODULE_PACKAGE + ".action.SYNC_BATTERY_RING";
    public static final String ACTION_SYNC_AUTO_WALLPAPER =
            MODULE_PACKAGE + ".action.SYNC_AUTO_WALLPAPER";
    /**
     * 背屏实时歌词开关变化：extra {@link #EXTRA_PREFERENCE_VALUE} 为新值，背屏中心即时生效。
     * {@link #EXTRA_PREFERENCE_KEY} 缺省为 {@link #KEY_ENABLE_REAR_LYRIC}（注册 / 注销订阅），
     * 也可以是 {@link #KEY_REAR_LYRIC_IN_AOD}。
     */
    public static final String ACTION_SYNC_REAR_LYRIC =
            MODULE_PACKAGE + ".action.SYNC_REAR_LYRIC";
    /** 背屏小部件列表变化：extra {@link #EXTRA_PREFERENCE_VALUE} 为新的列表串，背屏中心即时重排页面。 */
    public static final String ACTION_SYNC_REAR_WIDGETS =
            MODULE_PACKAGE + ".action.SYNC_REAR_WIDGETS";
    public static final String EXTRA_PREFERENCE_KEY = "preference_key";
    public static final String EXTRA_INTERVAL_MINUTES = "interval_minutes";
    public static final String PANEL_DIAGNOSTIC_METHOD_APPEND = "append_diagnostic";
    public static final String EXTRA_PREFERENCE_VALUE = "preference_value";
    public static final String EXTRA_PREFERENCE_ACCEPTED = "preference_accepted";
    public static final String EXTRA_DIAGNOSTIC_MESSAGE = "diagnostic_message";

    // ---- 应用投放到背屏 / 拉回主屏 ----
    // 执行者是 system_server（RearCastHost）：开机 systemReady 之后注册一个导出的接收器，
    // 只接受背屏中心与模块 App 自己发来的有序广播（发送方用 BroadcastOptions#setShareIdentityEnabled
    // 公开身份，接收方用 getSentFromPackage / getSentFromUid 校验），结果放在 resultCode / resultData。
    /** 投放请求广播；必须 setPackage({@link #SYSTEM_FRAMEWORK_PACKAGE})，只投递给 system_server。 */
    public static final String ACTION_REAR_CAST = MODULE_PACKAGE + ".action.REAR_CAST";
    /** 请求的动作，取值见下面的 REAR_CAST_OP_*。 */
    public static final String EXTRA_REAR_CAST_OP = "rear_cast_op";
    /** 主屏 → 背屏：把主屏最上层可见的普通应用任务移到背屏。 */
    public static final String REAR_CAST_OP_TO_REAR = "to_rear";
    /** 背屏 → 主屏：把背屏上最上层的被投放应用移回主屏。 */
    public static final String REAR_CAST_OP_TO_MAIN = "to_main";
    /** 切换：背屏上有被投放的应用就拉回，否则投放。 */
    public static final String REAR_CAST_OP_TOGGLE = "toggle";
    /** 只查询背屏上是否有被投放的应用，不移动任何任务。 */
    public static final String REAR_CAST_OP_QUERY = "query";
    /** system_server 动态注册的接收器归属的包名（广播 setPackage 用）。 */
    public static final String SYSTEM_FRAMEWORK_PACKAGE = "android";
    public static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    /** 主屏桌面。 */
    public static final String MAIN_LAUNCHER_PACKAGE = "com.miui.home";
    public static final int SYSTEM_MAIN_DISPLAY_ID = 0;
    /** 注册投放接收器的时机：ActivityManagerService#systemReady 返回之后。 */
    public static final String SYSTEM_ACTIVITY_MANAGER_SERVICE_CLASS =
            "com.android.server.am.ActivityManagerService";
    public static final String SYSTEM_READY_METHOD = "systemReady";
    // 结果码（有序广播 resultCode）。发送方以 NOT_HANDLED 作为初始值：
    // 原样回来说明 system_server 里没有接收器（模块未生效 / 还没重启）。
    public static final int REAR_CAST_RESULT_NOT_HANDLED = 0;
    public static final int REAR_CAST_RESULT_MOVED_TO_REAR = 1;
    public static final int REAR_CAST_RESULT_MOVED_TO_MAIN = 2;
    public static final int REAR_CAST_RESULT_NO_APP_ON_MAIN = 3;
    public static final int REAR_CAST_RESULT_NO_APP_ON_REAR = 4;
    public static final int REAR_CAST_RESULT_API_UNAVAILABLE = 5;
    public static final int REAR_CAST_RESULT_MOVE_FAILED = 6;
    public static final int REAR_CAST_RESULT_DENIED = 7;
    public static final int REAR_CAST_RESULT_BAD_REQUEST = 8;
    /** 设备处于需要凭据解锁的锁屏状态：不把主屏应用移到背屏（否则背屏会绕过锁屏显示应用内容）。 */
    public static final int REAR_CAST_RESULT_LOCKED = 9;
    /** 查询结果：背屏上有被投放的应用（resultData 为其包名）。 */
    public static final int REAR_CAST_RESULT_REAR_HAS_APP = 10;
    /** 查询结果：背屏上没有被投放的应用。 */
    public static final int REAR_CAST_RESULT_REAR_EMPTY = 11;
    /** 发送方本地超时（不经过 system_server）。 */
    public static final int REAR_CAST_RESULT_TIMEOUT = -100;

    // Theme store hook targets
    // 注意：以下 R8 混淆名（o5、cmzf、qp5l 等）与目标应用版本绑定，主题商店一更新即可能失效。
    public static final String THEME_STORE_PACKAGE = "com.android.thememanager";
    public static final String SYSTEM_DUAL_SCREEN_COVER_MANAGER_CLASS =
            "com.android.server.power.DualScreenCoverManager";
    public static final String SYSTEM_POWER_MANAGER_SERVICE_IMPL_CLASS =
            "com.android.server.power.PowerManagerServiceImpl";
    public static final String SYSTEM_SHOW_COVER_VIEW_METHOD = "showCoverView";
    public static final String SYSTEM_IS_SCREEN_SKIPPED_WAKEUP_METHOD = "isScreenSkippedWakeup";
    // 背屏息屏超时：PowerManagerServiceImpl.adjustScreenOffTimeoutIfNeededLocked(PowerGroup, long)
    // 对 groupId == 1 直接返回 mSecondaryDisplayScreenOffTimeout（来自系统设置
    // subscreen_display_time），其余组原样返回传入值。模块在这里按偏好覆盖返回值，
    // 同时把值写进该设置项做兜底 —— 两层都指向同一档位。
    public static final String SYSTEM_ADJUST_SCREEN_OFF_TIMEOUT_METHOD =
            "adjustScreenOffTimeoutIfNeededLocked";
    public static final String SYSTEM_POWER_GROUP_CLASS = "com.android.server.power.PowerGroup";
    public static final String SYSTEM_POWER_GROUP_GET_GROUP_ID_METHOD = "getGroupId";
    public static final String SYSTEM_SUBSCREEN_DISPLAY_TIME_SETTING = "subscreen_display_time";
    public static final int SYSTEM_SECONDARY_DISPLAY_GROUP_ID = 1;
    /**
     * 背屏的 {@code Display.getDisplayId()} 取值。数值与
     * {@link #SYSTEM_SECONDARY_DISPLAY_GROUP_ID} 相同（背屏在独立 display group），
     * 但分属两套 id 体系：这里比较 display id，那里比较 PowerGroup 的 group id。
     */
    public static final int SYSTEM_SECONDARY_DISPLAY_ID = 1;
    // 「一直亮屏」写入值取 int 上限（约 24.8 天）：Settings.System 只存 int，
    // 且 lastUserActivityTime + timeout 用 long 累加不会溢出。
    public static final int REAR_SCREEN_OFF_TIMEOUT_ALWAYS_MS = Integer.MAX_VALUE;
    // 定时关闭窗口内使用的超时值。不取 0：0 在系统里表示「不超时」，
    // 取 1 秒既能立刻走正常的熄灭流程，又给用户触屏留一次可见的状态切换。
    public static final int REAR_SCREEN_SCHEDULE_OFF_TIMEOUT_MS = 1000;
    // PowerManager.WAKE_REASON_APPLICATION：与系统自己「应用请求唤醒」的用法一致。
    public static final int WAKE_REASON_APPLICATION = 2;
    // PowerManager.GO_TO_SLEEP_REASON_TIMEOUT：与系统自己「超时熄屏」的用法一致。
    public static final int GO_TO_SLEEP_REASON_TIMEOUT = 2;
    // 游戏时保持背屏亮：DualScreenCoverManager.updateHighLoadSceneMode(int, boolean) 在
    // 主屏进入高负载（横屏大型游戏）时调用 goToSleepSecondaryDisplay(..., "mainScreen:highLoadGameMode", 0)
    // 直接关掉背屏（日志原文 "Secondary screen turned OFF, Main screen keep ON"）。
    // 该路径不走超时，只能拦这一处；同时把超时抬到上限，避免 10/30 秒后自己熄掉。
    public static final String SYSTEM_UPDATE_HIGH_LOAD_SCENE_METHOD = "updateHighLoadSceneMode";
    public static final String SYSTEM_HIGH_LOAD_GAME_DETAILS = "mainScreen:highLoadGameMode";
    public static final String SYSTEM_POWER_MANAGER_SERVICE_IMPL_FIELD = "mPowerManagerServiceImpl";
    public static final String SYSTEM_ACTIVITY_TASK_MANAGER_FIELD = "mActivityTaskManager";
    public static final String SYSTEM_FOREGROUND_APP_PACKAGE_FIELD = "mForegroundAppPackageName";
    // 背屏「双击熄屏」手势（miui-services.jar，未混淆）。onFocusedWindowChanged 按新焦点窗口的包名
    // 决定 mGestureEnable：在内置名单 PACKAGE_WITHOUT_DOUBLE_TAP_GESTURE 里就关掉，否则恢复为 true；
    // onPointerEvent 只有 mSetGestureEnable && mGestureEnable && mIsFlagEnable 都为真才 goToSleep。
    public static final String SYSTEM_SUBSCREEN_DOUBLE_TAP_GESTURE_CLASS =
            "com.miui.server.input.gesture.multifingergesture.gesture.MiuiSubscreenDoubleTapGesture";
    public static final String SYSTEM_ON_FOCUSED_WINDOW_CHANGED_METHOD = "onFocusedWindowChanged";
    public static final String SYSTEM_GESTURE_ENABLE_FIELD = "mGestureEnable";
    public static final String SYSTEM_WINDOW_STATE_OWNING_PACKAGE_METHOD = "getOwningPackage";
    /** ActivityTaskManagerService 的栈顶 ActivityRecord，只读字段、不取锁。 */
    public static final String SYSTEM_TOP_RESUMED_ACTIVITY_FIELD = "mTopResumedActivity";
    /** ActivityRecord 的包名字段。 */
    public static final String SYSTEM_ACTIVITY_RECORD_PACKAGE_FIELD = "packageName";
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
    public static final String THEME_REAR_PRESET_REPOSITORY_CLASS =
            "com.rearScreen.repository.RearScreenPresetRepository";
    public static final String THEME_SETTING_HELPER_COMPANION_CLASS =
            "com.personalizedEditor.helper.SettingHelper$Companion";
    public static final String THEME_MIUI_UTILS_CLASS =
            "com.android.thememanager.basemodule.utils.MiuiUtils";
    public static final String THEME_REAR_SCREEN_AI_APP_ACTIVITY =
            "com.rearScreen.aiapp.activity.RearScreenAiAppListActivity";
    public static final String THEME_AI_REAR_SCREEN_LIST_ACTIVITY =
            "com.android.thememanager.activity.ai.AiRearScreenListActivity";
    /**
     * AI 应用卡链路涉及的页面。进入其中任意一个都按 18 Pro Max 身份发请求，全部离开才恢复真实身份。
     * 单个 boolean 会被「列表页 onStop 晚于详情页 onResume」的交错顺序反转，故按集合跟踪。
     */
    public static final String[] THEME_AI_APP_FLOW_ACTIVITIES = {
            THEME_REAR_SCREEN_AI_APP_ACTIVITY,
            "com.rearScreen.aiapp.activity.RearScreenAiAppDetailActivity",
            "com.rearScreen.aiapp.activity.RearScreenAiAppAddedListActivity",
            "com.rearScreen.aiapp.activity.RearScreenAiAppEditActivity",
            "com.rearScreen.aiapp.activity.RearScreenAiAppGenerateActivity",
            "com.rearScreen.aiapp.activity.RearScreenAiAppMyGenListActivity",
            THEME_AI_REAR_SCREEN_LIST_ACTIVITY,
    };
    /**
     * AI 应用卡页面的退出信号，按优先级排列。宿主只在真正覆写时才声明这些方法，
     * 因此查找必须沿继承链进行；onStop 优先——页面不可见就复位，比等销毁及时。
     */
    public static final String[] THEME_AI_APP_EXIT_METHODS = {"onStop", "onPause", "onDestroy"};
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
    // AI 应用详情与普通背屏资源详情共用 /native/page/v3/theme/{id}，只有请求头能区分：
    // AiAppPageApi 带 request_flag:1，DetailRequestInterface（普通详情）带 request_flag:31。
    public static final String THEME_AI_APP_REQUEST_FLAG_HEADER = "request_flag";
    public static final String THEME_AI_APP_REQUEST_FLAG_VALUE = "1";
    public static final String THEME_DOWNLOAD_PATH = "/download/v2/";
    /**
     * 背屏壁纸 / 背屏个性化在主题商店下载接口里的 category 值。
     * 该类目只认本机真实设备名：伪装成 18 Pro Max（madrid）会被服务端以
     * {@code get downloadUrl error!} 拒绝。AI 应用卡资源不受此限。
     */
    public static final String THEME_REAR_WALLPAPER_CATEGORY = "rearscreen";
    /** 服务端在拿不到该资源对应设备池时回的固定错误文案，用于触发换身份重试。 */
    public static final String THEME_DOWNLOAD_ERROR_MARKER = "get downloadUrl error";
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
    /** theme_magic 数据根。模块落盘口径固定 user 0（KSU 同步目标）；读宿主运行时产物的地方按实际用户取。 */
    public static final String THEME_MAGIC_DIR = "/data/system/theme_magic";
    public static final String THEME_MAGIC_USERS_DIR = THEME_MAGIC_DIR + "/users/";
    public static final String THEME_MAGIC_USER_ROOT = THEME_MAGIC_USERS_DIR + "0";
    public static final String THEME_MAGIC_REAR_SCREEN_DIR =
            THEME_MAGIC_USER_ROOT + "/rearScreen";
    public static final String THEME_MAGIC_APP_CARD_DIR =
            THEME_MAGIC_USER_ROOT + "/back_screen_card";
    public static final String THEME_MAGIC_AI_APP_DIR =
            THEME_MAGIC_USER_ROOT + "/rearScreenAiApp_Theme";
    public static final String THEME_MAGIC_MIBACKSCREEN_RESOURCE_DIR =
            THEME_MAGIC_USER_ROOT + "/mibackscreen_18";
    public static final String THEME_AI_APP_RUNTIME_FILE =
            THEME_MAGIC_AI_APP_DIR + "/runtimeAiApp.json";
    public static final String SUBSCREEN_APP_INFO_FILE =
            THEME_MAGIC_USER_ROOT + "/subscreencenter/config/appInfo.json";
    public static final String MODULE_PANEL_CARD_RES_ID = "mibackscreen_quick_panel";
    public static final String MODULE_PANEL_CARD_ROOT =
            THEME_MAGIC_APP_CARD_DIR + "/" + MODULE_PANEL_CARD_RES_ID;
    public static final String MODULE_PANEL_CARD_RESOURCE =
            MODULE_PANEL_CARD_ROOT + "/rearscreen";
    public static final String MODULE_PANEL_CARD_DESCRIPTION =
            MODULE_PANEL_CARD_ROOT + "/description.xml";
    public static final String MODULE_PANEL_CARD_META =
            MODULE_PANEL_CARD_ROOT + "/meta.json";
    public static final String MODULE_PANEL_CARD_ICON =
            MODULE_PANEL_CARD_ROOT + "/app/app_icon.png";
    public static final String MODULE_PANEL_CARD_PREVIEW_LIGHT =
            MODULE_PANEL_CARD_ROOT + "/preview/preview_rearscreen_0.png";
    public static final String MODULE_PANEL_CARD_PREVIEW_DARK =
            MODULE_PANEL_CARD_ROOT + "/preview/preview_rearscreen_dark_0.png";
    public static final String MODULE_PANEL_CARD_INSTALL_MARK =
            THEME_MAGIC_USER_ROOT + "/subscreencenter/config/mibackscreen_quick_panel.installed";
    public static final long AI_MATE_BLANK_SNAPSHOT_MAX_BYTES = 256L * 1024L;
    public static final String AI_MATE_PEEKO_RES_ID =
            "317d9477-3731-4636-b526-15f914647826";
    public static final String AI_MATE_PEEKO_PREVIEW_PATH =
            "/product/etc/precust_theme/theme/.data/preview/theme/8e2cd3b6-63d6-47df-995b-f56914acaf43/preview_rearscreen_0.png";
    public static final String AI_MATE_LUMI_RES_ID =
            "b4deb767-f817-4376-b965-dd037e21953b";
    public static final String AI_MATE_LUMI_PREVIEW_PATH =
            "/product/etc/precust_theme/theme/.data/preview/theme/9374c6eb-7742-496e-8180-683e9da0e9ea/preview_rearscreen_0.png";

    // 背屏 AI 陪伴壁纸在详情页「再次应用」时不带已有 applyId，宿主返回的列表里又没有该项，
    // 于是按新 applyId 再插一行，同一 resId 在「妙享背屏」里累积多条。
    // 应用入口是 Companion.q(isUpdate, bean, cacheHelper, continuation)，isUpdate 为 true 时
    // 宿主按 (resId, applyId) 命中已有项做替换，这里只对 AI 陪伴场景把 applyId 对齐到已有项。
    public static final String THEME_REAR_RES_OPERATION_COMPANION_CLASS =
            "com.rearScreen.manager.RearScreenResOperationHelper$Companion";
    public static final String THEME_REAR_LIST_ITEM_CLASS =
            "com.rearScreen.bean.RearScreenListItemBean";
    public static final String THEME_REAR_APPLY_ENTRY_METHOD = "q";
    public static final String THEME_REAR_AI_COMPANION_RES_TYPE = "ai-mate";
    public static final String THEME_REAR_AI_COMPANION_SUB_TYPE = "ai";
    public static final String THEME_REAR_AI_WALLPAPER_CATEGORY_TYPE = "ai";
    public static final String THEME_REAR_GROUP_PHOTO_RES_TYPE = "groupPhoto";
    public static final String THEME_REAR_PHONE_CASE_RES_TYPE = "phoneCase";
    public static final String THEME_REAR_TEMPLATE_NORMAL_PRODUCT_DIR =
            "/product/media/rearscreen/template/normal";
    public static final String THEME_REAR_TEMPLATE_NORMAL_SYSTEM_DIR =
            "/system/media/rearscreen/template/normal";
    public static final String THEME_REAR_TEMPLATE_NORMAL_DATA_DIR =
            THEME_MAGIC_MIBACKSCREEN_RESOURCE_DIR + "/template/normal";
    public static final String THEME_REAR_CMF_FALLBACK = "BK";

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
    /** 背屏下拉出来的「智能助理」面板，和 MainPanel 收同一套页面对象。 */
    public static final String HOOK_CLASS_SMART_ASSISTANT_PANEL = "com.xiaomi.subscreencenter.SmartAssistantPanel";
    // v(false) 表示用户确认退出编辑模式；v(true) 表示暂停/AOD 等场景取消编辑。
    public static final String HOOK_METHOD_REQUEST_EXIT_EDIT = "v";
    // RELEASE-1.0.2609181659：onPause / onAodStateChanged 改为调用 w(Z)。
    public static final String HOOK_METHOD_REQUEST_EXIT_EDIT_18PRO = "w";
    public static final String HOOK_METHOD_SAVE_USER_SELECTION = "I";
    // RELEASE-1.0.2609181659：写 user_select 的方法从 I() 变为 J()。
    public static final String HOOK_METHOD_SAVE_USER_SELECTION_18PRO = "J";
    // RELEASE-1.0.2609181659：MainPanel 生命周期与原生壁纸列表切换入口。
    public static final String HOOK_METHOD_MAIN_PANEL_RESUME = "E";
    public static final String HOOK_METHOD_MAIN_PANEL_PAUSE = "D";
    public static final String HOOK_METHOD_MAIN_PANEL_DESTROY = "C";
    public static final String HOOK_METHOD_MAIN_PANEL_AOD_CHANGED = "B";
    public static final String HOOK_METHOD_AUTO_WALLPAPER_SWITCH = "f";
    // RELEASE-1.0.2605272226
    public static final String HOOK_FIELD_WIDGET_LIST = "i";
    public static final String HOOK_FIELD_SELECTED_INDEX = "l";
    public static final String HOOK_FIELD_PREVIEW_INDEX = "k";
    // RELEASE-1.0.2607201627 (HyperOS 4)
    public static final String HOOK_FIELD_WIDGET_LIST_OS4 = "j";
    public static final String HOOK_FIELD_SELECTED_INDEX_OS4 = "m";
    public static final String HOOK_FIELD_PREVIEW_INDEX_OS4 = "l";
    public static final String HOOK_FIELD_MAIN_PANEL_EDITING = "n";
    public static final String HOOK_FIELD_MAIN_PANEL_STATE = "s";
    public static final String HOOK_FIELD_WIDGET_BEAN = "c";
    public static final String HOOK_FIELD_WIDGET_ID = "a";
    public static final String SECURE_THEME_REAR_WIDGET = "theme_rear_widget";
    public static final String SECURE_SUBSCREEN_APP_WIDGET_ENABLE = "subscreen_app_widget_enable";
    public static final String SUBSCREEN_LAUNCHER_ACTIVITY_CLASS =
            "com.xiaomi.subscreencenter.SubScreenLauncher";

    // 背屏应用卡总闸：18 Pro 包会通过这些目标判断/隐藏应用卡运行时。
    public static final String SUBSCREEN_DEVICE_CONFIG_CLASS = "o2.j";
    public static final String SUBSCREEN_DEVICE_CONFIG_APP_WIDGET_METHOD = "d";
    // 背屏音乐控件判定类（智能助理卡片用）：静态 c(String)boolean 判断某包是不是音乐应用，
    // 静态 b()HashSet 给 MAML 音乐控件返回白名单。混淆名随版本变（m2.a / A2.a），由 DexKit 定位。
    public static final String SUBSCREEN_MUSIC_WHITELIST_CLASS = "m2.a";
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
    public static final String SUBSCREEN_APP_LIST_LISTENER_CLASS = "e2.f";
    public static final String SUBSCREEN_APP_LIST_LISTENER_METHOD = "a";
    public static final String SUBSCREEN_APP_LIST_LISTENER_KIND_FIELD = "a";
    public static final String SUBSCREEN_ON_TRANSACT_METHOD = "onTransact";
    public static final int SUBSCREEN_INSERT_APP_WIDGET_TRANSACTION = 11;
    public static final int SUBSCREEN_APP_CARD_LIMIT = 15;

    // 背屏上滑面板里的模块条目。面板条目是 e2.a，由 e2.k 单例持有：
    // a=名称、c=多语言名称表、e=图标路径、g/h=明暗预览图、l=bindApp、k=appCardType、m=Intent。
    // e2.l.onClick 对 k != 3 的条目直接 startActivity(m)，所以条目自己就能带点击行为。
    public static final String SUBSCREEN_LAUNCHER_ENTRY_CLASS = "e2.a";
    public static final String SUBSCREEN_LAUNCHER_ENTRY_NAME_FIELD = "a";
    public static final String SUBSCREEN_LAUNCHER_ENTRY_NAME_MAP_FIELD = "c";
    public static final String SUBSCREEN_LAUNCHER_ENTRY_MTZ_FIELD = "d";
    public static final String SUBSCREEN_LAUNCHER_ENTRY_ICON_FIELD = "e";
    public static final String SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_LIGHT_FIELD = "g";
    public static final String SUBSCREEN_LAUNCHER_ENTRY_PREVIEW_DARK_FIELD = "h";
    public static final String SUBSCREEN_LAUNCHER_ENTRY_RES_ID_FIELD = "i";
    public static final String SUBSCREEN_LAUNCHER_ENTRY_GAME_FIELD = "j";
    public static final String SUBSCREEN_LAUNCHER_ENTRY_BIND_APP_FIELD = "l";
    public static final String SUBSCREEN_LAUNCHER_ENTRY_CARD_TYPE_FIELD = "k";
    public static final String SUBSCREEN_LAUNCHER_ENTRY_INTENT_FIELD = "m";
    public static final String SUBSCREEN_LAUNCHER_ENTRY_PRESET_FIELD = "o";
    // 条目点击由 e2.o.g(...)（onBindViewHolder）每次绑定 new 出来的 e2.l 处理，
    // e2.l.b 就是绑定的 e2.a 条目。
    public static final String SUBSCREEN_LAUNCHER_HOLDER_CLASS = "e2.l";
    public static final String SUBSCREEN_LAUNCHER_HOLDER_ITEM_FIELD = "b";
    public static final String SUBSCREEN_LAUNCHER_HOLDER_CLICK_METHOD = "onClick";
    // 0 只是"不等于 3"：k == 3 会走引导/NFC 分支而不是启动 Intent。
    public static final int SUBSCREEN_LAUNCHER_ENTRY_ACTIVITY_CARD_TYPE = 0;
    public static final int SUBSCREEN_LAUNCHER_ENTRY_LOCAL_CARD_TYPE = 1;
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
    /** 智能助理里「背屏应用卡详情」页；类名和 onCreate 都不混淆。 */
    public static final String PERSONAL_ASSISTANT_BACKSCREEN_DETAIL_ACTIVITY_CLASS =
            "com.miui.personalassistant.backscreen.detail.view.BackScreenDetailActivity";
    /** 智能助理自带的 Glide；get / clearMemory / clearDiskCache 是公开 API，不混淆。 */
    public static final String PERSONAL_ASSISTANT_GLIDE_CLASS = "com.bumptech.glide.Glide";
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
    public static final String PERSONAL_ASSISTANT_PRESET_JSON_PATH =
            THEME_MAGIC_MIBACKSCREEN_RESOURCE_DIR + "/appcard/default/rearScreen.json";
    public static final String PERSONAL_ASSISTANT_PRESET_JSON_CLASS =
            "com.miui.personalassistant.backscreen.store.model.PresetJson";
    public static final String PERSONAL_ASSISTANT_PRESET_CATEGORY_CLASS =
            "com.miui.personalassistant.backscreen.store.model.PresetCategory";
    public static final String PERSONAL_ASSISTANT_GSON_CLASS = "com.google.gson.Gson";
    /** 与主题商店伪装同一身份（18 Pro Max 池），值随 THEME_REAR_* 走。 */
    public static final String PERSONAL_ASSISTANT_REAR_DEVICE = THEME_REAR_DEVICE;
    public static final String PERSONAL_ASSISTANT_REAR_MODEL = THEME_REAR_MODEL;

    // logcat 过滤用：旧名 RearScreenLongPressToggle 仅覆盖长按开关，已不符实际功能范围
    public static final String LOG_TAG = "MiBackscreen";

    private Constants() {
    }
}
