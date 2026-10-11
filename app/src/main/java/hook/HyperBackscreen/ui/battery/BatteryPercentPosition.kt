package hook.HyperBackscreen.ui.battery

import androidx.annotation.StringRes
import hook.HyperBackscreen.R

internal enum class BatteryPercentPosition(
    val prefValue: String,
    @get:StringRes val labelRes: Int,
) {
    OFF("off", R.string.battery_percent_position_off),
    LEFT("left", R.string.battery_percent_position_left),
    RIGHT("right", R.string.battery_percent_position_right);

    companion object {
        fun fromPref(value: String?): BatteryPercentPosition =
            entries.firstOrNull { it.prefValue == value } ?: OFF
    }
}
