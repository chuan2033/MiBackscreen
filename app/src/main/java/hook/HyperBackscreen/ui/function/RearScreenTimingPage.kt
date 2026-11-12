package hook.HyperBackscreen.ui.function

import hook.HyperBackscreen.ui.components.AppDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import hook.HyperBackscreen.ui.components.DetailPageScaffold
import hook.HyperBackscreen.ui.components.AppGlassTabRow
import top.yukonga.miuix.kmp.basic.BasicComponent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.model.AutoWallpaperInterval
import hook.HyperBackscreen.model.AutoWallpaperOrder
import hook.HyperBackscreen.model.RearScreenOffTimeout
import hook.HyperBackscreen.model.RearScreenScheduleMode
import hook.HyperBackscreen.model.RearScreenSchedulePrefs
import hook.HyperBackscreen.ui.components.AnimatedPreferenceVisibility
import hook.HyperBackscreen.ui.components.CardBlock
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import hook.HyperBackscreen.ui.components.AppDropdownPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「息屏与定时」二级页：管背屏什么时候灭、多久灭、多久换一次壁纸。
 *
 * 两张卡片按「屏幕本身」和「定时任务」分开：前一张是息屏档位与游戏时的例外，
 * 后一张是两个定时开关，其中一个展开出时间窗。
 */
