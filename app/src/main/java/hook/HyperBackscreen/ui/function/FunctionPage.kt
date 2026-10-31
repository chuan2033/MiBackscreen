package hook.HyperBackscreen.ui.function

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.PackageListCodec
import hook.HyperBackscreen.ui.battery.BatteryRingGeometry
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.components.CollapsibleFunctionGroup
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun FunctionPage(
    onBatteryColorClick: () -> Unit,
    onRearScreenTimingClick: () -> Unit,
    enable18ProFeatures: Boolean,
    onEnable18ProFeaturesChange: (Boolean) -> Unit,
    disableLongPress: Boolean,
    removeWallpaperLimit: Boolean,
    removeAppCardLimit: Boolean,
    fixRearScreenApply: Boolean,
    faceEnrollmentCompat: Boolean,
    disableRearScreenCover: Boolean,
    doubleTapWakeDisabledPackages: String,
    onDisableLongPressChange: (Boolean) -> Unit,
    onRemoveWallpaperLimitChange: (Boolean) -> Unit,
    onRemoveAppCardLimitChange: (Boolean) -> Unit,
    onFixRearScreenApplyChange: (Boolean) -> Unit,
    onFaceEnrollmentCompatChange: (Boolean) -> Unit,
    onDisableRearScreenCoverChange: (Boolean) -> Unit,
    onAddDisabledAppsClick: () -> Unit
) {
    val batteryRingSupported = BatteryRingGeometry.isCalibratedDevice(Build.DEVICE)
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
                    // 名单为空时总开关也是关的（写名单时自动推导），这里把空状态说清楚，
                    // 免得用户以为「点进去什么都不选也在拦」。
                    summary = if (disabledPackageCount == 0) {
                        stringResource(R.string.config_disabled_apps_count_none)
                    } else {
                        pluralStringResource(
                            R.plurals.config_disabled_apps_count,
                            disabledPackageCount,
                            disabledPackageCount
                        )
                    },
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
                // 息屏档位、两个定时开关、游戏时保持常亮都收进二级页，这里只留入口。
                ArrowPreference(
                    title = stringResource(R.string.rear_screen_timing_title),
                    onClick = onRearScreenTimingClick
                )
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
}
