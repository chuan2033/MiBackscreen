package hook.HyperBackscreen.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.ui.liquid.lens
import hook.HyperBackscreen.ui.liquid.vibrancy
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupLayoutPosition
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberListPopupLayoutInfo
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils

/**
 * 当前页面列表那一层的 backdrop（[DetailPageScaffold] / 主页 Tab 页提供）。
 * 玻璃下拉菜单拿它做模糊和折射采样。
 */
internal val LocalPageBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * 整个界面（导航内容）那一层的 backdrop，由 RearScreenApp 在根部提供。弹窗和下拉优先用它：
 * 不管弹窗写在页面框架里面还是外面都能拿到，采样范围也覆盖顶栏和底栏。
 */
internal val LocalRootBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** 玻璃效果的采样源：根 backdrop 优先，没有时退回页面 backdrop。 */
internal val currentGlassBackdrop: LayerBackdrop?
    @Composable
    get() = LocalRootBackdrop.current ?: LocalPageBackdrop.current

/**
 * 模块统一的下拉选项：只把 miuix [WindowDropdownPreference] 菜单的不透明底换成液态玻璃，其余全部照搬原生：
 * 锚点（行尾「值 + 箭头」那一块）、位置、尺寸、16dp 圆角、背景压暗、打开/关闭动画（从锚点角 0.15→1 放大 +
 * 按弹出方向圆角展开 + 淡入，同一套弹簧和时长）、选中后的触感与按住态。
 *
 * 为了能采样到页面做模糊，菜单不再开新窗口，而是画在根 Scaffold 的覆盖层里。
 * 没有可采样的页面（[LocalPageBackdrop] 为空）或设备不支持 RuntimeShader 时，退回原生下拉。
 */
@Composable
internal fun AppDropdownPreference(
    items: List<String>,
    selectedIndex: Int,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    startAction: @Composable (() -> Unit)? = null,
    onSelectedIndexChange: (Int) -> Unit,
) {
    val backdrop = currentGlassBackdrop
    val glass = backdrop != null && remember { isRuntimeShaderSupported() }
    if (!glass || backdrop == null) {
        WindowDropdownPreference(
            items = items,
            selectedIndex = selectedIndex,
            title = title,
            modifier = modifier,
            summary = summary,
            enabled = enabled,
            startAction = startAction,
            onSelectedIndexChange = onSelectedIndexChange,
        )
        return
    }

    // 下面这段与 miuix WindowDropdownPreference 的写法一一对应。
    var expanded by remember { mutableStateOf(false) }
    var holdDown by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val available = enabled && items.isNotEmpty()
    val actionColor = if (available) {
        MiuixTheme.colorScheme.onSurfaceVariantActions
    } else {
        MiuixTheme.colorScheme.disabledOnSecondaryVariant
    }

    BasicComponent(
        modifier = modifier,
        interactionSource = remember { MutableInteractionSource() },
        title = title,
        summary = summary,
        startAction = startAction,
        endActions = {
            items.getOrNull(selectedIndex)?.takeIf { it.isNotEmpty() }?.let { value ->
                Text(
                    text = value,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .align(Alignment.CenterVertically)
                        .weight(1f, fill = false),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = actionColor,
                    textAlign = TextAlign.End,
                )
            }
            DropdownArrowEndAction(actionColor = actionColor)
            // 和原生一样放在 endActions 里：锚点就是行尾这一块。
            GlassDropdownPopup(
                items = items,
                selectedIndex = selectedIndex,
                show = expanded,
                backdrop = backdrop,
                onSelect = { index ->
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onSelectedIndexChange(index)
                },
                onDismiss = { expanded = false },
                onDismissFinished = { holdDown = false },
            )
        },
        onClick = {
            if (available) {
                expanded = !expanded
                if (expanded) {
                    holdDown = true
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                }
            }
        },
        role = Role.DropdownList,
        holdDownState = holdDown,
        enabled = available,
    )
}

