package hook.HyperBackscreen.ui

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/** Stable, serializable keys for the main shell and its detail pages. */
@Serializable
internal enum class HomeRoute : NavKey {
    Main,
    Settings,
    License,
    AppPicker,
    Donate
}
