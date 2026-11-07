package hook.HyperBackscreen.ui.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/** Stable, serializable keys for the main shell and its detail pages. */
@Serializable
internal enum class HomeRoute : NavKey {
    Main,
    Settings,
    License,
    AppPicker,
    Donate,
    BatteryColor,
    RearScreenTiming,
    AppUpdate,
    VersionHistory,
    // 新路由只追加在末尾（见 HomeRouteTest）。
    DoubleTapSleepAppPicker,
    MusicWhitelistAppPicker,
    RearScreenRecord
}
