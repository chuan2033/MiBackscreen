package hook.HyperBackscreen.bridge

import hook.HyperBackscreen.common.Constants
import kotlinx.serialization.json.JsonPrimitive

object PreferenceSyncReport {
    private val defaults = linkedMapOf<String, Any>(
        Constants.KEY_DISABLE_LONG_PRESS_EDIT to PrefsBridge.DEFAULT_DISABLE_LONG_PRESS_EDIT,
        Constants.KEY_REMOVE_WALLPAPER_LIMIT to PrefsBridge.DEFAULT_REMOVE_WALLPAPER_LIMIT,
        Constants.KEY_ENABLE_APP_CARD to PrefsBridge.DEFAULT_ENABLE_APP_CARD,
        Constants.KEY_ENABLE_18_PRO_FEATURES to PrefsBridge.DEFAULT_ENABLE_18_PRO_FEATURES,
        Constants.KEY_ENABLE_BATTERY_RING to PrefsBridge.DEFAULT_ENABLE_BATTERY_RING,
        Constants.KEY_REMOVE_APP_CARD_LIMIT to PrefsBridge.DEFAULT_REMOVE_APP_CARD_LIMIT,
        Constants.KEY_FIX_REAR_SCREEN_APPLY to PrefsBridge.DEFAULT_FIX_REAR_SCREEN_APPLY,
        Constants.KEY_ENABLE_PICKUP to PrefsBridge.DEFAULT_ENABLE_PICKUP,
        Constants.KEY_DISABLE_REAR_SCREEN_COVER to PrefsBridge.DEFAULT_DISABLE_REAR_SCREEN_COVER,
        Constants.KEY_DISABLE_DOUBLE_TAP_WAKE to PrefsBridge.DEFAULT_DISABLE_DOUBLE_TAP_WAKE,
        Constants.KEY_THEME_SETTINGS_SHORTCUT to PrefsBridge.DEFAULT_THEME_SETTINGS_SHORTCUT,
        Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES to PrefsBridge.DEFAULT_DOUBLE_TAP_WAKE_DISABLED_PACKAGES,
        Constants.KEY_PICKUP_ISLAND_SELECTION to PrefsBridge.DEFAULT_PICKUP_ISLAND_SELECTION,
    )

    @JvmStatic
    fun build(local: Map<String, *>, remote: Map<String, *>?): String = buildString {
        appendLine("snapshot_note=Read-only sequential snapshot; remote values are not host-consumption acknowledgements.")
        appendLine("remote_available=${remote != null}")
        appendLine("pending_ui_count=${defaults.keys.count { local.containsKey("__pending_ui__$it") }}")
        appendLine("pending_panel_count=${defaults.keys.count { local.containsKey("__pending_panel__$it") }}")
        for ((key, default) in defaults) {
            val uiKey = "__pending_ui__$key"
            val panelKey = "__pending_panel__$key"
            appendLine()
            appendLine("[$key]")
            appendLine("default=${format(default)}")
            appendLine("local=${if (local.containsKey(key)) format(local[key]) else "<unset>"}")
            appendLine("remote=${when {
                remote == null -> "<unavailable>"
                remote.containsKey(key) -> format(remote[key])
                else -> "<unset; default=${format(default)}>"
            }}")
            appendLine("pending_ui=${if (local.containsKey(uiKey)) format(local[uiKey]) else "<none>"}")
            appendLine("pending_panel=${if (local.containsKey(panelKey)) format(local[panelKey]) else "<none>"}")
            val source = when {
                local.containsKey(uiKey) -> "pending_ui"
                remote != null -> "remote"
                local.containsKey(key) -> "local_cache"
                else -> "default"
            }
            val effective = when (source) {
                "pending_ui" -> local[uiKey]
                "remote" -> remote?.get(key) ?: default
                "local_cache" -> local[key]
                else -> default
            }
            appendLine("ui_source=$source")
            appendLine("ui_value=${format(effective)}")
            appendLine("ui_matches_remote=${if (remote == null) "unknown" else (effective == (remote[key] ?: default)).toString()}")
        }
    }

    private fun format(value: Any?): String = when (value) {
        is Boolean -> value.toString()
        is String -> JsonPrimitive(value).toString()
        else -> "<invalid:${value?.javaClass?.simpleName ?: "null"}>"
    }
}
