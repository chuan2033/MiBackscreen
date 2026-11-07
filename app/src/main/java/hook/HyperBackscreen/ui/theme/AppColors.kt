package hook.HyperBackscreen.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * miuix 色板之外、项目自己要用的颜色，全部集中在这里。
 *
 * 每一项都按三种情况给值：浅色、深色、Monet（动态取色）。原则：
 * - 表达「状态」的颜色（激活绿卡）在 Monet 下改跟主题色，避免固定绿和主题色系打架；
 * - 表达「品牌」的颜色（微信绿、支付宝蓝）不随 Monet 变，
 *   否则用户认不出含义，只在深色模式下压暗一档；
 * - 中性色（骨架屏、代码底色）一律从 onSurface 派生，天然适配所有主题。
 *
 * 背屏注入面板运行在宿主进程、没有 Compose，它的颜色在 common/PanelPalette 里单独维护，
 * 数值与这里和 miuix 默认色板保持一致。
 */
@Immutable
internal data class AppColors(
    /** 主页「已激活」卡片底色。 */
    val statusActiveContainer: Color,
    /** 「已激活」卡片上的文字色。 */
    val onStatusActiveContainer: Color,
    /** 「已激活」对勾图形，以及作用域「已注入」圆点。 */
    val statusActiveGlyph: Color,
    /** 作用域圆点：已注入当前版本（蓝）。 */
    val scopeActive: Color,
    /** 作用域圆点：需要重启 / 加载失败（红）。 */
    val scopeNeedsRestart: Color,
    /** 骨架屏流光条的底色与高光。 */
    val shimmerBase: Color,
    val shimmerHighlight: Color,
    /** 更新说明里行内代码的底色。 */
    val codeBackground: Color,
    /** 捐赠卡渐变（上 / 下）。 */
    val weChatTop: Color,
    val weChatBottom: Color,
    val alipayTop: Color,
    val alipayBottom: Color,
)

private val ActiveContainerLight = Color(0xFFDFFAE4)
private val ActiveContainerDark = Color(0xFF1A3825)
private val ActiveGlyph = Color(0xFF36D167)
private val WeChatTop = Color(0xFF2E9E5B)
private val WeChatBottom = Color(0xFF7AC992)
private val AlipayTop = Color(0xFF2A62E0)
private val AlipayBottom = Color(0xFF63A0F8)

/** 深色模式下品牌渐变往黑色压的比例：整块卡片不至于在暗背景里发亮。 */
private const val DarkBrandDim = 0.28f

internal fun appColors(scheme: Colors, dynamic: Boolean): AppColors {
    val dark = scheme.surface.luminance() < 0.5f
    val brandDim = if (dark) DarkBrandDim else 0f
    return AppColors(
        statusActiveContainer = when {
            dynamic -> scheme.secondaryContainer
            dark -> ActiveContainerDark
            else -> ActiveContainerLight
        },
        onStatusActiveContainer = if (dynamic) scheme.onSecondaryContainer else scheme.onSurfaceContainer,
        statusActiveGlyph = if (dynamic) scheme.primary else ActiveGlyph,
        // 圆点的蓝 / 红直接取主题的 primary / error：默认色板下就是 miuix 蓝和红，
        // Monet 下蓝点跟随主题主色，红点仍是错误色。
        scopeActive = scheme.primary,
        scopeNeedsRestart = scheme.error,
        shimmerBase = scheme.onSurface.copy(alpha = 0.08f),
        shimmerHighlight = scheme.onSurface.copy(alpha = 0.03f),
        codeBackground = scheme.onSurface.copy(alpha = 0.10f),
        weChatTop = lerp(WeChatTop, Color.Black, brandDim),
        weChatBottom = lerp(WeChatBottom, Color.Black, brandDim),
        alipayTop = lerp(AlipayTop, Color.Black, brandDim),
        alipayBottom = lerp(AlipayBottom, Color.Black, brandDim),
    )
}

internal val LocalAppColors = staticCompositionLocalOf<AppColors?> { null }

/** 读取当前主题下的项目颜色；没有显式提供时按 miuix 当前色板现算（非 Monet）。 */
internal val currentAppColors: AppColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAppColors.current ?: appColors(MiuixTheme.colorScheme, dynamic = false)