@Composable
internal fun RearScreenTimingPage(
    rearScreenOffTimeout: RearScreenOffTimeout,
    rearScreenSchedule: RearScreenSchedulePrefs,
    keepRearScreenOnInGame: Boolean,
    autoWallpaperInterval: AutoWallpaperInterval,
    autoWallpaperOrder: AutoWallpaperOrder,
    onRearScreenOffTimeoutChange: (RearScreenOffTimeout) -> Unit,
    onRearScreenScheduleChange: (RearScreenSchedulePrefs) -> Unit,
    onKeepRearScreenOnInGameChange: (Boolean) -> Unit,
    onAutoWallpaperIntervalChange: (AutoWallpaperInterval) -> Unit,
    onAutoWallpaperOrderChange: (AutoWallpaperOrder) -> Unit,
    onBack: () -> Unit
) {
    // 关闭时间与开启时间共用同一个弹窗，只换标题与初值。
    // 显示与内容分开：关闭时只收 show，字段等退场动画结束（onDismissFinished）再清，
    // 否则退场期间标题变空、内容塌掉，弹窗会先缩一下再消失。
    var editingSchedule by remember { mutableStateOf<ScheduleTimeField?>(null) }
    var showScheduleDialog by remember { mutableStateOf(false) }

    DetailPageScaffold(
        title = stringResource(R.string.rear_screen_timing_title),
        onBack = onBack,
    ) {
        item(key = "sleep") {
            CardBlock(pressFeedbackType = PressFeedbackType.None) {
                AppDropdownPreference(
                    items = RearScreenOffTimeout.entries.map { stringResource(it.labelRes) },
                    selectedIndex = rearScreenOffTimeout.ordinal,
                    title = stringResource(R.string.rear_screen_off_timeout_title),
                    onSelectedIndexChange = { index ->
                        RearScreenOffTimeout.entries.getOrNull(index)
                            ?.let(onRearScreenOffTimeoutChange)
                    }
                )
                // 只有息屏时间为「跟随系统」时这个开关才起作用（其它档位已经覆盖了超时），
                // 条件不满足时直接置灰并写明原因，而不是让用户开了却没效果。
                val keepOnAvailable = rearScreenOffTimeout == RearScreenOffTimeout.SYSTEM
                SwitchPreference(
                    checked = keepRearScreenOnInGame,
                    onCheckedChange = onKeepRearScreenOnInGameChange,
                    title = stringResource(R.string.keep_rear_screen_on_in_game_title),
                    summary = stringResource(
                        if (keepOnAvailable) R.string.keep_rear_screen_on_in_game_summary
                        else R.string.keep_rear_screen_on_in_game_unavailable
                    ),
                    enabled = keepOnAvailable
                )
            }
        }
        item(key = "schedule") {
            CardBlock(pressFeedbackType = PressFeedbackType.None) {
                AppDropdownPreference(
                    items = AutoWallpaperInterval.entries.map { stringResource(it.labelRes) },
                    selectedIndex = autoWallpaperInterval.ordinal,
                    title = stringResource(R.string.auto_wallpaper_interval_title),
                    onSelectedIndexChange = { index ->
                        AutoWallpaperInterval.entries.getOrNull(index)
                            ?.let(onAutoWallpaperIntervalChange)
                    }
                )
                // 顺序 / 随机只在真的会切换时才有意义，关掉就收起，免得留一个死选项。
                AnimatedPreferenceVisibility(
                    visible = autoWallpaperInterval != AutoWallpaperInterval.OFF
                ) {
                    AppDropdownPreference(
                        items = AutoWallpaperOrder.entries.map { stringResource(it.labelRes) },
                        selectedIndex = autoWallpaperOrder.ordinal,
                        title = stringResource(R.string.auto_wallpaper_order_title),
                        onSelectedIndexChange = { index ->
                            AutoWallpaperOrder.entries.getOrNull(index)
                                ?.let(onAutoWallpaperOrderChange)
                        }
                    )
                }
                SwitchPreference(
                    checked = rearScreenSchedule.enabled,
                    onCheckedChange = { enabled ->
                        onRearScreenScheduleChange(rearScreenSchedule.copy(enabled = enabled))
                    },
                    title = stringResource(R.string.rear_screen_schedule_title),
                    summary = scheduleSummary(rearScreenSchedule)
                )
                AnimatedPreferenceVisibility(visible = rearScreenSchedule.enabled) {
                    ArrowPreference(
                        title = stringResource(R.string.rear_screen_schedule_start_title),
                        summary = rearScreenSchedule.start,
                        onClick = { editingSchedule = ScheduleTimeField.START; showScheduleDialog = true }
                    )
                    ArrowPreference(
                        title = stringResource(R.string.rear_screen_schedule_end_title),
                        summary = rearScreenSchedule.end,
                        onClick = { editingSchedule = ScheduleTimeField.END; showScheduleDialog = true }
                    )
                    // 只有两个选项，用分段控件一眼看全，比下拉少点一次。
                    BasicComponent(
                        title = stringResource(R.string.rear_screen_schedule_mode_title),
                        bottomAction = {
                            AppGlassTabRow(
                                tabs = RearScreenScheduleMode.entries.map { stringResource(it.labelRes) },
                                selectedIndex = rearScreenSchedule.scheduleMode.ordinal,
                                onSelect = { index ->
                                    RearScreenScheduleMode.entries.getOrNull(index)
                                        ?.let { mode ->
                                            onRearScreenScheduleChange(rearScreenSchedule.withMode(mode))
                                        }
                                },
                                backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                                segmented = true,
                            )
                        }
                    )
                }
            }
        }
    }

    val editing = editingSchedule
    AppDialog(
        show = showScheduleDialog && editing != null,
        title = when (editing) {
            ScheduleTimeField.START -> stringResource(R.string.rear_screen_schedule_start_title)
            ScheduleTimeField.END -> stringResource(R.string.rear_screen_schedule_end_title)
            null -> ""
        },
        summary = stringResource(R.string.rear_screen_schedule_dialog_summary),
        onDismissRequest = { showScheduleDialog = false },
        onDismissFinished = { if (!showScheduleDialog) editingSchedule = null }
    ) {
        editing?.let { field ->
            val initial = when (field) {
                ScheduleTimeField.START -> rearScreenSchedule.startMinutes
                ScheduleTimeField.END -> rearScreenSchedule.endMinutes
            }
            RearScreenScheduleTimeDialog(
                initialMinutes = initial,
                onApply = { minutes ->
                    onRearScreenScheduleChange(
                        when (field) {
                            ScheduleTimeField.START -> rearScreenSchedule.withStart(minutes)
                            ScheduleTimeField.END -> rearScreenSchedule.withEnd(minutes)
                        }
                    )
                    showScheduleDialog = false
                },
                onCancel = { showScheduleDialog = false }
            )
        }
    }
}

/** 时间选择弹窗当前在改哪一项。 */
private enum class ScheduleTimeField { START, END }

/**
 * 开关副标题：直接写出当前时间窗，跨零点时用「次日」标出来。
 * 起止相同（例如都设成 23:00）时说明这个窗口没有意义，避免用户以为生效了。
 */
@Composable
private fun scheduleSummary(schedule: RearScreenSchedulePrefs): String {
    if (schedule.startMinutes == schedule.endMinutes) {
        // 起止相同没有窗口，说明白，免得用户以为设了之后夜里会不亮。
        return stringResource(R.string.rear_screen_schedule_summary_disabled)
    }
    return if (schedule.startMinutes < schedule.endMinutes) {
        stringResource(
            R.string.rear_screen_schedule_summary_same_day,
            schedule.start,
            schedule.end
        )
    } else {
        stringResource(
            R.string.rear_screen_schedule_summary_next_day,
            schedule.start,
            schedule.end
        )
    }
}
