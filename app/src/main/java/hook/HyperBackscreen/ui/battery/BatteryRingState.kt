package hook.HyperBackscreen.ui.battery

internal data class BatteryRingState(val percent: Int, val charging: Boolean) {
    val colorRole: String
        get() = when {
            charging -> "status_bar_battery_charging"
            percent < LOW_PERCENT_THRESHOLD -> "status_bar_battery_low"
            else -> "status_bar_battery_level_white"
        }

    val fallbackColor: Int
        get() = when {
            charging -> 0xff1dcd3a.toInt()
            percent < LOW_PERCENT_THRESHOLD -> 0xfffa382e.toInt()
            else -> 0xffffffff.toInt()
        }

    companion object {
        /** 低电量判定阈值，与系统电池图标换色点一致；不对外配置。 */
        const val LOW_PERCENT_THRESHOLD = 20
        fun from(level: Int, scale: Int, status: Int, plugged: Boolean): BatteryRingState? {
            if (level < 0 || scale <= 0 || level > scale) return null
            val percent = (level.toLong() * 100 / scale).toInt()
            // BatteryManager status 2 = charging, 5 = full. A paused charge is not charging.
            return BatteryRingState(percent, status == 2 || (status == 5 && plugged))
        }
    }
}
