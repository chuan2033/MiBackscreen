package hook.HyperBackscreen.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.max

/**
 * 界面风格。只改「长相」，不改布局和功能：
 * - [DEFAULT]：miuix 原样；
 * - [MOCHI]：麻薯感。奶油纯色底、珊瑚主色、更大的连续曲率圆角、暖色柔和投影，按下卡片会软软地回弹。
 *
 * 页面背景和 miuix 一样是纯色（取色板的 surface），不画额外的光晕。
 * 颜色统一在 [styledColorScheme] 里改 miuix 色板，所有控件自动跟随；Monet 下保留动态取色的主色，
 * 只换形状和动效。
 */
enum class UiStyle(val index: Int) {
    DEFAULT(0),
    // 1 曾是 OS4 风格（已移除）；存的 1 读回来按 DEFAULT 处理，Mochi 保持 2 不变。
    MOCHI(2);

    /** 列表卡片 / 状态卡的圆角。 */
    val cardCorner: Dp
        get() = when (this) {
            DEFAULT -> HomeUiTokens.CornerCard
            MOCHI -> 26.dp
        }

    companion object {
        fun fromIndex(i: Int): UiStyle = entries.firstOrNull { it.index == i } ?: DEFAULT
    }
}

internal val LocalUiStyle = staticCompositionLocalOf { UiStyle.DEFAULT }

internal val currentUiStyle: UiStyle
    @Composable
    @ReadOnlyComposable
    get() = LocalUiStyle.current

/** 在 miuix 色板上叠加风格。[dynamic] 为 true（Monet）时不动主色系，只调底色。 */
internal fun styledColorScheme(base: Colors, style: UiStyle, dark: Boolean, dynamic: Boolean): Colors = when (style) {
    UiStyle.DEFAULT -> base
    UiStyle.MOCHI -> when {
        dynamic && dark -> base.copy(surface = Color(0xFF120F0D))
        dynamic -> base.copy(surface = lerpWarm(base.surface))
        dark -> base.copy(
            primary = MochiCoralDark,
            primaryVariant = Color(0xFFE08872),
            onPrimaryVariant = Color(0xFF5A3A33),
            disabledPrimary = Color(0xFF5A3A33),
            disabledPrimaryButton = Color(0xFF5A3A33),
            disabledPrimarySlider = Color(0xFF6E4A41),
            primaryContainer = Color(0xFFD9806C),
            tertiaryContainer = Color(0xFF4A3029),
            onTertiaryContainer = MochiCoralDark,
            surface = Color(0xFF15110F),
            background = Color(0xFF2A221E),
            surfaceVariant = Color(0xFF2A221E),
            surfaceContainer = Color(0xFF2F2622),
            surfaceContainerHigh = Color(0xFF382D28),
            surfaceContainerHighest = Color(0xFF382D28),
            sliderKeyPointForeground = Color(0xFFFFA897),
        )
        else -> base.copy(
            primary = MochiCoral,
            primaryVariant = MochiCoral,
            onPrimaryVariant = Color(0xFFFFD3C8),
            disabledPrimary = Color(0xFFF9CFC5),
            disabledOnPrimary = Color(0xFFFFF6F3),
            disabledPrimaryButton = Color(0xFFF9CFC5),
            disabledPrimarySlider = Color(0xFFF5C3B7),
            primaryContainer = Color(0xFFF7A28E),
            tertiaryContainer = Color(0xFFFFEEE8),
            tertiaryContainerVariant = Color(0xFFFFEEE8),
            onTertiaryContainer = MochiCoral,
            // 分组小标题用的颜色；原来的 B0968A 在奶油底上对比度只有 2.5，看不清。
            onBackgroundVariant = Color(0xFF94786B),
            surface = Color(0xFFFBF4EC),
            // 弹窗 / 底部面板底色（miuix 取 background），和卡片一致的奶油白，不再是纯白。
            background = Color(0xFFFFFDFA),
            // 卡片用纯白，和奶油底拉开层次（FFFDFA 和底色几乎分不出来，卡片边界只靠阴影）。
            surfaceContainer = Color(0xFFFFFFFF),
            surfaceContainerHigh = Color(0xFFF3EAE1),
            surfaceContainerHighest = Color(0xFFF3EAE1),
            secondary = Color(0xFFEFE6DD),
            secondaryVariant = Color(0xFFF5EDE5),
            secondaryContainer = Color(0xFFF4ECE4),
            secondaryContainerVariant = Color(0xFFF4ECE4),
            outline = Color(0xFFE8DDD2),
            dividerLine = Color(0xFFEDE3D9),
            sliderKeyPointForeground = Color(0xFFFFA48F),
        )
    }
}

