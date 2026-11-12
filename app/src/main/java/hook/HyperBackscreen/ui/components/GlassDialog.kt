package hook.HyperBackscreen.ui.components

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.glass.GlassDialog

@Composable
internal fun AppDialog(
    show: Boolean,
    title: String? = null,
    summary: String? = null,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    GlassDialog(
        show = show,
        backdrop = currentGlassBackdrop,
        title = title,
        summary = summary,
        onDismissRequest = { onDismissRequest?.invoke() },
        onDismissFinished = onDismissFinished,
        content = content,
    )
}
