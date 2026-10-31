package hook.HyperBackscreen.ui.function

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.AutoWallpaperInterval
import hook.HyperBackscreen.ui.AutoWallpaperOrder
import hook.HyperBackscreen.ui.RearScreenOffTimeout
import hook.HyperBackscreen.ui.RearScreenScheduleMode
import hook.HyperBackscreen.ui.RearScreenSchedulePrefs
import hook.HyperBackscreen.ui.components.AnimatedPreferenceVisibility
import hook.HyperBackscreen.ui.components.BlurredBar
import hook.HyperBackscreen.ui.components.CardBlock
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

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
    var editingSchedule by remember { mutableStateOf<ScheduleTimeField?>(null) }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = stringResource(R.string.rear_screen_timing_title),
                    largeTitle = stringResource(R.string.rear_screen_timing_title),
                    color = Color.Transparent,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MiuixTheme.colorScheme.onBackground
                            )
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.layerBackdrop(backdrop)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = paddingValues.calculateTopPadding())
            ) {
                item(key = "top_spacer") {
                    Spacer(Modifier.height(12.dp))
                }
                item(key = "sleep") {
                    CardBlock(pressFeedbackType = PressFeedbackType.None) {
                        WindowDropdownPreference(
                            items = RearScreenOffTimeout.entries.map { stringResource(it.labelRes) },
                            selectedIndex = rearScreenOffTimeout.ordinal,
                            title = stringResource(R.string.rear_screen_off_timeout_title),
                            onSelectedIndexChange = { index ->
                                RearScreenOffTimeout.entries.getOrNull(index)
                                    ?.let(onRearScreenOffTimeoutChange)
                            }
                        )
                        SwitchPreference(
                            checked = keepRearScreenOnInGame,
                            onCheckedChange = onKeepRearScreenOnInGameChange,
                            title = stringResource(R.string.keep_rear_screen_on_in_game_title),
                            summary = stringResource(R.string.keep_rear_screen_on_in_game_summary)
                        )
                    }
                }
                item(key = "schedule") {
                    CardBlock(pressFeedbackType = PressFeedbackType.None) {
                        WindowDropdownPreference(
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
                            WindowDropdownPreference(
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
                                onClick = { editingSchedule = ScheduleTimeField.START }
                            )
                            ArrowPreference(
                                title = stringResource(R.string.rear_screen_schedule_end_title),
                                summary = rearScreenSchedule.end,
                                onClick = { editingSchedule = ScheduleTimeField.END }
                            )
                            WindowDropdownPreference(
                                items = RearScreenScheduleMode.entries.map { stringResource(it.labelRes) },
                                selectedIndex = rearScreenSchedule.scheduleMode.ordinal,
                                title = stringResource(R.string.rear_screen_schedule_mode_title),
                                onSelectedIndexChange = { index ->
                                    RearScreenScheduleMode.entries.getOrNull(index)
                                        ?.let { mode ->
                                            onRearScreenScheduleChange(rearScreenSchedule.withMode(mode))
                                        }
                                }
                            )
                        }
                    }
                }
                item(key = "bottom_spacer") {
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    val editing = editingSchedule
    WindowDialog(
        show = editing != null,
        title = when (editing) {
            ScheduleTimeField.START -> stringResource(R.string.rear_screen_schedule_start_title)
            ScheduleTimeField.END -> stringResource(R.string.rear_screen_schedule_end_title)
            null -> ""
        },
        summary = stringResource(R.string.rear_screen_schedule_dialog_summary),
        onDismissRequest = { editingSchedule = null }
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
                    editingSchedule = null
                },
                onCancel = { editingSchedule = null }
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
