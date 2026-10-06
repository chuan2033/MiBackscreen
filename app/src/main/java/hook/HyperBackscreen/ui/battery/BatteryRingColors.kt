package hook.HyperBackscreen.ui.battery

/**
 * 背屏电量显示颜色的用户覆盖值。
 *
 * 空字符串表示跟随系统（SystemUI 电池资源色），这是默认状态；只有用户显式改过才覆盖。
 * 格式为 `#RRGGBB` 或 `#AARRGGBB`，无法解析时按未设置处理。
 */
internal data class BatteryColorOverrides(
    val idle: String = "",
    val charging: String = "",
    val low: String = "",
) {
    /** 该状态对应的覆盖色；null 表示跟随系统。 */
    fun overrideFor(state: BatteryRingState): Int? = when {
        state.charging -> parseBatteryColor(charging)
        state.percent < BatteryRingState.LOW_PERCENT_THRESHOLD -> parseBatteryColor(low)
        else -> parseBatteryColor(idle)
    }

    /** 该状态对应的原始覆盖值，供设置页回显。 */
    fun rawFor(state: BatteryRingState): String = when {
        state.charging -> charging
        state.percent < BatteryRingState.LOW_PERCENT_THRESHOLD -> low
        else -> idle
    }

    companion object {
        fun from(idle: String, charging: String, low: String) =
            BatteryColorOverrides(idle, charging, low)
    }
}

/** 解析 `#RRGGBB` / `#AARRGGBB`；空值或非法值返回 null。 */
internal fun parseBatteryColor(value: String): Int? {
    val text = value.trim().removePrefix("#")
    if (text.length != 6 && text.length != 8) return null
    val parsed = text.toLongOrNull(16) ?: return null
    return if (text.length == 6) (0xFF000000L or parsed).toInt() else parsed.toInt()
}

/** 反解成 `#AARRGGBB`。 */
internal fun formatBatteryColor(argb: Int): String = String.format("#%08X", argb)
