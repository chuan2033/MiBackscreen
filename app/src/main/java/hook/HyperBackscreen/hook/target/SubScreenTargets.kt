package hook.HyperBackscreen.hook.target

import hook.HyperBackscreen.common.Constants

/**
 * 一个宿主类的定位规格。
 *
 * [strings] 是判据：类里必须出现这些字符串常量，混淆器不会改字符串，宿主重新分配类名时判据仍然成立。
 * [packages] 收窄搜索范围，写成宿主自己的包路径前缀（R8 保留包名）。
 * [fallbackNames] 是 DexKit 不可用或判据失配时的兜底类名，保持旧版本行为。
 */
internal class ClassTarget(
    val id: String,
    val packages: List<String>,
    val strings: List<String>,
    val fallbackNames: List<String>,
    /**
     * true 时 [strings] 按整个常量精确相等匹配（默认是子串匹配）。像 "music" 这种短词
     * 用子串匹配会命中一大片类，必须精确匹配。
     */
    val exactStrings: Boolean = false,
    /**
     * 可选的结构校验：类加载出来后再按方法形状确认一次。字符串命中多个时用它挑出唯一一个；
     * 缓存 / 兜底名字加载出来的类同样要过这一关，不满足视为失配。
     */
    val shape: ((Class<*>) -> Boolean)? = null,
)

/**
 * 背屏中心（com.xiaomi.subscreencenter）里的混淆类清单。
 *
 * 判据来自 `dexdump -d` 抽取的类内字符串，并在完整 dex 上验证过唯一性：
 * 每个 [strings] 组合在当前宿主版本里只命中一个类。
 */
internal object SubScreenTargets {
    @JvmField
    val APP_LIST_MANAGER = ClassTarget(
        id = "app_list_manager",
        packages = listOf("e2"),
        strings = listOf("saveAppInfoList: appInfoList is empty"),
        fallbackNames = listOf(Constants.SUBSCREEN_APP_LIST_MANAGER_CLASS),
    )

    @JvmField
    val APP_LIST_LISTENER = ClassTarget(
        id = "app_list_listener",
        packages = listOf("e2"),
        strings = listOf("onDataUpdate: return"),
        fallbackNames = listOf(Constants.SUBSCREEN_APP_LIST_LISTENER_CLASS),
    )

    @JvmField
    val LAUNCHER_HOLDER = ClassTarget(
        id = "launcher_holder",
        packages = listOf("e2"),
        strings = listOf("onBindViewHolder: enter unlocked = "),
        fallbackNames = listOf(Constants.SUBSCREEN_LAUNCHER_HOLDER_CLASS),
    )

    @JvmField
    val LAUNCHER_ENTRY = ClassTarget(
        id = "launcher_entry",
        packages = listOf("e2"),
        strings = emptyList(),
        fallbackNames = listOf(Constants.SUBSCREEN_LAUNCHER_ENTRY_CLASS),
    )

    @JvmField
    val DEVICE_CONFIG = ClassTarget(
        id = "device_config",
        packages = listOf("o2"),
        strings = listOf("MIUI_FLAG_DISABLE_SUBSCREEN_DOUBLE_TAP"),
        fallbackNames = listOf(Constants.SUBSCREEN_DEVICE_CONFIG_CLASS),
    )

    @JvmField
    val GUIDE_SETTINGS = ClassTarget(
        id = "guide_settings",
        packages = listOf("k2"),
        strings = listOf("off_screen_guide"),
        fallbackNames = listOf(Constants.SUBSCREEN_GUIDE_SETTINGS_CLASS),
    )

    @JvmField
    val LAUNCHER_GESTURE = ClassTarget(
        id = "launcher_gesture",
        packages = listOf("Z1"),
        strings = listOf("interceptScrollEvent: notOnTop = "),
        fallbackNames = listOf(Constants.SUBSCREEN_LAUNCHER_PANEL_GESTURE_CLASS),
    )

    @JvmField
    val SERVICE_BINDER = ClassTarget(
        id = "service_binder",
        packages = emptyList(),
        strings = listOf("com.xiaomi.subscreencenter.service.ISubScreen"),
        fallbackNames = listOf(Constants.SUBSCREEN_SERVICE_BINDER_CLASS),
    )

    /**
     * 背屏音乐控件判定类：静态 c(String)boolean 判断包名是否音乐应用（"unified.music" 恒为真，
     * 其余查云控 Map 里类型是否为 "music"），静态 b()HashSet 收集白名单交给 MAML 音乐控件。
     * 包名会随版本变（m2 / A2），不限定搜索包；短词 "music" 必须精确匹配，再按方法形状确认。
     */
    @JvmField
    val MUSIC_WHITELIST = ClassTarget(
        id = "music_whitelist",
        packages = emptyList(),
        strings = listOf("unified.music", "music"),
        fallbackNames = listOf(Constants.SUBSCREEN_MUSIC_WHITELIST_CLASS),
        exactStrings = true,
        shape = MusicWhitelistShape::matches,
    )

    val all: List<ClassTarget> = listOf(
        APP_LIST_MANAGER,
        APP_LIST_LISTENER,
        LAUNCHER_HOLDER,
        LAUNCHER_ENTRY,
        DEVICE_CONFIG,
        GUIDE_SETTINGS,
        LAUNCHER_GESTURE,
        SERVICE_BINDER,
        MUSIC_WHITELIST,
    )
}
