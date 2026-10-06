package hook.HyperBackscreen.ui.function

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.PackageListCodec
import hook.HyperBackscreen.ui.battery.BatteryRingGeometry
import hook.HyperBackscreen.ui.components.AnimatedPreferenceVisibility
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.components.CollapsibleFunctionGroup
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun FunctionPage(
    enableBatteryRing: Boolean,
    onEnableBatteryRingChange: (Boolean) -> Unit,
    onBatteryColorClick: () -> Unit,
    enable18ProFeatures: Boolean,
    onEnable18ProFeaturesChange: (Boolean) -> Unit,
    disableLongPress: Boolean,
    removeWallpaperLimit: Boolean,
    removeAppCardLimit: Boolean,
    fixRearScreenApply: Boolean,
    disableRearScreenCover: Boolean,
    doubleTapWakeDisabledPackages: String,
    enablePickup: Boolean,
    onDisableLongPressChange: (Boolean) -> Unit,
    onRemoveWallpaperLimitChange: (Boolean) -> Unit,
    onRemoveAppCardLimitChange: (Boolean) -> Unit,
    onFixRearScreenApplyChange: (Boolean) -> Unit,
    onDisableRearScreenCoverChange: (Boolean) -> Unit,
    onEnablePickupChange: (Boolean) -> Unit,
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
                // 几何按校准机型实测，其他机型背屏不绘制，这里也不显示无法生效的开关。
                if (batteryRingSupported) {
                    SwitchPreference(
                        checked = enableBatteryRing,
                        onCheckedChange = onEnableBatteryRingChange,
                        title = stringResource(R.string.home_battery_ring_title)
                    )
                    AnimatedPreferenceVisibility(visible = enableBatteryRing) {
                        ArrowPreference(
                            title = stringResource(R.string.function_battery_color_title),
                            summary = stringResource(R.string.function_battery_color_summary),
                            onClick = onBatteryColorClick
                        )
                    }
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
                    checked = removeWallpaperLimit,
                    onCheckedChange = onRemoveWallpaperLimitChange,
                    title = stringResource(R.string.home_remove_wallpaper_limit_title)
                )
            }

            CollapsibleFunctionGroup(
                title = stringResource(R.string.function_group_voice),
                iconRes = R.drawable.group_voice
            ) {
                SwitchPreference(
                    checked = enablePickup,
                    onCheckedChange = onEnablePickupChange,
                    title = stringResource(R.string.config_enable_pickup_title),
                    summary = stringResource(R.string.config_enable_pickup_summary)
                )
            }
        }
    }
}
