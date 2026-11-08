package hook.HyperBackscreen.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.ui.liquid.lens
import hook.HyperBackscreen.ui.liquid.vibrancy
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.utils.getRoundedCorner
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 模块统一的弹窗：用和 miuix [WindowDialog] 同一套布局与动画的 [OverlayDialog]
 * （压暗、从底部滑入、预测性返回都和原生一样），只把不透明底换成液态玻璃。
 *
 * OverlayDialog 画在根 Scaffold 的覆盖层里，和页面在同一个窗口，才能采样整个界面做模糊；
 * 滑入动画只有平移，采样位置跟得上。没有采样源（[currentGlassBackdrop] 为空）时退回原生弹窗。
 */
@Composable
internal fun AppDialog(
    show: Boolean,
    title: String? = null,
    summary: String? = null,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val backdrop = currentGlassBackdrop
    val glass = backdrop != null && remember { isRuntimeShaderSupported() }
    if (!glass || backdrop == null) {
        WindowDialog(
            show = show,
            title = title,
            summary = summary,
            onDismissRequest = onDismissRequest,
            onDismissFinished = onDismissFinished,
            content = content,
        )
        return
    }
    // 圆角按原生的算法：贴底弹窗与屏幕圆角同心（屏幕圆角减外边距），夹在 32..48dp。
    // 这里显式算出来传给弹窗，玻璃形状才能用同一个半径。
    val screenCorner = getRoundedCorner()
    val corner = (screenCorner - DialogDefaults.outsideMargin.width).coerceIn(32.dp, 48.dp)
    OverlayDialog(
        show = show,
        modifier = Modifier.glassDialogSurface(backdrop, corner),
        title = title,
        summary = summary,
        backgroundColor = Color.Transparent,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        largeScreen = false,
        cornerRadius = corner,
        content = content,
    )
}

@Composable
private fun Modifier.glassDialogSurface(backdrop: LayerBackdrop, corner: Dp): Modifier {
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val surface = MiuixTheme.colorScheme.background
    // 弹窗里文字多，底色比下拉菜单再实一点。
    val tint = if (dark) surface.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.74f)
    // 必须是 CornerBasedShape：透镜折射和高光描边按圆角半径算。
    val shape = remember(corner) { RoundedCornerShape(corner) }
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(16.dp.toPx(), 16.dp.toPx())
            lens(refractionHeight = 18.dp.toPx(), refractionAmount = 24.dp.toPx())
        },
        highlight = { Highlight.Default.copy(alpha = if (dark) 0.45f else 0.85f) },
        onDrawSurface = { drawRect(tint) },
    )
}
