package hook.HyperBackscreen.ui.function

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.PackageListCodec
import hook.HyperBackscreen.ui.components.AnimatedPreferenceVisibility
import hook.HyperBackscreen.ui.components.CardBlock
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun FunctionPage(
    disableLongPress: Boolean,
    removeWallpaperLimit: Boolean,
    fixRearScreenApply: Boolean,
    disableRearScreenCover: Boolean,
    disableDoubleTapWake: Boolean,
    doubleTapWakeDisabledPackages: String,
    enablePickup: Boolean,
    onDisableLongPressChange: (Boolean) -> Unit,
    onRemoveWallpaperLimitChange: (Boolean) -> Unit,
    onFixRearScreenApplyChange: (Boolean) -> Unit,
    onDisableRearScreenCoverChange: (Boolean) -> Unit,
    onDisableDoubleTapWakeChange: (Boolean) -> Unit,
    onEnablePickupChange: (Boolean) -> Unit,
    onAddDisabledAppsClick: () -> Unit
) {
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        SwitchPreference(
            checked = disableLongPress,
            onCheckedChange = onDisableLongPressChange,
            title = stringResource(R.string.home_disable_long_press_title),
            summary = stringResource(R.string.home_disable_long_press_summary)
        )
        SwitchPreference(
            checked = removeWallpaperLimit,
            onCheckedChange = onRemoveWallpaperLimitChange,
            title = stringResource(R.string.home_remove_wallpaper_limit_title),
            summary = stringResource(R.string.home_remove_wallpaper_limit_summary)
        )
        SwitchPreference(
            checked = fixRearScreenApply,
            onCheckedChange = onFixRearScreenApplyChange,
            title = stringResource(R.string.home_fix_apply_title),
            summary = stringResource(R.string.home_fix_apply_summary)
        )
        SwitchPreference(
            checked = disableRearScreenCover,
            onCheckedChange = onDisableRearScreenCoverChange,
            title = stringResource(R.string.config_disable_rear_cover_title),
            summary = stringResource(R.string.config_disable_rear_cover_summary)
        )
        SwitchPreference(
            checked = disableDoubleTapWake,
            onCheckedChange = onDisableDoubleTapWakeChange,
            title = stringResource(R.string.config_disable_double_tap_wake_title),
            summary = stringResource(R.string.config_disable_double_tap_wake_summary)
        )
        AnimatedPreferenceVisibility(visible = disableDoubleTapWake) {
            val disabledPackageCount = PackageListCodec.parse(doubleTapWakeDisabledPackages).size
            ArrowPreference(
                title = stringResource(R.string.config_add_apps_title),
                summary = if (disabledPackageCount == 0) {
                    stringResource(R.string.config_disabled_apps_empty)
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
        SwitchPreference(
            checked = enablePickup,
            onCheckedChange = onEnablePickupChange,
            title = stringResource(R.string.config_enable_pickup_title),
            summary = stringResource(R.string.config_enable_pickup_summary)
        )
    }
}
