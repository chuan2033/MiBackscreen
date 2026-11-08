package hook.HyperBackscreen.ui.config

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.theme.LocalThemeState
import hook.HyperBackscreen.ui.theme.keyColorOptions
import hook.HyperBackscreen.ui.theme.UiStyle
import hook.HyperBackscreen.ui.util.AppLanguage
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import hook.HyperBackscreen.ui.components.AppDropdownPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun ConfigPage(
    appLanguage: AppLanguage,
    enableAppCard: Boolean,
    launcherIconHidden: Boolean,
    onThemeClick: () -> Unit,
    onAppLanguageChange: (AppLanguage) -> Unit,
    onEnableAppCardChange: (Boolean) -> Unit,
    onLauncherIconHiddenChange: (Boolean) -> Unit,
    themeSettingsShortcut: Boolean,
    onThemeSettingsShortcutChange: (Boolean) -> Unit,
    settingsEntry: String,
    onSettingsEntryChange: (String) -> Unit,
    onBackupPreferences: () -> Unit,
    onRestorePreferences: () -> Unit
) {
    val context = LocalContext.current

    SmallTitle(text = stringResource(R.string.config_module_settings_title), insideMargin = PaddingValues(16.dp, 8.dp))
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        // 模块入口只选一处：妙享背屏里的入口，或系统「设置」首页的顶部 / 中部 / 底部。
        val entryPositions = listOf("top", "middle", "bottom")
        AppDropdownPreference(
            items = listOf(
                stringResource(R.string.config_module_entry_disabled),
                stringResource(R.string.config_module_entry_theme_settings),
                stringResource(R.string.config_module_entry_settings_top),
                stringResource(R.string.config_module_entry_settings_middle),
                stringResource(R.string.config_module_entry_settings_bottom)
            ),
            selectedIndex = when {
                settingsEntry in entryPositions -> 2 + entryPositions.indexOf(settingsEntry)
                themeSettingsShortcut -> 1
                else -> 0
            },
            title = stringResource(R.string.config_module_entry_title),
            onSelectedIndexChange = { index ->
                val position = if (index >= 2) entryPositions[index - 2] else "off"
                if ((index == 1) != themeSettingsShortcut) onThemeSettingsShortcutChange(index == 1)
                if (position != settingsEntry) onSettingsEntryChange(position)
            }
        )
        ArrowPreference(
            title = stringResource(R.string.config_enable_app_card_title),
            summary = stringResource(R.string.config_enable_app_card_summary),
            onClick = { onEnableAppCardChange(true) }
        )
        SwitchPreference(
            checked = launcherIconHidden,
            onCheckedChange = onLauncherIconHiddenChange,
            title = stringResource(R.string.config_hide_launcher_icon)
        )
        AppDropdownPreference(
            items = listOf(
                stringResource(R.string.config_language_default),
                stringResource(R.string.config_language_chinese),
                stringResource(R.string.config_language_english)
            ),
            selectedIndex = appLanguage.index,
            title = stringResource(R.string.config_language_title),
            onSelectedIndexChange = { index ->
                onAppLanguageChange(AppLanguage.fromIndex(index))
            }
        )
    }

    SmallTitle(text = stringResource(R.string.config_appearance_title), insideMargin = PaddingValues(16.dp, 8.dp))
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        // 颜色、Monet 强调色、界面风格、底栏与手势都收进「主题」页（移植自 KernelSU），这里只给入口和当前状态。
        ArrowPreference(
            title = stringResource(R.string.theme_title),
            summary = themeSummary(),
            onClick = onThemeClick
        )
    }

    SmallTitle(text = stringResource(R.string.config_backup_title), insideMargin = PaddingValues(16.dp, 8.dp))
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        ArrowPreference(
            title = stringResource(R.string.config_backup_action_title),
            summary = stringResource(R.string.config_backup_action_summary, PreferencesBackup.FILE_NAME),
            onClick = onBackupPreferences
        )
        ArrowPreference(
            title = stringResource(R.string.config_restore_action_title),
            summary = stringResource(R.string.config_restore_action_summary, PreferencesBackup.FILE_NAME),
            onClick = onRestorePreferences
        )
    }
}

/** 「主题」入口的摘要：明暗 · Monet / 强调色 · 界面风格。 */
@Composable
private fun themeSummary(): String {
    val state = LocalThemeState.current ?: return ""
    val brightness = stringResource(
        when (state.themeMode.brightnessIndex) {
            1 -> R.string.config_theme_mode_light
            2 -> R.string.config_theme_mode_dark
            else -> R.string.config_theme_mode_system
        }
    )
    val color = if (state.monet) {
        keyColorOptions.firstOrNull { it.argb == state.keyColor }?.let { stringResource(it.labelRes) }
            ?: stringResource(R.string.theme_monet_short)
    } else {
        null
    }
    val style = stringResource(
        when (state.uiStyle) {
            UiStyle.DEFAULT -> R.string.config_ui_style_default
            UiStyle.MOCHI -> R.string.config_ui_style_mochi
        }
    )
    return listOfNotNull(brightness, color, style).joinToString(" · ")
}
