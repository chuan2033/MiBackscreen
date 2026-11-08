package hook.HyperBackscreen.ui.components

import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.theme.HomeUiTokens
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
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
    /**
     * 固定在顶栏下方、不随列表滚动的区域（例如电量页的背屏预览）。
     * 列表在它下面单独滚动，所以调下面的参数时预览始终可见。
     */
    pinnedHeader: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val surfaceColor = MiuixTheme.colorScheme.surface
    // 毛玻璃顶栏采样的是列表这一层；BlurredBar 必须在 layerBackdrop 子树之外，否则采样成环。
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = title,
                    largeTitle = title,
                    color = Color.Transparent,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MiuixTheme.colorScheme.onBackground
                            )
                        }
                    },
                    actions = actions
                )
            }
        }
    ) { paddingValues ->
        CompositionLocalProvider(LocalPageBackdrop provides backdrop) {
        Column(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            if (pinnedHeader != null) {
                Spacer(Modifier.height(paddingValues.calculateTopPadding()))
                Box(modifier = Modifier.padding(horizontal = 12.dp)) { pinnedHeader() }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                // 左右留白放进 contentPadding 而不是给列表本身加 padding：列表边界会裁掉卡片阴影，
                // 留白在列表外时 Mochi 的卡片阴影左右两侧被切成一条竖直的灰边。
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

/**
 * 弹窗底部按钮行的唯一写法：左边次要操作（TextButton），右边主操作（主色 Button），
 * 两者等宽、高 48dp。没有次要操作时主按钮占满整行。
 */
@Composable
internal fun DialogButtonRow(
    primaryText: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryText: String? = null,
    onSecondary: () -> Unit = {},
    primaryEnabled: Boolean = true,
    primaryColors: ButtonColors = ButtonDefaults.buttonColorsPrimary(),
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
    ) {
        if (secondaryText != null) {
            TextButton(
                text = secondaryText,
                onClick = onSecondary,
                modifier = Modifier.weight(1f).height(48.dp)
            )
        }
        Button(
            onClick = onPrimary,
            modifier = Modifier.weight(1f).height(48.dp),
            enabled = primaryEnabled,
            colors = primaryColors,
            content = { Text(primaryText) }
        )
    }
}
