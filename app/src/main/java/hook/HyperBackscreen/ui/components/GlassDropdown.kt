package hook.HyperBackscreen.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference

internal val LocalPageBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
internal val LocalRootBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

internal val currentGlassBackdrop: LayerBackdrop?
    @Composable get() = LocalRootBackdrop.current ?: LocalPageBackdrop.current

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
    GlassDropdownPreference(
        items = items,
        selectedIndex = selectedIndex,
        title = title,
        modifier = modifier,
        summary = summary,
        enabled = enabled,
        startAction = startAction,
        backdrop = currentGlassBackdrop,
        onSelectedIndexChange = onSelectedIndexChange,
    )
}
