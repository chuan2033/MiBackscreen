package hook.HyperBackscreen.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶栏的毛玻璃容器。必须挂在 layerBackdrop 内容子树之外，
 * 否则 textureBlur 与 backdrop 采样成环会导致 native crash。
 */
@Composable
internal fun BlurredBar(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.textureBlur(
            backdrop = backdrop,
            shape = RectangleShape,
            blurRadius = 25f,
            colors = BlurDefaults.blurColors(
                blendColors = listOf(
                    BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.8f))
                )
            )
        )
    ) {
        content()
    }
}
