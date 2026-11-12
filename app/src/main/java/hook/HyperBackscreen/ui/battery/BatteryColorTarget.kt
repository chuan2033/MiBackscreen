package hook.HyperBackscreen.ui.battery

import androidx.annotation.StringRes
import hook.HyperBackscreen.R
import hook.HyperBackscreen.batteryring.BatteryColorOverrides
import hook.HyperBackscreen.bridge.ModulePreferencesSnapshot
import hook.HyperBackscreen.common.Constants

/**
 * 「颜色自定义」里正在给谁上色：电量环本身，或者旁边的百分比数字。
 *
 * 两者各存一套三态颜色（未充电 / 充电中 / 低电量），状态判据共用。
 * 环没设色时跟随系统电池色；数字没设色时跟随电量环 —— 所以老用户升级后观感不变。
 */
internal enum class BatteryColorTarget(@get:StringRes val labelRes: Int) {
    RING(R.string.battery_color_state_title),
    PERCENT(R.string.battery_percent_position_title);

    /** 该部分、该状态对应的偏好键。 */
    fun keyFor(slot: BatteryColorSlot): String = when (this) {
        RING -> when (slot) {
            BatteryColorSlot.IDLE -> Constants.KEY_BATTERY_COLOR_IDLE
            BatteryColorSlot.CHARGING -> Constants.KEY_BATTERY_COLOR_CHARGING
            BatteryColorSlot.LOW -> Constants.KEY_BATTERY_COLOR_LOW
        }
        PERCENT -> when (slot) {
            BatteryColorSlot.IDLE -> Constants.KEY_BATTERY_PERCENT_COLOR_IDLE
            BatteryColorSlot.CHARGING -> Constants.KEY_BATTERY_PERCENT_COLOR_CHARGING
            BatteryColorSlot.LOW -> Constants.KEY_BATTERY_PERCENT_COLOR_LOW
        }
    }
}

/**
 * 数字从「跟随电量环」切到单独设色时的起始色：不透明白色。
 *
 * 绿 / 红这类状态色放在环上好看，放在数字上容易被同色或深色壁纸吃掉；
 * 白字再加上绘制端自带的投影，在深浅壁纸上都读得清。
 */
internal const val BatteryPercentContrastColor = "#FFFFFFFF"

/** 设置页回显用：该状态的原始覆盖值（空串 = 不覆盖）。 */
internal fun BatteryColorOverrides.rawFor(slot: BatteryColorSlot): String = when (slot) {
    BatteryColorSlot.IDLE -> idle
    BatteryColorSlot.CHARGING -> charging
    BatteryColorSlot.LOW -> low
}

/** 只改一个状态的覆盖值，其余两态原样保留。 */
internal fun BatteryColorOverrides.with(slot: BatteryColorSlot, value: String): BatteryColorOverrides =
    when (slot) {
        BatteryColorSlot.IDLE -> copy(idle = value)
        BatteryColorSlot.CHARGING -> copy(charging = value)
        BatteryColorSlot.LOW -> copy(low = value)
    }

internal fun batteryRingColorsOf(values: ModulePreferencesSnapshot) = BatteryColorOverrides(
    idle = values.batteryColorIdle,
    charging = values.batteryColorCharging,
    low = values.batteryColorLow,
)

internal fun batteryPercentColorsOf(values: ModulePreferencesSnapshot) = BatteryColorOverrides(
    idle = values.batteryPercentColorIdle,
    charging = values.batteryPercentColorCharging,
    low = values.batteryPercentColorLow,
)
