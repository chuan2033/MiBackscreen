package hook.HyperBackscreen.bridge

import android.content.SharedPreferences
import hook.HyperBackscreen.common.Constants
import java.io.Closeable

/** App-process cache, updated by UI writes, the panel Provider and service reconciliation. */
internal data class ModulePreferencesSnapshot(
    val disableLongPress: Boolean,
    val removeWallpaperLimit: Boolean,
    val enableAppCard: Boolean,
    val removeAppCardLimit: Boolean,
    val fixRearScreenApply: Boolean,
    val enablePickup: Boolean,
    val disableRearScreenCover: Boolean,
    val themeSettingsShortcut: Boolean,
    val disableDoubleTapWake: Boolean,
    val doubleTapWakeDisabledPackages: String,
    val enable18ProFeatures: Boolean,
    val enableBatteryRing: Boolean = false,
    val batteryColorIdle: String = "",
    val batteryColorCharging: String = "",
    val batteryColorLow: String = "",
) {
    companion object {
        private val keys = setOf(
            Constants.KEY_DISABLE_LONG_PRESS_EDIT,
            Constants.KEY_REMOVE_WALLPAPER_LIMIT,
            Constants.KEY_ENABLE_APP_CARD,
            Constants.KEY_ENABLE_18_PRO_FEATURES,
            Constants.KEY_ENABLE_BATTERY_RING,
            Constants.KEY_REMOVE_APP_CARD_LIMIT,
            Constants.KEY_FIX_REAR_SCREEN_APPLY,
            Constants.KEY_ENABLE_PICKUP,
            Constants.KEY_DISABLE_REAR_SCREEN_COVER,
            Constants.KEY_THEME_SETTINGS_SHORTCUT,
            Constants.KEY_DISABLE_DOUBLE_TAP_WAKE,
            Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES,
            Constants.KEY_BATTERY_COLOR_IDLE,
            Constants.KEY_BATTERY_COLOR_CHARGING,
            Constants.KEY_BATTERY_COLOR_LOW,
        )

        /** One in-memory snapshot, with no Binder call or cache write during observation. */
        fun read(preferences: SharedPreferences): ModulePreferencesSnapshot {
            val values = preferences.all
            fun value(key: String): Any? {
                val pending = PrefsBridge.PENDING_UI_PREFIX + key
                return values[if (values.containsKey(pending)) pending else key]
            }
            fun boolean(key: String, default: Boolean) = value(key) as? Boolean ?: default
            return ModulePreferencesSnapshot(
                disableLongPress = boolean(Constants.KEY_DISABLE_LONG_PRESS_EDIT, PrefsBridge.DEFAULT_DISABLE_LONG_PRESS_EDIT),
                removeWallpaperLimit = boolean(Constants.KEY_REMOVE_WALLPAPER_LIMIT, PrefsBridge.DEFAULT_REMOVE_WALLPAPER_LIMIT),
                enableAppCard = boolean(Constants.KEY_ENABLE_APP_CARD, PrefsBridge.DEFAULT_ENABLE_APP_CARD),
                enable18ProFeatures = boolean(Constants.KEY_ENABLE_18_PRO_FEATURES, PrefsBridge.DEFAULT_ENABLE_18_PRO_FEATURES),
                enableBatteryRing = boolean(Constants.KEY_ENABLE_BATTERY_RING, PrefsBridge.DEFAULT_ENABLE_BATTERY_RING),
                removeAppCardLimit = boolean(Constants.KEY_REMOVE_APP_CARD_LIMIT, PrefsBridge.DEFAULT_REMOVE_APP_CARD_LIMIT),
                fixRearScreenApply = boolean(Constants.KEY_FIX_REAR_SCREEN_APPLY, PrefsBridge.DEFAULT_FIX_REAR_SCREEN_APPLY),
                enablePickup = boolean(Constants.KEY_ENABLE_PICKUP, PrefsBridge.DEFAULT_ENABLE_PICKUP),
                disableRearScreenCover = boolean(Constants.KEY_DISABLE_REAR_SCREEN_COVER, PrefsBridge.DEFAULT_DISABLE_REAR_SCREEN_COVER),
                themeSettingsShortcut = boolean(Constants.KEY_THEME_SETTINGS_SHORTCUT, PrefsBridge.DEFAULT_THEME_SETTINGS_SHORTCUT),
                disableDoubleTapWake = boolean(Constants.KEY_DISABLE_DOUBLE_TAP_WAKE, PrefsBridge.DEFAULT_DISABLE_DOUBLE_TAP_WAKE),
                doubleTapWakeDisabledPackages = value(Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES) as? String
                    ?: PrefsBridge.DEFAULT_DOUBLE_TAP_WAKE_DISABLED_PACKAGES,
                batteryColorIdle = value(Constants.KEY_BATTERY_COLOR_IDLE) as? String
                    ?: PrefsBridge.DEFAULT_BATTERY_COLOR,
                batteryColorCharging = value(Constants.KEY_BATTERY_COLOR_CHARGING) as? String
                    ?: PrefsBridge.DEFAULT_BATTERY_COLOR,
                batteryColorLow = value(Constants.KEY_BATTERY_COLOR_LOW) as? String
                    ?: PrefsBridge.DEFAULT_BATTERY_COLOR,
            )
        }

        /** The caller marshals invalidation to the UI thread and reads then, not in this callback. */
        fun observe(preferences: SharedPreferences, invalidate: Runnable): Closeable {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key in keys || key.removePrefix(PrefsBridge.PENDING_UI_PREFIX) in keys) {
                    invalidate.run()
                }
            }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            // Keep the listener strongly referenced until disposal (SharedPreferences holds it weakly).
            return Closeable { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }
}
