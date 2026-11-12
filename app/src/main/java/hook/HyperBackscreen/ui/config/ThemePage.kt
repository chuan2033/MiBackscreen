package hook.HyperBackscreen.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.components.AnimatedPreferenceVisibility
import hook.HyperBackscreen.ui.components.AppGlassTabRow
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.components.DetailPageScaffold
import hook.HyperBackscreen.ui.theme.LocalThemeState
import hook.HyperBackscreen.ui.theme.BottomBarStyle
import hook.HyperBackscreen.ui.theme.ThemeState
import hook.HyperBackscreen.ui.theme.UiStyle
import hook.HyperBackscreen.ui.theme.keyColorOptions
import hook.HyperBackscreen.ui.theme.labelRes
import hook.HyperBackscreen.ui.theme.supportsSpec2025
import hook.HyperBackscreen.ui.util.ThemePrefs
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import hook.HyperBackscreen.ui.components.AppDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import kotlin.math.roundToInt

/**
 * 主题页，移植自 KernelSU 管理器的「主题」页（ColorPaletteScreenMiuix），按本模块适配：
 * - 颜色部分与 KernelSU 一致：明暗三段 + Monet，Monet 下可选强调色，选了强调色再给色彩风格和色彩标准；
 * - 多了本模块的「界面风格」（默认 / Mochi）；
 * - 底栏相关沿用模块原来的四个开关，另加 KernelSU 的顶栏模糊、横滑返回、页面缩放。
 * 所有改动即时生效。
 */
@Composable
internal fun ThemePage(
    bottomBarStyle: BottomBarStyle,
    bottomBarBlur: Boolean,
    showFunctionCount: Boolean,
    onBottomBarStyleChange: (BottomBarStyle) -> Unit,
    onBottomBarBlurChange: (Boolean) -> Unit,
    onShowFunctionCountChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val state = LocalThemeState.current ?: return
    DetailPageScaffold(
        title = stringResource(R.string.theme_title),
        onBack = onBack,
    ) {
        item(key = "brightness") {
            AppGlassTabRow(
                tabs = listOf(
                    stringResource(R.string.config_theme_mode_system),
                    stringResource(R.string.config_theme_mode_light),
                    stringResource(R.string.config_theme_mode_dark),
                ),
                selectedIndex = state.themeMode.brightnessIndex,
                onSelect = state::updateBrightness,
                backgroundColor = MiuixTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }
        item(key = "color") { ColorCard(state) }
        item(key = "style") { StyleCard(state) }
        item(key = "bars") {
            BarsCard(
                state = state,
                bottomBarStyle = bottomBarStyle,
                bottomBarBlur = bottomBarBlur,
                showFunctionCount = showFunctionCount,
                onBottomBarStyleChange = onBottomBarStyleChange,
                onBottomBarBlurChange = onBottomBarBlurChange,
                onShowFunctionCountChange = onShowFunctionCountChange,
            )
        }
        item(key = "interaction") { InteractionCard(state) }
    }
}

@Composable
private fun ColorCard(state: ThemeState) {
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        SwitchPreference(
            checked = state.monet,
            onCheckedChange = state::updateMonet,
            title = stringResource(R.string.theme_monet_title),
            summary = stringResource(R.string.theme_monet_summary),
        )
        AnimatedPreferenceVisibility(visible = state.monet) {
            val values = listOf(0) + keyColorOptions.map { it.argb }
            val labels = listOf(stringResource(R.string.theme_key_color_default)) +
                keyColorOptions.map { stringResource(it.labelRes) }
            AppDropdownPreference(
                items = labels,
                selectedIndex = values.indexOf(state.keyColor).coerceAtLeast(0),
                title = stringResource(R.string.theme_key_color_title),
                summary = if (state.keyColor == 0) stringResource(R.string.theme_key_color_summary) else null,
                startAction = { KeyColorSwatch(state.keyColor) },
                onSelectedIndexChange = { index -> state.updateKeyColor(values[index]) },
            )
            // 和 KernelSU 一样：跟随壁纸取色时风格和标准由系统决定，只有指定了强调色才给这两项。
            AnimatedPreferenceVisibility(visible = state.keyColor != 0) {
                val styles = ThemePaletteStyle.entries
                AppDropdownPreference(
                    items = styles.map { stringResource(it.labelRes) },
                    selectedIndex = styles.indexOf(state.paletteStyle).coerceAtLeast(0),
                    title = stringResource(R.string.theme_palette_style_title),
                    summary = if (state.paletteStyle == ThemePaletteStyle.Cmf) {
                        stringResource(R.string.theme_palette_cmf_summary)
                    } else null,
                    onSelectedIndexChange = { index -> state.updatePaletteStyle(styles[index]) },
                )
                AnimatedPreferenceVisibility(visible = state.paletteStyle != ThemePaletteStyle.Cmf) {
                    val specs = ThemeColorSpec.entries
                    val downgraded = state.colorSpec == ThemeColorSpec.Spec2025 &&
                        !state.paletteStyle.supportsSpec2025
                    AppDropdownPreference(
                        items = specs.map {
                            stringResource(
                                if (it == ThemeColorSpec.Spec2025) R.string.theme_color_spec_2025
                                else R.string.theme_color_spec_2021
                            )
                        },
                        selectedIndex = specs.indexOf(state.colorSpec).coerceAtLeast(0),
                        title = stringResource(R.string.theme_color_spec_title),
                        summary = if (downgraded) stringResource(R.string.theme_color_spec_downgraded) else null,
                        onSelectedIndexChange = { index -> state.updateColorSpec(specs[index]) },
                    )
                }
            }
        }
    }
}

