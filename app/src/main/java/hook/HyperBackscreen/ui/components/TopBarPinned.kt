package hook.HyperBackscreen.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRow
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

/**
 * 固定在顶栏下方的玻璃搜索框：原生 [InputField] 套原生玻璃材质（胶囊形、细玻璃、浮起阴影），
 * 采样页面列表层，列表从它下面滑过时透出模糊和折射。
 *
 * 搜索框本身在列表层之外，不会被录进自己的采样。没有采样源时就是原生实色搜索框。
 * 提交搜索时收起键盘、清掉焦点。
 */
@Composable
internal fun TopBarSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val backdrop = LocalTopBarGlass.current?.backdrop
    val dark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val shape = GlassShape(50.dp)
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
    InputField(
        query = query,
        onQueryChange = onQueryChange,
        onSearch = {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        },
        expanded = expanded,
        onExpandedChange = { expanded = it },
        label = label,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().pinnedPadding().then(glassModifier),
    )
}
