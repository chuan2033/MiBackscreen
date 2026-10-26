package hook.HyperBackscreen.ui

import androidx.annotation.StringRes
import hook.HyperBackscreen.R
import hook.HyperBackscreen.bridge.PrefsBridge
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.common.RearScreenSchedule

/**
 * 「定时关闭背屏」的三个偏好值。
 *
 * 打包成一个对象，是为了让 HomeScreen 的参数链只多两项（值 + 回调），
 * 不必为开关和两个时间各加一组参数。时间统一是 "HH:mm"。
 */
internal data class RearScreenSchedulePrefs(
    val enabled: Boolean = PrefsBridge.DEFAULT_REAR_SCREEN_SCHEDULE_ENABLED,
    val start: String = PrefsBridge.DEFAULT_REAR_SCREEN_SCHEDULE_START,
    val end: String = PrefsBridge.DEFAULT_REAR_SCREEN_SCHEDULE_END,
    val mode: String = PrefsBridge.DEFAULT_REAR_SCREEN_SCHEDULE_MODE,
) {
    val startMinutes: Int
        get() = RearScreenSchedule.parseMinutes(start, DEFAULT_START_MINUTES)

    val endMinutes: Int
        get() = RearScreenSchedule.parseMinutes(end, DEFAULT_END_MINUTES)

    val scheduleMode: RearScreenScheduleMode
        get() = RearScreenScheduleMode.fromPref(mode)

    fun withStart(minutes: Int): RearScreenSchedulePrefs =
        copy(start = RearScreenSchedule.format(minutes))

    fun withEnd(minutes: Int): RearScreenSchedulePrefs =
        copy(end = RearScreenSchedule.format(minutes))

    fun withMode(mode: RearScreenScheduleMode): RearScreenSchedulePrefs =
        copy(mode = mode.prefValue)

    companion object {
        private val DEFAULT_START_MINUTES =
            RearScreenSchedule.parseMinutes(PrefsBridge.DEFAULT_REAR_SCREEN_SCHEDULE_START, 23 * 60)
        private val DEFAULT_END_MINUTES =
            RearScreenSchedule.parseMinutes(PrefsBridge.DEFAULT_REAR_SCREEN_SCHEDULE_END, 7 * 60)
    }
}

/**
 * 时间窗内背屏停在哪一档。
 *
 * [prefValue] 直接写进 `rear_screen_schedule_mode`：`off` = 背屏休眠（纯黑），
 * `doze` = 停在息屏（息屏内容可见，但不会被点亮）。
 */
internal enum class RearScreenScheduleMode(
    val prefValue: String,
    @get:StringRes val labelRes: Int,
) {
    OFF(Constants.REAR_SCREEN_SCHEDULE_MODE_OFF, R.string.rear_screen_schedule_mode_off),
    DOZE(Constants.REAR_SCREEN_SCHEDULE_MODE_DOZE, R.string.rear_screen_schedule_mode_doze);

    companion object {
        fun fromPref(value: String?): RearScreenScheduleMode =
            entries.firstOrNull { it.prefValue == value } ?: OFF
    }
}