/** 强调色下拉项左侧的色块：未指定时画当前主色并加一圈描边，表示「跟随壁纸」。 */
@Composable
private fun KeyColorSwatch(keyColor: Int) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .padding(end = 12.dp)
            .size(26.dp)
            .clip(CircleShape)
            .background(if (keyColor == 0) scheme.primary else Color(keyColor))
            .then(
                if (keyColor == 0) Modifier.border(2.dp, scheme.surfaceContainer, CircleShape) else Modifier
            )
    )
}

@Composable
private fun StyleCard(state: ThemeState) {
    // 风格卡紧跟在颜色卡下面，不再加小标题（标题和条目名都叫「界面风格」会重复）。
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        AppDropdownPreference(
            items = listOf(
                stringResource(R.string.config_ui_style_default),
                stringResource(R.string.config_ui_style_mochi),
            ),
            selectedIndex = UiStyle.entries.indexOf(state.uiStyle),
            title = stringResource(R.string.config_ui_style_title),
            summary = stringResource(
                when (state.uiStyle) {
                    UiStyle.DEFAULT -> R.string.config_ui_style_default_summary
                    UiStyle.MOCHI -> R.string.config_ui_style_mochi_summary
                }
            ),
            onSelectedIndexChange = { index -> UiStyle.entries.getOrNull(index)?.let(state::updateUiStyle) },
        )
    }
}

@Composable
private fun BarsCard(
    state: ThemeState,
    bottomBarStyle: BottomBarStyle,
    bottomBarBlur: Boolean,
    showFunctionCount: Boolean,
    onBottomBarStyleChange: (BottomBarStyle) -> Unit,
    onBottomBarBlurChange: (Boolean) -> Unit,
    onShowFunctionCountChange: (Boolean) -> Unit,
) {
    SmallTitle(text = stringResource(R.string.theme_bars_title), insideMargin = PaddingValues(16.dp, 8.dp))
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        SwitchPreference(
            checked = state.topBarBlur,
            onCheckedChange = state::updateTopBarBlur,
            title = stringResource(R.string.theme_top_bar_blur_title),
            summary = stringResource(R.string.theme_top_bar_blur_summary),
        )
        AppDropdownPreference(
            items = listOf(
                stringResource(R.string.config_bottom_bar_style_standard),
                stringResource(R.string.config_bottom_bar_style_floating),
                stringResource(R.string.config_bottom_bar_style_liquid),
                stringResource(R.string.config_bottom_bar_style_glass),
            ),
            selectedIndex = BottomBarStyle.entries.indexOf(bottomBarStyle),
            title = stringResource(R.string.config_bottom_bar_style_title),
            onSelectedIndexChange = { index ->
                BottomBarStyle.entries.getOrNull(index)?.let(onBottomBarStyleChange)
            },
        )
        SwitchPreference(
            checked = bottomBarBlur,
            onCheckedChange = onBottomBarBlurChange,
            title = stringResource(R.string.config_bottom_bar_blur_title),
            // 液态玻璃底栏里这个开关管的是玻璃折射本身，关掉就是纯色底，摘要要说清楚。
            summary = stringResource(
                if (bottomBarStyle == BottomBarStyle.LIQUID) R.string.config_bottom_bar_blur_summary_glass
                else R.string.config_bottom_bar_blur_summary
            ),
        )
        SwitchPreference(
            checked = showFunctionCount,
            onCheckedChange = onShowFunctionCountChange,
            title = stringResource(R.string.config_show_function_count_title),
            summary = stringResource(R.string.config_show_function_count_summary),
        )
    }
}

@Composable
private fun InteractionCard(state: ThemeState) {
    SmallTitle(text = stringResource(R.string.theme_interaction_title), insideMargin = PaddingValues(16.dp, 8.dp))
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        SwitchPreference(
            checked = state.swipeBack,
            onCheckedChange = state::updateSwipeBack,
            title = stringResource(R.string.theme_swipe_back_title),
            summary = stringResource(R.string.theme_swipe_back_summary),
        )
        // 拖动时只改显示的百分比，松手才写入：缩放会让整页重新排版，边拖边生效滑块自己也会跟着跳。
        var sliderValue by remember(state.pageScale) { mutableFloatStateOf(state.pageScale) }
        BasicComponent(
            title = stringResource(R.string.theme_page_scale_title),
            summary = stringResource(R.string.theme_page_scale_summary),
            endActions = {
                Text(
                    text = "${(sliderValue * 100).roundToInt()}%",
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            },
            bottomAction = {
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { state.updatePageScale(sliderValue) },
                    valueRange = ThemePrefs.PAGE_SCALE_MIN..ThemePrefs.PAGE_SCALE_MAX,
                    showKeyPoints = true,
                    keyPoints = listOf(0.8f, 0.9f, 1f, 1.1f),
                    magnetThreshold = 0.01f,
                    hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                )
            },
        )
    }
}
