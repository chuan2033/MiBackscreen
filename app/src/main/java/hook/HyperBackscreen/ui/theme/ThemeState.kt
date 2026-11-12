package hook.HyperBackscreen.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.annotation.StringRes
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.util.ThemeMode
import hook.HyperBackscreen.ui.util.ThemePrefs
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * 主题系统，移植自 KernelSU 管理器（Miuix 界面）的「主题」页，按本模块的情况做了取舍：
 *
 * - 颜色：明暗三段（跟随系统 / 浅色 / 深色）+ Monet 开关；开 Monet 后可指定强调色（种子色），
 *   指定了强调色还能选色彩风格（TonalSpot、Vibrant…）和色彩标准（Material 2021 / 2025）。
 *   取色交给 miuix 的 [ThemeController]，和 KernelSU 走的是同一条路径。
 * - 界面：顶栏模糊、页面缩放、全屏横滑返回。悬浮底栏 / 液态玻璃 / 底栏模糊 / 功能数量角标沿用模块原有的开关。
 * - 没有移植的：KernelSU 的「预测性返回」需要隐藏 API 豁免库，「分页手势模式」和本模块固定的跨轴分页手势冲突，
 *   「模块描述行数」是 KernelSU 自己的页面；AMOLED 纯黑在 Miuix 界面下 KernelSU 也没有提供。
 *
 * 所有字段都是快照状态：改动立即生效并写进 app_ui_prefs。
 */
@Stable
internal class ThemeState(context: Context) {
    private val appContext = context.applicationContext

    var themeMode by mutableStateOf(ThemePrefs.getThemeMode(appContext))
        private set
    /** Monet 种子色（ARGB），0 = 跟随系统壁纸。只在 Monet 开着时生效。 */
    var keyColor by mutableIntStateOf(ThemePrefs.getKeyColor(appContext))
        private set
    var paletteStyle by mutableStateOf(ThemePrefs.getPaletteStyle(appContext))
        private set
    var colorSpec by mutableStateOf(ThemePrefs.getColorSpec(appContext))
        private set
    var pageScale by mutableFloatStateOf(ThemePrefs.getPageScale(appContext))
        private set
    var topBarBlur by mutableStateOf(ThemePrefs.getTopBarBlur(appContext))
        private set
    var swipeBack by mutableStateOf(ThemePrefs.getSwipeBack(appContext))
        private set

    val monet: Boolean get() = themeMode.usesDynamicColors

    fun updateThemeMode(mode: ThemeMode) {
        ThemePrefs.setThemeMode(appContext, mode)
        themeMode = mode
    }

    fun updateBrightness(index: Int) = updateThemeMode(themeMode.withBrightness(index))

    fun updateMonet(enabled: Boolean) = updateThemeMode(themeMode.withMonet(enabled))

    fun updateKeyColor(color: Int) {
        ThemePrefs.setKeyColor(appContext, color)
        keyColor = color
    }

    fun updatePaletteStyle(style: ThemePaletteStyle) {
        ThemePrefs.setPaletteStyle(appContext, style)
        paletteStyle = style
    }

    fun updateColorSpec(spec: ThemeColorSpec) {
        ThemePrefs.setColorSpec(appContext, spec)
        colorSpec = spec
    }

    fun updatePageScale(scale: Float) {
        ThemePrefs.setPageScale(appContext, scale)
        pageScale = scale.coerceIn(ThemePrefs.PAGE_SCALE_MIN, ThemePrefs.PAGE_SCALE_MAX)
    }

    fun updateTopBarBlur(enabled: Boolean) {
        ThemePrefs.setTopBarBlur(appContext, enabled)
        topBarBlur = enabled
    }

    fun updateSwipeBack(enabled: Boolean) {
        ThemePrefs.setSwipeBack(appContext, enabled)
        swipeBack = enabled
    }

