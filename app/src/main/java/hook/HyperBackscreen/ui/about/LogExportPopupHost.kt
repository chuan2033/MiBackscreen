package hook.HyperBackscreen.ui.about

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.RemovePlatformDialogDefaultEffects
import kotlin.math.roundToInt

/** Pop above the log entry, scaling symmetrically from the bottom center without translation. */
@Composable
internal fun LogExportPopupHost(
    show: Boolean,
    anchorBounds: IntRect,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = show && anchorBounds.width > 0 && anchorBounds.height > 0
    val dimAlpha by animateFloatAsState(if (visible.targetState) 1f else 0f, tween(180), label = "logDim")
    if (!visible.currentState && !visible.targetState && visible.isIdle) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        RemovePlatformDialogDefaultEffects()
        val insets = WindowInsets.safeDrawing
        var hostOrigin by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().onGloballyPositioned { hostOrigin = it.positionInWindow() }) {
            Box(Modifier.fillMaxSize()
                .graphicsLayer { alpha = dimAlpha }
                .background(MiuixTheme.colorScheme.windowDimming)
                .pointerInput(onDismiss) { detectTapGestures(onTap = { onDismiss() }) })
            Layout(
                modifier = Modifier.fillMaxSize().drawWithContent {
                    // Keep the popup above the log row throughout its scale animation.
                    clipRect(bottom = (anchorBounds.top - hostOrigin.y - 8.dp.toPx()).coerceIn(0f, size.height)) {
                        this@drawWithContent.drawContent()
                    }
                },
                content = {
                    AnimatedVisibility(
                        visibleState = visible,
                        enter = scaleIn(
                            animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f),
                            initialScale = 0.7f,
                            transformOrigin = TransformOrigin(0.5f, 1f),
                        ) + fadeIn(tween(160)),
                        exit = scaleOut(
                            animationSpec = tween(150),
                            targetScale = 0.9f,
                            transformOrigin = TransformOrigin(0.5f, 1f),
                        ) + fadeOut(tween(150)),
                    ) {
                        Box(Modifier.squircleClip(16.dp)
                            .background(MiuixTheme.colorScheme.surfaceContainer)
                            .pointerInput(Unit) { detectTapGestures(onTap = {}) }) {
                            content()
                        }
                    }
                },
            ) { measurables, constraints ->
                val safeLeft = insets.getLeft(this, layoutDirection)
                val safeRight = insets.getRight(this, layoutDirection)
                val safeTop = insets.getTop(this)
                val safeBottom = insets.getBottom(this)
                val gap = 8.dp.roundToPx()
                val availableWidth = (constraints.maxWidth - safeLeft - safeRight).coerceAtLeast(0)
                val cardWidth = anchorBounds.width.coerceIn(0, availableWidth)
                val availableHeight = (constraints.maxHeight - safeTop - safeBottom - gap * 2).coerceAtLeast(0)
                val card = measurables.single().measure(constraints.copy(
                    minWidth = cardWidth, maxWidth = cardWidth, minHeight = 0, maxHeight = availableHeight,
                ))
                val x = (anchorBounds.left - hostOrigin.x.roundToInt()).coerceIn(safeLeft,
                    (constraints.maxWidth - safeRight - card.width).coerceAtLeast(safeLeft))
                val minY = safeTop + gap
                val y = (anchorBounds.top - hostOrigin.y.roundToInt() - card.height - gap).coerceIn(minY,
                    (constraints.maxHeight - safeBottom - card.height - gap).coerceAtLeast(minY))
                layout(constraints.maxWidth, constraints.maxHeight) { card.place(x, y) }
            }
        }
    }
}
