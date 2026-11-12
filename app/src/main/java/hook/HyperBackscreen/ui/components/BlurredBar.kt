package hook.HyperBackscreen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import hook.HyperBackscreen.ui.theme.currentTopBarBlur
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 顶栏背景保持渐进式模糊，采样层位于页面内容中。 */
@Composable
internal fun BlurredBar(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(MiuixTheme.colorScheme.surface.copy(alpha = 0.3f)))
    )
    Box(modifier = modifier) {
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
            Box(Modifier.matchParentSize().background(MiuixTheme.colorScheme.surface))
        }
        content()
    }
}
