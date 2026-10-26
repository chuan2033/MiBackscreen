package hook.HyperBackscreen.ui.function

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.PackageListCodec
import hook.HyperBackscreen.ui.AutoWallpaperInterval
import hook.HyperBackscreen.ui.RearScreenOffTimeout
import hook.HyperBackscreen.ui.RearScreenScheduleMode
import hook.HyperBackscreen.ui.RearScreenSchedulePrefs
import hook.HyperBackscreen.ui.battery.BatteryRingGeometry
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.components.CollapsibleFunctionGroup
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun FunctionPage(
    onBatteryColorClick: () -> Unit,
    enable18ProFeatures: Boolean,
    onEnable18ProFeaturesChange: (Boolean) -> Unit,
    disableLongPress: Boolean,
    removeWallpaperLimit: Boolean,
    removeAppCardLimit: Boolean,
    autoWallpaperInterval: AutoWallpaperInterval,
    rearScreenOffTimeout: RearScreenOffTimeout,
    rearScreenSchedule: RearScreenSchedulePrefs,
    keepRearScreenOnInGame: Boolean,
    fixRearScreenApply: Boolean,
    faceEnrollmentCompat: Boolean,
    disableRearScreenCover: Boolean,
    doubleTapWakeDisabledPackages: String,
    onDisableLongPressChange: (Boolean) -> Unit,
    onRemoveWallpaperLimitChange: (Boolean) -> Unit,
    onRemoveAppCardLimitChange: (Boolean) -> Unit,
    onAutoWallpaperIntervalChange: (AutoWallpaperInterval) -> Unit,
    onRearScreenOffTimeoutChange: (RearScreenOffTimeout) -> Unit,
    onRearScreenScheduleChange: (RearScreenSchedulePrefs) -> Unit,
    onKeepRearScreenOnInGameChange: (Boolean) -> Unit,
    onFixRearScreenApplyChange: (Boolean) -> Unit,
    onFaceEnrollmentCompatChange: (Boolean) -> Unit,
    onDisableRearScreenCoverChange: (Boolean) -> Unit,
    onAddDisabledAppsClick: () -> Unit
) {
    val batteryRingSupported = BatteryRingGeometry.isCalibratedDevice(Build.DEVICE)
    // 时间选择弹窗挂在这里：下面两个箭头项共用同一个弹窗，只换标题与初值。
    var editingSchedule by remember { mutableStateOf<ScheduleTimeField?>(null) }
    Column {
        // 跨作用域的总开关：同时涉及主题商店、背屏中心与智能助理，不归入任何单个分组。
        CardBlock(pressFeedbackType = PressFeedbackType.None) {
            SwitchPreference(
                checked = enable18ProFeatures,
                onCheckedChange = onEnable18ProFeaturesChange,
                title = stringResource(R.string.home_enable_18_pro_features_title)
            )
        }

        // 下列分组与 Xposed 作用域一一对应：系统框架、背屏中心、主题商店、超级小爱。
        // 四组共用同一个卡片容器，展开内容在容器内展开。
        CardBlock(pressFeedbackType = PressFeedbackType.None) {
            CollapsibleFunctionGroup(
                title = stringResource(R.string.function_group_system),
                iconRes = R.drawable.group_system
            ) {
                SwitchPreference(
                    checked = disableRearScreenCover,
                    onCheckedChange = onDisableRearScreenCoverChange,
                    title = stringResource(R.string.config_disable_rear_cover_title),
                    summary = stringResource(R.string.config_disable_rear_cover_summary)
                )
                val disabledPackageCount = PackageListCodec.parse(doubleTapWakeDisabledPackages).size
                ArrowPreference(
                    title = stringResource(R.string.config_disable_double_tap_wake_title),
                    summary = pluralStringResource(
                        R.plurals.config_disabled_apps_count,
                        disabledPackageCount,
                        disabledPackageCount
                    ),
                    onClick = onAddDisabledAppsClick
                )
            }

            CollapsibleFunctionGroup(
                title = stringResource(R.string.function_group_subscreen),
                iconRes = R.drawable.group_subscreen
            ) {
                // 几何按校准机型实测，其他机型背屏不绘制，入口也不显示。
                // 电量总开关已经收到二级页里，这里只留常驻入口。
                if (batteryRingSupported) {
                    ArrowPreference(
                        title = stringResource(R.string.function_battery_color_title),
                        onClick = onBatteryColorClick
                    )
                }
                SwitchPreference(
                    checked = disableLongPress,
                    onCheckedChange = onDisableLongPressChange,
                    title = stringResource(R.string.home_disable_long_press_title)
                )
                SwitchPreference(
                    checked = removeAppCardLimit,
                    onCheckedChange = onRemoveAppCardLimitChange,
                    title = stringResource(R.string.home_remove_app_card_limit_title)
                )
                WindowDropdownPreference(
                    items = AutoWallpaperInterval.entries.map { stringResource(it.labelRes) },
                    selectedIndex = autoWallpaperInterval.ordinal,
                    title = stringResource(R.string.auto_wallpaper_interval_title),
                    onSelectedIndexChange = { index ->
                        AutoWallpaperInterval.entries.getOrNull(index)
                            ?.let(onAutoWallpaperIntervalChange)
                    }
                )
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
                SwitchPreference(
                    checked = rearScreenSchedule.enabled,
                    onCheckedChange = { enabled ->
                        onRearScreenScheduleChange(rearScreenSchedule.copy(enabled = enabled))
                    },
                    title = stringResource(R.string.rear_screen_schedule_title),
                    summary = scheduleSummary(rearScreenSchedule)
                )
                if (rearScreenSchedule.enabled) {
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

            CollapsibleFunctionGroup(
                title = stringResource(R.string.function_group_theme),
                iconRes = R.drawable.group_theme
            ) {
                SwitchPreference(
                    checked = fixRearScreenApply,
                    onCheckedChange = onFixRearScreenApplyChange,
                    title = stringResource(R.string.home_fix_apply_title),
                    summary = stringResource(R.string.home_fix_apply_summary)
                )
                SwitchPreference(
                    checked = faceEnrollmentCompat,
                    onCheckedChange = onFaceEnrollmentCompatChange,
                    title = stringResource(R.string.face_enrollment_compat_title),
                    summary = stringResource(R.string.face_enrollment_compat_summary)
                )
                SwitchPreference(
                    checked = removeWallpaperLimit,
                    onCheckedChange = onRemoveWallpaperLimitChange,
                    title = stringResource(R.string.home_remove_wallpaper_limit_title)
                )
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
        return stringResource(
            R.string.rear_screen_schedule_summary_same_day,
            schedule.start,
            schedule.end
        ) + " " + stringResource(R.string.rear_screen_schedule_summary_disabled)
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