/** 对应 miuix 的 ListPopupLayout（Align.End、窗口压暗、原生动画规格），只是宿主换成页面覆盖层。 */
@Composable
private fun GlassDropdownPopup(
    items: List<String>,
    selectedIndex: Int,
    show: Boolean,
    backdrop: LayerBackdrop,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    val fraction = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    val dim = remember { Animatable(0f) }
    var mounted by remember { mutableStateOf(false) }
    val dismiss by rememberUpdatedState(onDismiss)
    val dismissFinished by rememberUpdatedState(onDismissFinished)

    LaunchedEffect(show) {
        if (show) {
            mounted = true
            launch { fraction.animateTo(1f, ListPopupDefaults.FractionAnimationSpec) }
            launch { alpha.animateTo(1f, ListPopupDefaults.AlphaEnterAnimationSpec) }
            launch { dim.animateTo(1f, ListPopupDefaults.DimEnterAnimationSpec) }
        } else {
            if (!mounted) return@LaunchedEffect
            launch { fraction.animateTo(0f, ListPopupDefaults.FractionAnimationSpec) }
            launch { dim.animateTo(0f, ListPopupDefaults.DimExitAnimationSpec) }
            alpha.animateTo(0f, ListPopupDefaults.AlphaExitAnimationSpec)
            fraction.snapTo(0f)
            alpha.snapTo(0f)
            dim.snapTo(0f)
            mounted = false
            dismissFinished()
        }
    }
    if (!show && !mounted) return

    var parentBounds by remember { mutableStateOf(IntRect.Zero) }
    Spacer(
        modifier = Modifier.onGloballyPositioned { child ->
            child.parentLayoutCoordinates?.let { parent ->
                val position = parent.positionInWindow()
                parentBounds = IntRect(
                    left = position.x.toInt(),
                    top = position.y.toInt(),
                    right = position.x.toInt() + parent.size.width,
                    bottom = position.y.toInt() + parent.size.height,
                )
            }
        }
    )
    if (parentBounds == IntRect.Zero) return

    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    var hostPosition by remember { mutableStateOf(Offset.Zero) }
    val positionProvider = ListPopupDefaults.DropdownPositionProvider
    val alignment = PopupPositionProvider.Align.End
    val layoutInfo = rememberListPopupLayoutInfo(
        alignment = alignment,
        popupPositionProvider = positionProvider,
        parentBounds = parentBounds,
        popupContentSize = contentSize,
    )
    val popupVisible = remember { mutableStateOf(false) }
    popupVisible.value = mounted
    val dimColor = MiuixTheme.colorScheme.windowDimming

    MiuixPopupUtils.PopupLayout(
        visible = popupVisible,
        enterTransition = EnterTransition.None,
        exitTransition = ExitTransition.None,
        enableWindowDim = false, // 压暗自己画，节奏和原生一致
        enableBackHandler = false,
        renderInRootScaffold = true,
    ) {
        BackHandler(enabled = show) { dismiss() }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = dim.value }
                    .background(dimColor)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { hostPosition = it.positionInWindow() }
                    .pointerInput(Unit) { detectTapGestures { dismiss() } }
                    .layout { measurable, constraints ->
                        val windowBounds = layoutInfo.windowBounds
                        val margin = layoutInfo.popupMargin
                        val minHeight = ListPopupDefaults.MinPopupHeight.roundToPx()
                        val placeable = measurable.measure(
                            constraints.copy(
                                maxHeight = (windowBounds.height - margin.top - margin.bottom).coerceAtLeast(minHeight),
                                minHeight = if (minHeight <= constraints.maxHeight) minHeight else constraints.maxHeight,
                                maxWidth = constraints.maxWidth,
                                minWidth = ListPopupDefaults.MinWidth.roundToPx().coerceAtMost(constraints.maxWidth),
                            )
                        )
                        val offset = positionProvider.calculatePosition(
                            parentBounds,
                            windowBounds,
                            layoutDirection,
                            IntSize(placeable.width, placeable.height),
                            margin,
                            alignment,
                        )
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.place(
                                IntOffset(offset.x - hostPosition.x.toInt(), offset.y - hostPosition.y.toInt())
                            )
                        }
                    },
            ) {
                GlassListPopupContent(
                    fraction = { fraction.value },
                    alpha = { alpha.value },
                    layoutPosition = layoutInfo.popupLayoutPosition,
                    transformOrigin = layoutInfo.localTransformOrigin,
                    backdrop = backdrop,
                    modifier = Modifier.onSizeChanged { contentSize = it },
                ) {
                    val colors = DropdownDefaults.dropdownColors(
                        containerColor = Color.Transparent,
                        selectedContainerColor = Color.Transparent,
                    )
                    ListPopupColumn {
                        items.forEachIndexed { index, text ->
                            DropdownImpl(
                                item = DropdownItem(text = text, selected = index == selectedIndex),
                                optionSize = items.size,
                                isSelected = index == selectedIndex,
                                index = index,
                                dropdownColors = colors,
                                onSelectedIndexChange = {
                                    onSelect(it)
                                    dismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 对应 miuix 的 ListPopupContent：同样的 0.15→1 缩放、锚点角为原点、淡入、按弹出方向的圆角展开，
 * 只把 `background(surfaceContainer)` 换成玻璃。
 *
 * 这些图层属性都放进 drawBackdrop 的 layerBlock：缩放会被反向作用到背景采样上，玻璃里透出的背景
 * 始终和真实背景对齐；展开裁剪（shape + clip）在同一个图层里，和原生一样跟着缩放走。
 */
@Composable
private fun GlassListPopupContent(
    fraction: () -> Float,
    alpha: () -> Float,
    layoutPosition: PopupLayoutPosition,
    transformOrigin: TransformOrigin,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val surface = MiuixTheme.colorScheme.surfaceContainer
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    // 半透明底：浅色偏白、深色偏黑，菜单文字在任何背景上都读得清。
    val tint = if (dark) surface.copy(alpha = 0.68f) else Color.White.copy(alpha = 0.7f)
    // 必须是 CornerBasedShape：透镜折射和高光描边按圆角半径算。
    val shape = remember { RoundedCornerShape(MenuCornerRadius) }
    Box(
        modifier = modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(12.dp.toPx(), 12.dp.toPx())
                    lens(refractionHeight = 14.dp.toPx(), refractionAmount = 20.dp.toPx())
                },
                highlight = { Highlight.Default.copy(alpha = if (dark) 0.45f else 0.8f) },
                layerBlock = {
                    val f = fraction()
                    val scale = 0.15f + 0.85f * f
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha()
                    this.transformOrigin = transformOrigin
                    // 原生 popupClipReveal：弹簧会过冲，裁剪带夹在 0..1。
                    this.shape = RevealBandShape(f.coerceIn(0f, 1f), layoutPosition, MenuCornerRadius)
                    clip = true
                },
                onDrawSurface = { drawRect(tint) },
            )
            .clip(shape),
    ) {
        content()
    }
}

/** 原生 popupClipReveal 的可见带：下方弹出从顶部长出，上方弹出从底部长出，其余从中间向两侧，四角保持圆角。 */
private class RevealBandShape(
    private val progress: Float,
    private val position: PopupLayoutPosition,
    private val cornerRadius: Dp,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val visible = size.height * progress
        val top = when {
            position.showBelow -> 0f
            position.showAbove -> size.height - visible
            else -> (size.height - visible) / 2f
        }
        val radius = with(density) { cornerRadius.toPx() }.coerceAtMost(visible / 2f).coerceAtLeast(0f)
        return Outline.Rounded(
            RoundRect(0f, top, size.width, top + visible, CornerRadius(radius, radius))
        )
    }
}

/** 与 miuix ListPopupContent 相同的 16dp 圆角。 */
private val MenuCornerRadius = 16.dp
