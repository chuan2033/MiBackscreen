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
    val disableRearScreenCover: Boolean,
    val themeSettingsShortcut: Boolean,
    val disableDoubleTapWake: Boolean,
    val doubleTapWakeDisabledPackages: String,
    val enable18ProFeatures: Boolean,
    val enableBatteryRing: Boolean = false,
    val batteryColorIdle: String = "",
    val batteryColorCharging: String = "",
    val batteryColorLow: String = "",
    val batteryPercentPosition: String = PrefsBridge.DEFAULT_BATTERY_PERCENT_POSITION,
    val batteryPercentOffsetX: String = PrefsBridge.DEFAULT_BATTERY_PERCENT_OFFSET_X,
    val batteryPercentOffsetXLeft: String = PrefsBridge.DEFAULT_BATTERY_PERCENT_OFFSET_X,
    val batteryPercentOffsetXRight: String = PrefsBridge.DEFAULT_BATTERY_PERCENT_OFFSET_X,
    val batteryPercentOffsetY: String = PrefsBridge.DEFAULT_BATTERY_PERCENT_OFFSET_Y,
    val batteryRingScale: String = PrefsBridge.DEFAULT_BATTERY_RING_SCALE,
    val batteryRingStroke: String = PrefsBridge.DEFAULT_BATTERY_RING_STROKE,
    val batteryPercentTextSize: String = PrefsBridge.DEFAULT_BATTERY_PERCENT_TEXT_SIZE,
    val batteryPercentTextWeight: String = PrefsBridge.DEFAULT_BATTERY_PERCENT_TEXT_WEIGHT,
    val batteryRingTrack: String = PrefsBridge.DEFAULT_BATTERY_RING_TRACK,
    val batteryRingStyle: String = PrefsBridge.DEFAULT_BATTERY_RING_STYLE,
    val autoWallpaperIntervalMinutes: String = PrefsBridge.DEFAULT_AUTO_WALLPAPER_INTERVAL_MINUTES,
    val rearScreenOffTimeout: String = PrefsBridge.DEFAULT_REAR_SCREEN_OFF_TIMEOUT,
    val keepRearScreenOnInGame: Boolean = PrefsBridge.DEFAULT_KEEP_REAR_SCREEN_ON_IN_GAME,
    val rearScreenScheduleEnabled: Boolean = PrefsBridge.DEFAULT_REAR_SCREEN_SCHEDULE_ENABLED,
    val rearScreenScheduleStart: String = PrefsBridge.DEFAULT_REAR_SCREEN_SCHEDULE_START,
    val rearScreenScheduleEnd: String = PrefsBridge.DEFAULT_REAR_SCREEN_SCHEDULE_END,
    val rearScreenScheduleMode: String = PrefsBridge.DEFAULT_REAR_SCREEN_SCHEDULE_MODE,
    val faceEnrollmentCompat: Boolean = PrefsBridge.DEFAULT_FACE_ENROLLMENT_COMPAT,
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
            Constants.KEY_DISABLE_REAR_SCREEN_COVER,
            Constants.KEY_THEME_SETTINGS_SHORTCUT,
            Constants.KEY_DISABLE_DOUBLE_TAP_WAKE,
            Constants.KEY_DOUBLE_TAP_WAKE_DISABLED_PACKAGES,
            Constants.KEY_BATTERY_COLOR_IDLE,
            Constants.KEY_BATTERY_COLOR_CHARGING,
            Constants.KEY_BATTERY_COLOR_LOW,
            Constants.KEY_BATTERY_PERCENT_POSITION,
            Constants.KEY_BATTERY_PERCENT_OFFSET_X,
            Constants.KEY_BATTERY_PERCENT_OFFSET_X_LEFT,
            Constants.KEY_BATTERY_PERCENT_OFFSET_X_RIGHT,
            Constants.KEY_BATTERY_PERCENT_OFFSET_Y,
            Constants.KEY_BATTERY_RING_SCALE,
            Constants.KEY_BATTERY_RING_STROKE,
            Constants.KEY_BATTERY_PERCENT_TEXT_SIZE,
            Constants.KEY_BATTERY_PERCENT_TEXT_WEIGHT,
            Constants.KEY_BATTERY_RING_TRACK,
            Constants.KEY_BATTERY_RING_STYLE,
            Constants.KEY_AUTO_WALLPAPER_INTERVAL_MINUTES,
            Constants.KEY_REAR_SCREEN_OFF_TIMEOUT,
            Constants.KEY_KEEP_REAR_SCREEN_ON_IN_GAME,
            Constants.KEY_REAR_SCREEN_SCHEDULE_ENABLED,
            Constants.KEY_REAR_SCREEN_SCHEDULE_START,
            Constants.KEY_REAR_SCREEN_SCHEDULE_END,
            Constants.KEY_REAR_SCREEN_SCHEDULE_MODE,
            Constants.KEY_FACE_ENROLLMENT_COMPAT,
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
                batteryPercentPosition = value(Constants.KEY_BATTERY_PERCENT_POSITION) as? String
                    ?: PrefsBridge.DEFAULT_BATTERY_PERCENT_POSITION,
                batteryPercentOffsetX = value(Constants.KEY_BATTERY_PERCENT_OFFSET_X) as? String
                    ?: PrefsBridge.DEFAULT_BATTERY_PERCENT_OFFSET_X,
                batteryPercentOffsetXLeft = value(Constants.KEY_BATTERY_PERCENT_OFFSET_X_LEFT) as? String
                    ?: PrefsBridge.DEFAULT_BATTERY_PERCENT_OFFSET_X,
                batteryPercentOffsetXRight = value(Constants.KEY_BATTERY_PERCENT_OFFSET_X_RIGHT) as? String
                    ?: PrefsBridge.DEFAULT_BATTERY_PERCENT_OFFSET_X,
                batteryPercentOffsetY = value(Constants.KEY_BATTERY_PERCENT_OFFSET_Y) as? String
                    ?: PrefsBridge.DEFAULT_BATTERY_PERCENT_OFFSET_Y,
                batteryRingScale = value(Constants.KEY_BATTERY_RING_SCALE) as? String
                    ?: PrefsBridge.DEFAULT_BATTERY_RING_SCALE,
                batteryRingStroke = value(Constants.KEY_BATTERY_RING_STROKE) as? String
                    ?: PrefsBridge.DEFAULT_BATTERY_RING_STROKE,
                batteryPercentTextSize = value(Constants.KEY_BATTERY_PERCENT_TEXT_SIZE) as? String
                    ?: PrefsBridge.DEFAULT_BATTERY_PERCENT_TEXT_SIZE,
                batteryPercentTextWeight = value(Constants.KEY_BATTERY_PERCENT_TEXT_WEIGHT) as? String
                    ?: PrefsBridge.DEFAULT_BATTERY_PERCENT_TEXT_WEIGHT,
                batteryRingTrack = value(Constants.KEY_BATTERY_RING_TRACK) as? String
                    ?: PrefsBridge.DEFAULT_BATTERY_RING_TRACK,
                batteryRingStyle = value(Constants.KEY_BATTERY_RING_STYLE) as? String
                    ?: PrefsBridge.DEFAULT_BATTERY_RING_STYLE,
                autoWallpaperIntervalMinutes = value(Constants.KEY_AUTO_WALLPAPER_INTERVAL_MINUTES) as? String
                    ?: PrefsBridge.DEFAULT_AUTO_WALLPAPER_INTERVAL_MINUTES,
                rearScreenOffTimeout = value(Constants.KEY_REAR_SCREEN_OFF_TIMEOUT) as? String
                    ?: PrefsBridge.DEFAULT_REAR_SCREEN_OFF_TIMEOUT,
                keepRearScreenOnInGame = boolean(
                    Constants.KEY_KEEP_REAR_SCREEN_ON_IN_GAME,
                    PrefsBridge.DEFAULT_KEEP_REAR_SCREEN_ON_IN_GAME,
                ),
                rearScreenScheduleEnabled = boolean(
                    Constants.KEY_REAR_SCREEN_SCHEDULE_ENABLED,
                    PrefsBridge.DEFAULT_REAR_SCREEN_SCHEDULE_ENABLED,
                ),
                rearScreenScheduleStart = value(Constants.KEY_REAR_SCREEN_SCHEDULE_START) as? String
                    ?: PrefsBridge.DEFAULT_REAR_SCREEN_SCHEDULE_START,
                rearScreenScheduleEnd = value(Constants.KEY_REAR_SCREEN_SCHEDULE_END) as? String
                    ?: PrefsBridge.DEFAULT_REAR_SCREEN_SCHEDULE_END,
                rearScreenScheduleMode = value(Constants.KEY_REAR_SCREEN_SCHEDULE_MODE) as? String
                    ?: PrefsBridge.DEFAULT_REAR_SCREEN_SCHEDULE_MODE,
                // 迁移：新键从未写过时沿用「修复背屏壁纸应用失败」，
                // 与 PrefsBridge.readFaceEnrollmentCompatForUi 的回退保持一致。
                faceEnrollmentCompat = boolean(
                    Constants.KEY_FACE_ENROLLMENT_COMPAT,
                    boolean(
                        Constants.KEY_FIX_REAR_SCREEN_APPLY,
                        PrefsBridge.DEFAULT_FIX_REAR_SCREEN_APPLY,
                    ),
                ),
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
