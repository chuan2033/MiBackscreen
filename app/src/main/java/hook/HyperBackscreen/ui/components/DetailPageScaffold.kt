package hook.HyperBackscreen.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.theme.HomeUiTokens
import top.yukonga.miuix.kmp.basic.ButtonColors
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassDialogButtonDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 所有二级页共用的骨架：毛玻璃大标题顶栏 + 返回键 + 可回弹列表 + 首尾留白。
 *
 * 首尾留白在这里统一处理（顶部 12dp、底部 24dp 再加导航栏高度），页面只管往列表里放内容，
 * 不会再出现某页忘了给手势条留空的情况。首个元素自带上边距（例如 SmallTitle）的页面，
 * 把 [topSpacing] 设成 0，避免叠出双倍空白。
 */
@Composable
internal fun DetailPageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    topSpacing: Dp = HomeUiTokens.DetailListTopSpacing,
    actions: @Composable RowScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    /**
     * 固定在顶栏下方、不随列表滚动的区域（例如电量页的背屏预览）。
     * 列表在它下面单独滚动，所以调下面的参数时预览始终可见。
     */
    pinnedHeader: (@Composable () -> Unit)? = null,
    /**
     * 并进顶栏玻璃里的固定区域（[TopBarTabs]、[TopBarSearchField]）：属于顶栏，列表从它下面滑过，
     * 玻璃采样列表层。和 [pinnedHeader] 不同，它不把列表往下推出一块独立区域。
     */
    topBarBottom: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    val scrollBehavior = rememberPageScrollBehavior(listState)
    val isContentScrolled by rememberContentScrolled(scrollBehavior, listState)
    val surfaceColor = MiuixTheme.colorScheme.surface
    // 顶栏采样列表内容，顶栏本身处于采样范围之外。
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    val overlayHost = remember { OverlayHost() }
    Box(modifier = modifier.fillMaxSize().onPlaced { overlayHost.coordinates = it }) {
    CompositionLocalProvider(LocalOverlayHost provides overlayHost) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            AppGlassTopAppBar(
                title = title,
                isContentScrolled = isContentScrolled,
                backdrop = backdrop,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    TopBarIconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Glass.Back,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MiuixTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = actions,
                bottomContent = topBarBottom,
            )
        }
    ) { paddingValues ->
        CompositionLocalProvider(LocalPageBackdrop provides backdrop) {
        Column(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            if (pinnedHeader != null) {
                Spacer(Modifier.height(paddingValues.calculateTopPadding()))
                Box(modifier = Modifier.padding(horizontal = 12.dp)) { pinnedHeader() }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                // 左右留白放进 contentPadding 而不是给列表本身加 padding：列表边界会裁掉卡片阴影，
                // 留白在列表外时卡片阴影左右两侧会被切成一条竖直的灰边。
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = if (pinnedHeader != null) 0.dp else paddingValues.calculateTopPadding()
                )
            ) {
                if (topSpacing > 0.dp) {
                    item(key = "detail_top_spacer") { Spacer(Modifier.height(topSpacing)) }
                }
                content()
                item(key = "detail_bottom_spacer") {
                    Spacer(Modifier.height(HomeUiTokens.DetailListBottomSpacing).navigationBarsPadding())
                }
            }
        }
        }
    }
    }
    CompositionLocalProvider(LocalPageBackdrop provides backdrop) { overlay() }
    }
}

/** [DetailPageScaffold] 外层 Box 的布局坐标，也就是 `overlay` 槽所在的坐标系。 */
internal class OverlayHost {
    var coordinates: LayoutCoordinates? = null
}

internal val LocalOverlayHost = staticCompositionLocalOf<OverlayHost?> { null }

/**
 * 报告控件在 [DetailPageScaffold] `overlay` 坐标系里的边界，给 GlassPopup 的 anchorBounds 用。
 *
 * GlassPopup 要的是它所在 BoxScope 的坐标。boundsInRoot 只在页面静止、正好位于根原点时与之相等；
 * 导航转场或侧滑返回时页面带着 graphicsLayer 变换，两者会差一个变换。
 * 不在 DetailPageScaffold 里时退回 boundsInRoot。
 */
@Composable
internal fun Modifier.onBoundsInOverlay(onBounds: (Rect) -> Unit): Modifier {
    val host = LocalOverlayHost.current
    return onGloballyPositioned { coordinates ->
        val hostCoordinates = host?.coordinates
        onBounds(
            if (hostCoordinates != null && hostCoordinates.isAttached) {
                hostCoordinates.localBoundingBoxOf(coordinates, clipBounds = false)
            } else {
                coordinates.boundsInRoot()
            }
        )
    }
}

/** 弹窗底部的玻璃材质按钮行。 */
@Composable
internal fun DialogButtonRow(
    primaryText: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryText: String? = null,
    onSecondary: () -> Unit = {},
    primaryEnabled: Boolean = true,
    primaryColors: ButtonColors = GlassDialogButtonDefaults.buttonColors(GlassDialogButtonRole.Primary),
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
    ) {
        if (secondaryText != null) {
            GlassDialogButton(
                onClick = onSecondary,
                modifier = Modifier.weight(1f).height(48.dp)
            ) { Text(secondaryText, style = MiuixTheme.textStyles.button) }
        }
        GlassDialogButton(
            onClick = onPrimary,
            modifier = Modifier.weight(1f).height(48.dp),
            enabled = primaryEnabled,
            role = GlassDialogButtonRole.Primary,
            colors = primaryColors,
        ) { Text(primaryText, style = MiuixTheme.textStyles.button) }
    }
}
