package hook.HyperBackscreen.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 外层 controller 只用来下发配色模式，它算出的色板会被内层覆盖；固定种子色让它只在首次组合时算一次。 */
private val ModeOnlySeed = Color(0xFF3482FF)

/**
 * 模块的 miuix 主题：色板用 [colors]（已叠好 UiStyle），同时让 `MiuixTheme.isDynamicColor` 跟随 Monet 开关。
 *
 * `MiuixTheme(colors = …)` 不提供配色模式，`isDynamicColor` 恒为 false：Glass 底栏的选中色和指示器、
 * Switch 的滑块色在 Monet 下都不跟主色。配色模式的 CompositionLocal 是库内部的，只能经
 * `MiuixTheme(controller = …)` 设置，所以外层套一个只提供模式的 controller，内层再用 [colors] 覆盖色板。
 * 外层 controller 会改写 LocalContentColor，内层补回与 [colors] 一致的值（和 controller 重载的行为相同）。
 *
 * 两种模式都始终套这两层，切换 Monet 时组合树结构不变，页面状态不会丢。
 */
@Composable
internal fun AppMiuixTheme(
    colors: Colors,
    dynamicColor: Boolean,
    isDark: Boolean,
    content: @Composable () -> Unit,
) {
    val controller = remember(dynamicColor, isDark) {
        ThemeController(
            colorSchemeMode = when {
                dynamicColor && isDark -> ColorSchemeMode.MonetDark
                dynamicColor -> ColorSchemeMode.MonetLight
                isDark -> ColorSchemeMode.Dark
                else -> ColorSchemeMode.Light
            },
            keyColor = ModeOnlySeed,
            isDark = isDark,
        )
    }
    MiuixTheme(controller = controller) {
        MiuixTheme(colors = colors) {
            CompositionLocalProvider(
                LocalContentColor provides MiuixTheme.colorScheme.onBackground,
                content = content,
            )
        }
    }
}