private val MochiCoral = Color(0xFFF2856D)
private val MochiCoralDark = Color(0xFFF29A86)

/** Monet 浅色下给底色加一点奶油暖调。 */
private fun lerpWarm(color: Color): Color = androidx.compose.ui.graphics.lerp(color, Color(0xFFFBF1E6), 0.45f)

/**
 * 卡片外观装饰，挂在 miuix Card 的 modifier 上：
 * - MOCHI：暖色柔和投影 + 按压回弹。
 */
@Composable
internal fun Modifier.uiStyleCard(cornerRadius: Dp): Modifier {
    val style = LocalUiStyle.current
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    return when (style) {
        UiStyle.DEFAULT -> this
        UiStyle.MOCHI -> {
            val squircle = isSquircleEnabled()
            val shape = remember(cornerRadius, squircle) { SquircleShape(cornerRadius, squircle) }
            this
                .pressSquish()
                // 用 dropShadow 而不是 elevation 阴影：形状和卡片的连续曲率圆角一致，
                // 不受光源位置影响（屏幕上下方的卡片阴影一样），也不会叠成一整条灰带。
                .dropShadow(
                    shape = shape,
                    shadow = Shadow(
                        radius = if (dark) 10.dp else 14.dp,
                        color = if (dark) Color.Black else MochiShadow,
                        offset = DpOffset(0.dp, if (dark) 3.dp else 4.dp),
                        alpha = if (dark) 0.32f else 0.16f,
                    ),
                )
        }
    }
}

private val MochiShadow = Color(0xFFB8735C)

/** 与 miuix Card 的 squircleSurface 同形的轮廓，给阴影用。 */
internal class SquircleShape(private val cornerRadius: Dp, private val squircle: Boolean = true) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(
            Path().apply {
                addSquircleRect(
                    width = size.width,
                    height = size.height,
                    cornerRadius = with(density) { cornerRadius.toPx() },
                    squircleEnabled = squircle,
                )
            }
        )
}

/**
 * 按下时整张卡片微微缩小、松手带过冲弹回。只观察触摸、不消费事件，卡片里的开关和行照常响应；
 * 手指一拖动（列表滚动）就松开，不会卡在按下状态。
 */
@Composable
private fun Modifier.pressSquish(): Modifier {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    return this
        .pointerInput(Unit) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // 让边缘大约内缩 5dp：大卡片缩得少、小卡片缩得多，看起来手感一致。
                val target = 1f - (10.dp.toPx() / max(size.height, size.width).coerceAtLeast(1)).coerceIn(0.012f, 0.04f)
                var pressed = false
                val pressJob: Job = scope.launch {
                    delay(SquishPressDelayMillis)
                    pressed = true
                    scale.animateTo(target, spring(dampingRatio = 0.7f, stiffness = 900f))
                }
                var dragged = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if ((change.position - down.position).getDistance() > slop) {
                        dragged = true
                        break
                    }
                }
                pressJob.cancel()
                scope.launch {
                    if (!pressed && !dragged) {
                        // 轻点：来不及按下就松手了，补一个短促的按下再弹回。
                        scale.animateTo(target, spring(dampingRatio = 1f, stiffness = 2000f))
                    }
                    scale.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = 380f))
                }
            }
        }
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
}

private const val SquishPressDelayMillis = 60L
