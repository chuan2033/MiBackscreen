package hook.HyperBackscreen.ui.battery

internal data class BatteryRingState(
    val percent: Int,
    val charging: Boolean,
    /** 低电量阈值（用户可调，默认 [LOW_PERCENT_THRESHOLD]）。 */
    val lowThreshold: Int = LOW_PERCENT_THRESHOLD,
) {
    /** 未充电且低于阈值。充电中一律按「充电」处理，不显示低电量色。 */
    val isLow: Boolean
        get() = !charging && percent < lowThreshold

    val colorRole: String
        get() = when {
            charging -> "status_bar_battery_charging"
            percent < lowThreshold -> "status_bar_battery_low"
            else -> "status_bar_battery_level_white"
        }

    val fallbackColor: Int
        get() = when {
            charging -> 0xff1dcd3a.toInt()
            percent < lowThreshold -> 0xfffa382e.toInt()
            else -> 0xffffffff.toInt()
        }

    companion object {
        /** 默认低电量阈值，与系统电池图标换色点一致；用户可在电量自定义里改。 */
        const val LOW_PERCENT_THRESHOLD = 20
        fun from(
            level: Int,
            scale: Int,
            status: Int,
            plugged: Boolean,
            lowThreshold: Int = LOW_PERCENT_THRESHOLD,
        ): BatteryRingState? {
            if (level < 0 || scale <= 0 || level > scale) return null
            val percent = (level.toLong() * 100 / scale).toInt()
            // BatteryManager status 2 = charging, 5 = full. A paused charge is not charging.
            return BatteryRingState(percent, status == 2 || (status == 5 && plugged), lowThreshold)
        }
    }
}
