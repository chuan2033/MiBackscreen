package hook.HyperBackscreen.ui.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.BuildConfig
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.PackageListCodec
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.components.InfoRow
import hook.HyperBackscreen.ui.util.currentDeviceName
import hook.HyperBackscreen.ui.util.currentHyperOSVersion
import hook.HyperBackscreen.ui.util.currentSystemVersion
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun HomePage(
    disableLongPress: Boolean,
    removeWallpaperLimit: Boolean,
    fixRearScreenApply: Boolean,
    disableRearScreenCover: Boolean,
    disableDoubleTapWake: Boolean,
    doubleTapWakeDisabledPackages: String,
    moduleActivated: Boolean,
    onDisableLongPressChange: (Boolean) -> Unit,
    onRemoveWallpaperLimitChange: (Boolean) -> Unit,
    onFixRearScreenApplyChange: (Boolean) -> Unit,
    onDisableRearScreenCoverChange: (Boolean) -> Unit,
    onDisableDoubleTapWakeChange: (Boolean) -> Unit,
    onAddDisabledAppsClick: () -> Unit
) {
    if (!moduleActivated) {
        CardBlock(pressFeedbackType = PressFeedbackType.None) {
            Text(
                text = stringResource(R.string.home_module_not_activated),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                color = Color(0xFFFF3B30)
            )
        }
    }

    SmallTitle(text = stringResource(R.string.home_settings_title), insideMargin = PaddingValues(16.dp, 8.dp))
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        SwitchPreference(
            checked = disableLongPress,
            onCheckedChange = onDisableLongPressChange,
            title = stringResource(R.string.home_disable_long_press_title),
            summary = if (disableLongPress) {
                stringResource(R.string.home_disable_long_press_summary_on)
            } else {
                stringResource(R.string.home_disable_long_press_summary_off)
            }
        )
        SwitchPreference(
            checked = removeWallpaperLimit,
            onCheckedChange = onRemoveWallpaperLimitChange,
            title = stringResource(R.string.home_remove_wallpaper_limit_title),
            summary = if (removeWallpaperLimit) {
                stringResource(R.string.home_remove_wallpaper_limit_summary_on)
            } else {
                stringResource(R.string.home_remove_wallpaper_limit_summary_off)
            }
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
            summary = if (disableRearScreenCover) {
                stringResource(R.string.config_disable_rear_cover_summary_on)
            } else {
                stringResource(R.string.config_disable_rear_cover_summary_off)
            }
        )
        SwitchPreference(
            checked = disableDoubleTapWake,
            onCheckedChange = onDisableDoubleTapWakeChange,
            title = stringResource(R.string.config_disable_double_tap_wake_title),
            summary = if (disableDoubleTapWake) {
                stringResource(R.string.config_disable_double_tap_wake_summary_on)
            } else {
                stringResource(R.string.config_disable_double_tap_wake_summary_off)
            }
        )
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

    SmallTitle(text = stringResource(R.string.home_system_info_title), insideMargin = PaddingValues(16.dp, 8.dp))
    CardBlock {
        InfoRow(label = stringResource(R.string.home_module_version), value = BuildConfig.VERSION_NAME)
        InfoRow(label = stringResource(R.string.home_device), value = currentDeviceName())
        InfoRow(label = stringResource(R.string.home_system_version), value = currentHyperOSVersion())
        InfoRow(label = stringResource(R.string.home_android_version), value = currentSystemVersion())
    }
}
