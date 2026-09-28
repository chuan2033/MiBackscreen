package hook.HyperBackscreen.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Only the background fades; icon opacity and hit target stay constant. */
@Composable
internal fun ScrollGlassIconButton(
    image: ImageVector,
    description: String,
    backdrop: LayerBackdrop,
    covered: Boolean,
    onClick: () -> Unit
) {
    val opacity by animateFloatAsState(if (covered) 1f else 0f, tween(180), label = "actionGlass")
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val tint = if (dark) MiuixTheme.colorScheme.surfaceContainer else Color(0xFFD8DBDF)
    val rim = Brush.linearGradient(listOf(
        Color.White.copy(alpha = if (dark) 0.45f else 0.9f),
        Color.White.copy(alpha = if (dark) 0.12f else 0.3f),
        Color.White.copy(alpha = if (dark) 0.28f else 0.65f),
    ))
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        if (opacity > 0f) {
            Box(
                Modifier.matchParentSize()
                    .alpha(opacity)
                    .shadow(3.dp, CircleShape, clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.12f),
                        spotColor = Color.Black.copy(alpha = 0.12f))
                    .textureBlur(
                        backdrop = backdrop,
                        shape = CircleShape,
                        blurRadius = 20f,
                        colors = BlurDefaults.blurColors(
                            blendColors = listOf(BlendColorEntry(tint.copy(alpha = 0.6f)))
                        )
                    )
                    .background(Brush.verticalGradient(listOf(
                        Color.White.copy(alpha = if (dark) 0.12f else 0.28f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.04f),
                    )), CircleShape)
                    .border(1.dp, rim, CircleShape)
            )
        }
        IconButton(onClick = onClick, modifier = Modifier.matchParentSize(),
            backgroundColor = Color.Transparent) {
            Icon(imageVector = image, contentDescription = description,
                tint = MiuixTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        }
    }
}
