package hook.HyperBackscreen.ui

import androidx.annotation.StringRes
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.Constants

/**
 * 背屏息屏超时档位。
 *
 * [prefValue] 直接就是系统设置 `subscreen_display_time` 的毫秒值：`"0"` 表示跟随系统
 * （不覆盖），其余档位覆盖背屏（display group 1）的息屏倒计时。
 */
internal enum class RearScreenOffTimeout(
    val timeoutMillis: Int,
    @get:StringRes val labelRes: Int,
) {
    SYSTEM(0, R.string.rear_screen_off_timeout_system),
    ALWAYS(Constants.REAR_SCREEN_OFF_TIMEOUT_ALWAYS_MS, R.string.rear_screen_off_timeout_always),
    ONE_MINUTE(60_000, R.string.rear_screen_off_timeout_one_minute),
    THREE_MINUTES(180_000, R.string.rear_screen_off_timeout_three_minutes),
    FIVE_MINUTES(300_000, R.string.rear_screen_off_timeout_five_minutes),
    TEN_MINUTES(600_000, R.string.rear_screen_off_timeout_ten_minutes),
    THIRTY_MINUTES(1_800_000, R.string.rear_screen_off_timeout_thirty_minutes);

    /** 跟随系统时不写系统设置，只清掉我们之前写进去的值。 */
    val overridesSystem: Boolean
        get() = timeoutMillis > 0

    val prefValue: String
        get() = timeoutMillis.toString()

    companion object {
        fun fromPref(value: String?): RearScreenOffTimeout =
            entries.firstOrNull { it.prefValue == value } ?: SYSTEM
    }
}
