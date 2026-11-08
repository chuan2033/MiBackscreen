package hook.HyperBackscreen.ui.util

import android.content.Context
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

enum class ThemeMode(val index: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2),
    MONET_SYSTEM(3),
    MONET_LIGHT(4),
    MONET_DARK(5);

    val usesDynamicColors: Boolean
        get() = when (this) {
            MONET_SYSTEM,
            MONET_LIGHT,
            MONET_DARK -> true
            SYSTEM,
            LIGHT,
            DARK -> false
        }

    /** 主题页顶部的「跟随系统 / 浅色 / 深色」三段：Monet 与否不影响这里的位置。 */
    val brightnessIndex: Int
        get() = index % 3

    /** 保留明暗选择，只切换 Monet 开关（对应 KernelSU 的 toMonetMode / toNonMonetMode）。 */
    fun withMonet(enabled: Boolean): ThemeMode = fromIndex(brightnessIndex + if (enabled) 3 else 0)

    /** 保留 Monet 开关，只切换明暗。 */
    fun withBrightness(brightnessIndex: Int): ThemeMode =
        fromIndex(brightnessIndex.coerceIn(0, 2) + if (usesDynamicColors) 3 else 0)

    /** 将用户选择解析为最终是否使用深色主题。 */
    fun resolve(systemDark: Boolean): Boolean = when (this) {
        SYSTEM,
        MONET_SYSTEM -> systemDark
        LIGHT,
        MONET_LIGHT -> false
        DARK,
        MONET_DARK -> true
    }

    companion object {
        fun fromIndex(i: Int): ThemeMode = entries.firstOrNull { it.index == i } ?: SYSTEM
    }
}

object ThemePrefs {
    private const val PREF_NAME = "app_ui_prefs"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_UI_STYLE = "ui_style"
    private const val KEY_KEY_COLOR = "key_color"
    private const val KEY_PALETTE_STYLE = "palette_style"
    private const val KEY_COLOR_SPEC = "color_spec"
    private const val KEY_PAGE_SCALE = "page_scale"
    private const val KEY_TOP_BAR_BLUR = "top_bar_blur"
    private const val KEY_SWIPE_BACK = "swipe_back"

    /** 页面缩放的范围与默认值，和 KernelSU 一致（80% ~ 110%）。 */
    const val PAGE_SCALE_MIN = 0.8f
    const val PAGE_SCALE_MAX = 1.1f
    const val PAGE_SCALE_DEFAULT = 1f

    private fun prefs(context: Context) = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getThemeMode(context: Context): ThemeMode =
        ThemeMode.fromIndex(prefs(context).getInt(KEY_THEME_MODE, ThemeMode.SYSTEM.index))

    fun setThemeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putInt(KEY_THEME_MODE, mode.index).apply()
    }

    fun getUiStyle(context: Context): hook.HyperBackscreen.ui.theme.UiStyle =
        hook.HyperBackscreen.ui.theme.UiStyle.fromIndex(prefs(context).getInt(KEY_UI_STYLE, 0))

    fun setUiStyle(context: Context, style: hook.HyperBackscreen.ui.theme.UiStyle) {
        prefs(context).edit().putInt(KEY_UI_STYLE, style.index).apply()
    }

    /** Monet 种子色（ARGB）；0 表示不指定，跟随系统壁纸取色。 */
    fun getKeyColor(context: Context): Int = prefs(context).getInt(KEY_KEY_COLOR, 0)

    fun setKeyColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_KEY_COLOR, color).apply()
    }

    fun getPaletteStyle(context: Context): ThemePaletteStyle {
        val name = prefs(context).getString(KEY_PALETTE_STYLE, null)
        return ThemePaletteStyle.entries.firstOrNull { it.name == name } ?: ThemePaletteStyle.TonalSpot
    }

    fun setPaletteStyle(context: Context, style: ThemePaletteStyle) {
        prefs(context).edit().putString(KEY_PALETTE_STYLE, style.name).apply()
    }

    fun getColorSpec(context: Context): ThemeColorSpec {
        val name = prefs(context).getString(KEY_COLOR_SPEC, null)
        return ThemeColorSpec.entries.firstOrNull { it.name == name } ?: ThemeColorSpec.Spec2025
    }

    fun setColorSpec(context: Context, spec: ThemeColorSpec) {
        prefs(context).edit().putString(KEY_COLOR_SPEC, spec.name).apply()
    }

    fun getPageScale(context: Context): Float =
        prefs(context).getFloat(KEY_PAGE_SCALE, PAGE_SCALE_DEFAULT).coerceIn(PAGE_SCALE_MIN, PAGE_SCALE_MAX)

    fun setPageScale(context: Context, scale: Float) {
        prefs(context).edit().putFloat(KEY_PAGE_SCALE, scale.coerceIn(PAGE_SCALE_MIN, PAGE_SCALE_MAX)).apply()
    }

    /** 顶栏毛玻璃；关掉后顶栏是纯色底。 */
    fun getTopBarBlur(context: Context): Boolean = prefs(context).getBoolean(KEY_TOP_BAR_BLUR, true)

    fun setTopBarBlur(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TOP_BAR_BLUR, enabled).apply()
    }

    /** 二级页在页面任意位置横滑返回（不只是系统的边缘返回手势）。 */
    fun getSwipeBack(context: Context): Boolean = prefs(context).getBoolean(KEY_SWIPE_BACK, false)

    fun setSwipeBack(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SWIPE_BACK, enabled).apply()
    }
}
