package hook.HyperBackscreen.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.ui.theme.currentTopBarBlur
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassMotion
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported

/** 顶栏右侧多个按钮之间的间距。 */
private val TopBarActionSpacing = 8.dp

/**
 * 顶栏的滚动行为。在 miuix 原生行为之外补一件事：把顶栏自身的折叠位移也计进 contentOffset。
 *
 * miuix 的 contentOffset 只累计列表自己吃掉的滚动，而原生渐进模糊和按钮阴影都按它淡入。
 * 内容很短的页面整段拖动都被顶栏折叠吃掉、列表本身不动，顶栏折起来了玻璃底却一直是透明的。
 * 顶栏折叠时内容区的上边距也跟着移动，内容确实已经滑到顶栏下面，所以这段位移同样算「已滚动」。
 */
@Composable
internal fun rememberPageScrollBehavior(): ScrollBehavior {
    val state = rememberTopAppBarState()
    val behavior = MiuixScrollBehavior(state)
    LaunchedEffect(state) {
        var previous = state.heightOffset
        // 页面恢复时顶栏可能已经是折叠的。
        state.contentOffset = minOf(state.contentOffset, previous)
        snapshotFlow { state.heightOffset }.collect { height ->
            val delta = height - previous
            previous = height
            if (delta != 0f) state.contentOffset = (state.contentOffset + delta).coerceAtMost(0f)
        }
    }
    return behavior
}

/**
 * 顶栏下方固定区域（标签栏、搜索框）的玻璃采样源，只在 [AppGlassTopAppBar] 的 bottomContent 里有值。
 *
 * [backdrop] 是页面列表那一层：固定区域本身在列表层之外，采样不会录到自己。
 * 关掉「顶栏模糊」或设备不支持 RuntimeShader 时为 null，固定区域退回普通控件。
 * [materialAlpha] 跟顶栏玻璃底同一套动画：内容滑到顶栏下面时从 0 淡入到 1。
 */
internal class TopBarGlass(
    val backdrop: Backdrop?,
    val materialAlpha: State<Float>,
)

internal val LocalTopBarGlass = staticCompositionLocalOf<TopBarGlass?> { null }

/**
 * 模块统一的玻璃顶栏：miuix 原生 [GlassTopAppBar] + 原生渐进模糊背景。
 *
 * - 背景用原生 `progressiveBlur`，玻璃底、按钮材质、按钮阴影、大标题模糊由同一套滚动状态联动；
 *   关掉「顶栏模糊」时不传 backdrop，原生顶栏退回渐变实色底。
 * - 右侧多个按钮之间统一留 [TopBarActionSpacing]。
 * - [bottomContent] 固定在标题下方、属于顶栏（列表从它下面滑过），用 [LocalTopBarGlass] 拿玻璃采样源。
 *
 * [backdrop] 必须是页面列表那一层（顶栏本身在采样范围之外）。
 */
@Composable
internal fun AppGlassTopAppBar(
    title: String,
    isContentScrolled: Boolean,
    backdrop: Backdrop,
    scrollBehavior: ScrollBehavior,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: (@Composable () -> Unit)? = null,
) {
    val barBackdrop = backdrop.takeIf { currentTopBarBlur }
    val supported = remember { isRuntimeShaderSupported() }
    val materialAlpha = animateFloatAsState(
        targetValue = if (isContentScrolled) 1f else 0f,
        animationSpec = GlassMotion.topBarButtonFloat(),
        label = "topBarPinnedMaterial",
    )
    GlassTopAppBar(
        title = title,
        largeTitle = title,
        isContentScrolled = isContentScrolled,
        modifier = modifier,
        progressiveBlur = true,
        backdrop = barBackdrop,
        scrollBehavior = scrollBehavior,
        navigationIcon = navigationIcon,
        actions = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(TopBarActionSpacing),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        },
        bottomContent = {
            if (bottomContent != null) {
                CompositionLocalProvider(
                    LocalTopBarGlass provides TopBarGlass(
                        backdrop = barBackdrop.takeIf { supported },
                        materialAlpha = materialAlpha,
                    ),
                    content = bottomContent,
                )
            }
        },
    )
}

/**
 * 顶栏按钮。可用时是原生玻璃圆钮 [GlassIconButton]（不支持 RuntimeShader 的设备上它自己会退回实色底）；
 * [GlassIconButton] 没有禁用态，禁用时换成同尺寸的普通 [IconButton] 并压到 38% 透明度。
 */
@Composable
internal fun TopBarIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (enabled) {
        GlassIconButton(onClick = onClick, modifier = modifier, content = content)
    } else {
        IconButton(
            onClick = onClick,
            modifier = modifier.alpha(0.38f),
            enabled = false,
            minWidth = GlassTopAppBarDefaults.ButtonSize,
            minHeight = GlassTopAppBarDefaults.ButtonSize,
            content = content,
        )
    }
}
