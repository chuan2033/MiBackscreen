package hook.HyperBackscreen.ui.config

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.components.SettingsInfoRow
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun ConfigPage(
    modifier: Modifier = Modifier,
    disableLongPress: Boolean,
    removeWallpaperLimit: Boolean,
    fixRearScreenApply: Boolean,
    floatingNavBar: Boolean,
    liquidGlass: Boolean,
    onDisableLongPressChange: (Boolean) -> Unit,
    onRemoveWallpaperLimitChange: (Boolean) -> Unit,
    onFixRearScreenApplyChange: (Boolean) -> Unit,
    onFloatingNavBarChange: (Boolean) -> Unit,
    onLiquidGlassChange: (Boolean) -> Unit
) {
    SmallTitle(text = stringResource(R.string.config_nav_bar_title), insideMargin = PaddingValues(16.dp, 8.dp))
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        SwitchPreference(
            checked = floatingNavBar,
            onCheckedChange = onFloatingNavBarChange,
            title = stringResource(R.string.config_floating_bar_title),
            summary = if (floatingNavBar) stringResource(R.string.config_floating_bar_summary_on) else stringResource(R.string.config_floating_bar_summary_off)
        )
        SwitchPreference(
            checked = liquidGlass,
            onCheckedChange = onLiquidGlassChange,
            title = stringResource(R.string.config_liquid_glass_title),
            summary = if (liquidGlass) stringResource(R.string.config_liquid_glass_summary_on) else stringResource(R.string.config_liquid_glass_summary_off),
            enabled = floatingNavBar
        )
    }

    SmallTitle(text = stringResource(R.string.config_current_config_title), insideMargin = PaddingValues(16.dp, 8.dp))
    CardBlock {
        SettingsInfoRow(label = stringResource(R.string.config_long_press_disabled), value = if (disableLongPress) stringResource(R.string.config_status_on) else stringResource(R.string.config_status_off))
        SettingsInfoRow(label = stringResource(R.string.config_wallpaper_limit), value = if (removeWallpaperLimit) stringResource(R.string.config_status_removed) else stringResource(R.string.config_status_default))
        SettingsInfoRow(label = stringResource(R.string.config_apply_fix), value = if (fixRearScreenApply) stringResource(R.string.config_status_on) else stringResource(R.string.config_status_off))
        SettingsInfoRow(label = stringResource(R.string.config_bar_style), value = if (floatingNavBar) stringResource(R.string.config_status_floating) else stringResource(R.string.config_status_standard))
        SettingsInfoRow(label = stringResource(R.string.config_liquid_glass_status), value = if (liquidGlass) stringResource(R.string.config_status_on) else stringResource(R.string.config_status_off))
    }
}