    /**
     * 按当前设置算出 miuix 色板。
     * 非 Monet 时就是 miuix 默认的浅色 / 深色；Monet 且没指定强调色时取系统壁纸色；
     * 指定了强调色就按色彩风格和色彩标准从种子色生成。
     */
    @Composable
    fun baseColors(isDark: Boolean): Colors {
        val mode = themeMode
        val seed = keyColor.takeIf { mode.usesDynamicColors && it != 0 }?.let(::Color)
        val style = paletteStyle
        val spec = colorSpec
        val controller = remember(mode, seed, style, spec, isDark) {
            ThemeController(
                colorSchemeMode = when (mode) {
                    ThemeMode.SYSTEM -> ColorSchemeMode.System
                    ThemeMode.LIGHT -> ColorSchemeMode.Light
                    ThemeMode.DARK -> ColorSchemeMode.Dark
                    ThemeMode.MONET_SYSTEM -> ColorSchemeMode.MonetSystem
                    ThemeMode.MONET_LIGHT -> ColorSchemeMode.MonetLight
                    ThemeMode.MONET_DARK -> ColorSchemeMode.MonetDark
                },
                keyColor = seed,
                colorSpec = spec,
                paletteStyle = style,
                isDark = isDark,
            )
        }
        return controller.currentColors()
    }
}

internal val LocalThemeState = staticCompositionLocalOf<ThemeState?> { null }

/** 顶栏是否用毛玻璃；没有 [ThemeState]（预览等场景）时按默认开启。 */
internal val currentTopBarBlur: Boolean
    @Composable
    get() = LocalThemeState.current?.topBarBlur ?: true

/** 强调色选项：与 KernelSU 的 keyColorOptions 相同的 15 种颜色。 */
internal class KeyColorOption(val argb: Int, @StringRes val labelRes: Int)

internal val keyColorOptions: List<KeyColorOption> = listOf(
    KeyColorOption(Color(0xFFF44336).toArgb(), R.string.theme_color_red),
    KeyColorOption(Color(0xFFE91E63).toArgb(), R.string.theme_color_pink),
    KeyColorOption(Color(0xFF9C27B0).toArgb(), R.string.theme_color_purple),
    KeyColorOption(Color(0xFF673AB7).toArgb(), R.string.theme_color_deep_purple),
    KeyColorOption(Color(0xFF3F51B5).toArgb(), R.string.theme_color_indigo),
    KeyColorOption(Color(0xFF2196F3).toArgb(), R.string.theme_color_blue),
    KeyColorOption(Color(0xFF00BCD4).toArgb(), R.string.theme_color_cyan),
    KeyColorOption(Color(0xFF009688).toArgb(), R.string.theme_color_teal),
    KeyColorOption(Color(0xFF4FAF50).toArgb(), R.string.theme_color_green),
    KeyColorOption(Color(0xFFFFEB3B).toArgb(), R.string.theme_color_yellow),
    KeyColorOption(Color(0xFFFFC107).toArgb(), R.string.theme_color_amber),
    KeyColorOption(Color(0xFFFF9800).toArgb(), R.string.theme_color_orange),
    KeyColorOption(Color(0xFF795548).toArgb(), R.string.theme_color_brown),
    KeyColorOption(Color(0xFF607D8F).toArgb(), R.string.theme_color_blue_grey),
    KeyColorOption(Color(0xFFFF9CA8).toArgb(), R.string.theme_color_sakura),
)

/** 只有这四种色彩风格支持 Material 2025 标准，其余风格选 2025 时 miuix 会退回 2021。 */
internal val ThemePaletteStyle.supportsSpec2025: Boolean
    get() = this == ThemePaletteStyle.TonalSpot ||
        this == ThemePaletteStyle.Neutral ||
        this == ThemePaletteStyle.Vibrant ||
        this == ThemePaletteStyle.Expressive

@get:StringRes
internal val ThemePaletteStyle.labelRes: Int
    get() = when (this) {
        ThemePaletteStyle.TonalSpot -> R.string.theme_palette_tonal_spot
        ThemePaletteStyle.Neutral -> R.string.theme_palette_neutral
        ThemePaletteStyle.Vibrant -> R.string.theme_palette_vibrant
        ThemePaletteStyle.Expressive -> R.string.theme_palette_expressive
        ThemePaletteStyle.Rainbow -> R.string.theme_palette_rainbow
        ThemePaletteStyle.FruitSalad -> R.string.theme_palette_fruit_salad
        ThemePaletteStyle.Monochrome -> R.string.theme_palette_monochrome
        ThemePaletteStyle.Fidelity -> R.string.theme_palette_fidelity
        ThemePaletteStyle.Content -> R.string.theme_palette_content
        ThemePaletteStyle.Cmf -> R.string.theme_palette_cmf
    }
