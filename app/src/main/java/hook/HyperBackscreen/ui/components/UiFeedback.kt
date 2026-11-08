package hook.HyperBackscreen.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarResult

internal class UiFeedback(private val scope: CoroutineScope) {
    val host = SnackbarHostState()

    fun dismiss() { scope.launch { host.newestSnackbarData()?.dismiss() } }

    fun show(message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        scope.launch {
            // Keep actionable feedback visible, without accumulating an unbounded stack of errors.
            host.newestSnackbarData()?.dismiss()
            val result = host.showSnackbar(message, actionLabel,
                withDismissAction = actionLabel != null,
                duration = if (actionLabel != null) SnackbarDuration.Indefinite else SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) action?.invoke()
        }
    }
}

internal val LocalUiFeedback = staticCompositionLocalOf<UiFeedback?> { null }

@Composable
internal fun rememberUiFeedback(): UiFeedback {
    val scope = rememberCoroutineScope()
    return remember(scope) { UiFeedback(scope) }
}
