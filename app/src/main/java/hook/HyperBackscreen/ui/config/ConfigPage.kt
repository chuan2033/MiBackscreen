package hook.HyperBackscreen.ui.config

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.components.AnimatedPreferenceVisibility
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.util.AppLanguage
import hook.HyperBackscreen.ui.util.ThemeMode
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun ConfigPage(
    floatingNavBar: Boolean,
    liquidGlass: Boolean,
    bottomBarBlur: Boolean,
    showFunctionCount: Boolean,
    appLanguage: AppLanguage,
    checkUpdates: Boolean,
    enableAppCard: Boolean,
    launcherIconHidden: Boolean,
    onFloatingNavBarChange: (Boolean) -> Unit,
    onLiquidGlassChange: (Boolean) -> Unit,
    onBottomBarBlurChange: (Boolean) -> Unit,
    onShowFunctionCountChange: (Boolean) -> Unit,
    onAppLanguageChange: (AppLanguage) -> Unit,
    onCheckUpdatesChange: (Boolean) -> Unit,
    onEnableAppCardChange: (Boolean) -> Unit,
    onLauncherIconHiddenChange: (Boolean) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    themeSettingsShortcut: Boolean,
    onThemeSettingsShortcutChange: (Boolean) -> Unit
) {
    SmallTitle(text = stringResource(R.string.config_module_settings_title), insideMargin = PaddingValues(16.dp, 8.dp))
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        WindowDropdownPreference(
            items = listOf(
                stringResource(R.string.config_module_entry_disabled),
                stringResource(R.string.config_module_entry_theme_settings)
            ),
            selectedIndex = if (themeSettingsShortcut) 1 else 0,
            title = stringResource(R.string.config_module_entry_title),
            onSelectedIndexChange = { index ->
                onThemeSettingsShortcutChange(index == 1)
            }
        )
        SwitchPreference(
            checked = enableAppCard,
            onCheckedChange = onEnableAppCardChange,
            title = stringResource(R.string.config_enable_app_card_title),
            summary = stringResource(R.string.config_enable_app_card_summary)
        )
        SwitchPreference(
            checked = launcherIconHidden,
            onCheckedChange = onLauncherIconHiddenChange,
            title = stringResource(R.string.config_hide_launcher_icon)
        )
        SwitchPreference(
            checked = checkUpdates,
            onCheckedChange = onCheckUpdatesChange,
            title = stringResource(R.string.config_check_updates_title),
            summary = if (checkUpdates) {
                stringResource(R.string.config_check_updates_summary_on)
            } else {
                stringResource(R.string.config_check_updates_summary_off)
            }
        )
        WindowDropdownPreference(
            items = listOf(
                stringResource(R.string.config_language_default),
                stringResource(R.string.config_language_chinese),
                stringResource(R.string.config_language_english)
            ),
            selectedIndex = appLanguage.index,
            title = stringResource(R.string.config_language_title),
            summary = when (appLanguage) {
                AppLanguage.SYSTEM -> stringResource(R.string.config_language_default)
                AppLanguage.CHINESE -> stringResource(R.string.config_language_chinese)
                AppLanguage.ENGLISH -> stringResource(R.string.config_language_english)
            },
            onSelectedIndexChange = { index ->
                onAppLanguageChange(AppLanguage.fromIndex(index))
            }
        )
    }

    SmallTitle(text = stringResource(R.string.config_appearance_title), insideMargin = PaddingValues(16.dp, 8.dp))
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        WindowDropdownPreference(
            items = listOf(
                stringResource(R.string.config_theme_mode_system),
                stringResource(R.string.config_theme_mode_light),
                stringResource(R.string.config_theme_mode_dark),
                stringResource(R.string.config_theme_mode_monet_system),
                stringResource(R.string.config_theme_mode_monet_light),
                stringResource(R.string.config_theme_mode_monet_dark)
            ),
            selectedIndex = themeMode.index,
            title = stringResource(R.string.config_theme_mode_title),
            onSelectedIndexChange = { index ->
                onThemeModeChange(ThemeMode.fromIndex(index))
            }
        )
        SwitchPreference(
            checked = floatingNavBar,
            onCheckedChange = onFloatingNavBarChange,
            title = stringResource(R.string.config_floating_bar_title),
            summary = stringResource(R.string.config_floating_bar_summary)
        )
        AnimatedPreferenceVisibility(visible = floatingNavBar) {
            SwitchPreference(
                checked = liquidGlass,
                onCheckedChange = onLiquidGlassChange,
                title = stringResource(R.string.config_liquid_glass_title),
                summary = stringResource(R.string.config_liquid_glass_summary)
            )
        }
        SwitchPreference(
            checked = bottomBarBlur,
            onCheckedChange = onBottomBarBlurChange,
            title = stringResource(R.string.config_bottom_bar_blur_title),
            summary = stringResource(R.string.config_bottom_bar_blur_summary)
        )
        SwitchPreference(
            checked = showFunctionCount,
            onCheckedChange = onShowFunctionCountChange,
            title = stringResource(R.string.config_show_function_count_title),
            summary = stringResource(R.string.config_show_function_count_summary)
        )
    }
}
