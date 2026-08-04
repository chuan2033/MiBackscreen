package hook.HyperBackscreen.ui.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.BuildConfig
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.components.InfoRow
import hook.HyperBackscreen.ui.util.currentDeviceName
import hook.HyperBackscreen.ui.util.currentHyperOSVersion
import hook.HyperBackscreen.ui.util.currentSystemVersion
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun HomePage(
    disableLongPress: Boolean,
    removeWallpaperLimit: Boolean,
    fixRearScreenApply: Boolean,
    onDisableLongPressChange: (Boolean) -> Unit,
    onRemoveWallpaperLimitChange: (Boolean) -> Unit,
    onFixRearScreenApplyChange: (Boolean) -> Unit
) {
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
    }

    SmallTitle(text = stringResource(R.string.home_system_info_title), insideMargin = PaddingValues(16.dp, 8.dp))
    CardBlock {
        InfoRow(label = stringResource(R.string.home_module_version), value = BuildConfig.VERSION_NAME)
        InfoRow(label = stringResource(R.string.home_device), value = currentDeviceName())
        InfoRow(label = stringResource(R.string.home_system_version), value = currentHyperOSVersion())
        InfoRow(label = stringResource(R.string.home_android_version), value = currentSystemVersion())
    }
}
