package hook.HyperBackscreen.ui.config

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.components.CardBlock
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun ConfigPage(
    floatingNavBar: Boolean,
    liquidGlass: Boolean,
    enableSwipePanel: Boolean,
    launcherIconHidden: Boolean,
    onFloatingNavBarChange: (Boolean) -> Unit,
    onLiquidGlassChange: (Boolean) -> Unit,
    onEnableSwipePanelChange: (Boolean) -> Unit,
    onLauncherIconHiddenChange: (Boolean) -> Unit
) {
    SmallTitle(text = stringResource(R.string.config_module_settings_title), insideMargin = PaddingValues(16.dp, 8.dp))
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        SwitchPreference(
            checked = launcherIconHidden,
            onCheckedChange = onLauncherIconHiddenChange,
            title = stringResource(R.string.config_hide_launcher_icon)
        )
        SwitchPreference(
            checked = enableSwipePanel,
            onCheckedChange = onEnableSwipePanelChange,
            title = stringResource(R.string.config_swipe_panel_title),
            summary = if (enableSwipePanel) {
                stringResource(R.string.config_swipe_panel_summary_on)
            } else {
                stringResource(R.string.config_swipe_panel_summary_off)
            }
        )
        SwitchPreference(
            checked = floatingNavBar,
            onCheckedChange = onFloatingNavBarChange,
            title = stringResource(R.string.config_floating_bar_title),
            summary = if (floatingNavBar) {
                stringResource(R.string.config_floating_bar_summary_on)
            } else {
                stringResource(R.string.config_floating_bar_summary_off)
            }
        )
        if (floatingNavBar) {
            SwitchPreference(
                checked = liquidGlass,
                onCheckedChange = onLiquidGlassChange,
                title = stringResource(R.string.config_liquid_glass_title),
                summary = if (liquidGlass) {
                    stringResource(R.string.config_liquid_glass_summary_on)
                } else {
                    stringResource(R.string.config_liquid_glass_summary_off)
                }
            )
        }
    }
}
