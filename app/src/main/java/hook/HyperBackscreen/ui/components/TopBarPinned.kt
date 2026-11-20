package hook.HyperBackscreen.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRow
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.glass.GlassTextFieldDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup
import top.yukonga.miuix.kmp.glass.glass
import top.yukonga.miuix.kmp.glass.glassShadow
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassMaterials
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 顶栏下方固定区域的外边距：左右与列表卡片对齐。 */
private val PinnedHorizontalPadding = 12.dp
private val PinnedTopPadding = 4.dp
private val PinnedBottomPadding = 8.dp

private fun Modifier.pinnedPadding(): Modifier = padding(
    start = PinnedHorizontalPadding,
    end = PinnedHorizontalPadding,
    top = PinnedTopPadding,
    bottom = PinnedBottomPadding,
)

/**
 * 并进顶栏玻璃里的标签栏：放在 [DetailPageScaffold] 的 `topBarBottom` 里，列表从它下面滑过。
 *
 * 用原生连体分段标签 [GlassSegmentedTabRow]，采样页面列表层；它的玻璃材质跟顶栏玻璃底同一套动画淡入
 * （停在顶部时只剩静止态的底色）。没有采样源（关掉顶栏模糊 / 不支持 RuntimeShader）时退回 [AppGlassTabRow]。
 */
@Composable
internal fun TopBarTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalTopBarGlass.current
    val backdrop = glass?.backdrop
    val rowModifier = modifier.fillMaxWidth().pinnedPadding()
    if (glass != null && backdrop != null) {
        GlassSegmentedTabRow(
            tabs = tabs,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            backdrop = backdrop,
            surfaceAlpha = glass.materialAlpha.value,
            modifier = rowModifier,
        )
    } else {
        AppGlassTabRow(
            tabs = tabs,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            backgroundColor = MiuixTheme.colorScheme.surface,
            segmented = true,
            modifier = rowModifier,
        )
    }
}

/** 搜索框是胶囊形：圆角取大值，绘制时会被限制在高度的一半。 */
private val SearchCornerRadius = 50.dp

/** 上下内边距比普通输入框小，高度与原生搜索框（45dp）接近。 */
private val SearchInsideMargin = DpSize(16.dp, 12.dp)

/**
 * 固定在顶栏下方的玻璃搜索框：原生 [GlassTextField] 套原生玻璃材质（胶囊形、细玻璃、浮起阴影），
 * 采样页面列表层，列表从它下面滑过时透出模糊和折射。
 *
 * 搜索框本身在列表层之外，不会被录进自己的采样。没有采样源时用 [GlassTextField] 自带的半透明底色。
 * 单行输入，键盘回车键显示为「搜索」，提交时收起键盘、清掉焦点；有内容时右侧出现清除按钮。
 * 输入内容由调用方持有的 [state] 保存，页面直接读 `state.text` 过滤列表。
 */
@Composable
internal fun TopBarSearchField(
    state: TextFieldState,
    label: String,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val backdrop = LocalTopBarGlass.current?.backdrop
    val dark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val shape = GlassShape(SearchCornerRadius)
    val glassModifier = if (backdrop != null) {
        // 阴影放在玻璃裁切之外，否则会被裁掉。
        Modifier
            .glassShadow(shape, GlassShadows.Float)
            .glass(
                backdrop = backdrop,
                shape = shape,
                style = GlassDefaults.style(dark),
                material = GlassMaterials.puredThinGlass(dark),
                shading = false,
            )
    } else {
        Modifier
    }
    val hasText = state.text.isNotEmpty()
    GlassTextField(
        state = state,
        modifier = modifier.fillMaxWidth().pinnedPadding().then(glassModifier),
        insideMargin = SearchInsideMargin,
        colors = if (backdrop != null) {
            GlassTextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent)
        } else {
            GlassTextFieldDefaults.textFieldColors()
        },
        cornerRadius = SearchCornerRadius,
        label = label,
        useLabelAsPlaceholder = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        onKeyboardAction = {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        },
        lineLimits = TextFieldLineLimits.SingleLine,
        // 带图标的一侧 TextField 不加内边距，图标自带留白（与原生搜索框相同）。
        leadingIcon = {
            Icon(
                imageVector = MiuixIcons.Basic.Search,
                contentDescription = null,
                modifier = Modifier.padding(start = 16.dp, end = 8.dp),
                tint = MiuixTheme.colorScheme.onSurfaceContainerHigh,
            )
        },
        trailingIcon = {
            AnimatedVisibility(visible = hasText, enter = fadeIn(), exit = fadeOut()) {
                Icon(
                    imageVector = MiuixIcons.Basic.SearchCleanup,
                    contentDescription = stringResource(R.string.common_clear),
                    modifier = Modifier
                        .padding(start = 8.dp, end = 16.dp)
                        .clip(CircleShape)
                        .clickable { state.clearText() },
                    tint = MiuixTheme.colorScheme.onSurfaceContainerHighest,
                )
            }
        },
    )
}
