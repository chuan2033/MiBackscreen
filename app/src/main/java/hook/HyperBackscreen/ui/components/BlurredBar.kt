package hook.HyperBackscreen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import hook.HyperBackscreen.ui.theme.currentTopBarBlur
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶栏的渐进式毛玻璃容器。必须挂在 layerBackdrop 内容子树之外，
 * 否则 backdrop 采样成环会导致 native crash。
 * 使用官方完整渐进管线；按用户指定的 HyperMusicCover 参数常驻显示，不随滚动淡入。
 */
@Composable
internal fun BlurredBar(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(MiuixTheme.colorScheme.surface.copy(0.3f)))
    )
    Box(modifier = modifier) {
        // 主题页可以关掉顶栏模糊（KernelSU 的「模糊」开关）：关掉后顶栏是和页面同色的纯色底。
        if (currentTopBarBlur) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        blurRadius = 15f,
                        gradient = ProgressiveBlur.Top.copy(curve = 10f),
                        colors = colors,
                    )
            )
        } else {
            Box(modifier = Modifier.matchParentSize().background(MiuixTheme.colorScheme.surface))
        }
        content()
    }
}
