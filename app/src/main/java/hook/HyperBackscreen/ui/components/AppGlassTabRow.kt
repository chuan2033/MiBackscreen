package hook.HyperBackscreen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRow
import top.yukonga.miuix.kmp.glass.GlassTabRow

/** 为标签栏单独记录背景，保持玻璃采样范围清洁。 */
@Composable
internal fun AppGlassTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    segmented: Boolean = false,
) {
    val backdrop = rememberLayerBackdrop()
    Box(modifier = modifier) {
        Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(backgroundColor))
        if (segmented) {
            GlassSegmentedTabRow(
                tabs = tabs,
                selectedIndex = selectedIndex,
                onSelect = onSelect,
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            GlassTabRow(
                tabs = tabs,
                selectedIndex = selectedIndex,
                onSelect = onSelect,
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
