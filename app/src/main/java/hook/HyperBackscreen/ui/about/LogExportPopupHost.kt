package hook.HyperBackscreen.ui.about

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import hook.HyperBackscreen.ui.theme.HomeUiTokens
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.ui.components.currentGlassBackdrop
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils
import kotlin.math.roundToInt

/**
 * Pop above the log entry, scaling symmetrically from the bottom center without translation.
 *
 * 弹层挂在根 Scaffold 的弹窗宿主里（[MiuixPopupUtils.PopupLayout]），和玻璃弹窗、玻璃下拉同一个窗口：
 * 根 backdrop 采样的就是面板正下方的页面，坐标也同属主窗口。之前用平台 Dialog 是独立窗口，
 * 玻璃采样跨窗口，位置可能错开。遮罩、返回手势由宿主处理。
 */
@Composable
internal fun LogExportPopupHost(
    show: Boolean,
    anchorBounds: IntRect,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val wanted = show && anchorBounds.width > 0 && anchorBounds.height > 0
    val showState = remember { mutableStateOf(false) }
    val latestWanted by rememberUpdatedState(wanted)
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(wanted) { showState.value = wanted }
    // 宿主在返回手势时直接把 showState 置 false：同步回调用方。
    LaunchedEffect(showState) {
        var wasShown = false
        snapshotFlow { showState.value }.collect { shown ->
            if (shown) {
                wasShown = true
            } else {
                if (wasShown && latestWanted) latestOnDismiss()
                wasShown = false
            }
        }
    }
    MiuixPopupUtils.PopupLayout(
        visible = showState,
        enterTransition = fadeIn(tween(160)),
        exitTransition = fadeOut(tween(150)),
    ) {
        LogExportPopupLayer(
            visible = showState.value,
            anchorBounds = anchorBounds,
            onDismiss = onDismiss,
            content = content,
        )
    }
}

@Composable
private fun LogExportPopupLayer(
    visible: Boolean,
    anchorBounds: IntRect,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val cardVisible = remember { MutableTransitionState(false) }
    cardVisible.targetState = visible
    val insets = WindowInsets.safeDrawing
    val panelShape = GlassShape(HomeUiTokens.CornerLarge)
    val panelFill = MiuixTheme.colorScheme.surfaceContainer
    val popupMaterial = GlassPopupDefaults.material()
    val popupStroke = GlassPopupDefaults.stroke()
    val pageBackdrop = currentGlassBackdrop
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { hostOrigin = it.positionInWindow() }) {
        // 宿主的遮罩会吞掉触摸，点空白处关闭由这一层处理。
        Box(Modifier.fillMaxSize().pointerInput(onDismiss) { detectTapGestures(onTap = { onDismiss() }) })
        Layout(
            modifier = Modifier.fillMaxSize().drawWithContent {
                // Keep the popup above the log row throughout its scale animation.
                clipRect(bottom = (anchorBounds.top - hostOrigin.y - 8.dp.toPx()).coerceIn(0f, size.height)) {
                    this@drawWithContent.drawContent()
                }
            },
            content = {
                AnimatedVisibility(
                    visibleState = cardVisible,
                    enter = scaleIn(
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f),
                        initialScale = 0.7f,
                        transformOrigin = TransformOrigin(0.5f, 1f),
                    ),
                    exit = scaleOut(
                        animationSpec = tween(150),
                        targetScale = 0.9f,
                        transformOrigin = TransformOrigin(0.5f, 1f),
                    ),
                ) {
                    Box(Modifier.glassPanel(
                            backdrop = pageBackdrop,
                            shape = panelShape,
                            material = popupMaterial,
                            stroke = popupStroke,
                            shading = false,
                            fallback = Modifier.clip(panelShape).background(panelFill),
                        )
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
            val card = measurables.singleOrNull()?.measure(constraints.copy(
                minWidth = cardWidth, maxWidth = cardWidth, minHeight = 0, maxHeight = availableHeight,
            ))
            layout(constraints.maxWidth, constraints.maxHeight) {
                if (card == null) return@layout
                val x = (anchorBounds.left - hostOrigin.x.roundToInt()).coerceIn(safeLeft,
                    (constraints.maxWidth - safeRight - card.width).coerceAtLeast(safeLeft))
                val minY = safeTop + gap
                val y = (anchorBounds.top - hostOrigin.y.roundToInt() - card.height - gap).coerceIn(minY,
                    (constraints.maxHeight - safeBottom - card.height - gap).coerceAtLeast(minY))
                card.place(x, y)
            }
        }
    }
}
