package hook.HyperBackscreen.ui

import androidx.annotation.StringRes
import hook.HyperBackscreen.R

internal enum class AutoWallpaperInterval(
    val minutes: Int,
    @get:StringRes val labelRes: Int,
) {
    OFF(0, R.string.auto_wallpaper_interval_off),
    ONE_MINUTE(1, R.string.auto_wallpaper_interval_one_minute),
    FIVE_MINUTES(5, R.string.auto_wallpaper_interval_five_minutes),
    FIFTEEN_MINUTES(15, R.string.auto_wallpaper_interval_fifteen_minutes);

    val prefValue: String
        get() = minutes.toString()

    companion object {
        fun fromPref(value: String?): AutoWallpaperInterval =
            entries.firstOrNull { it.prefValue == value } ?: OFF
    }
}
