package hook.HyperBackscreen.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.ui.theme.HomeUiTokens
import hook.HyperBackscreen.ui.util.openUrl
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun CardBlock(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    pressFeedbackType: PressFeedbackType = PressFeedbackType.Tilt,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        cornerRadius = HomeUiTokens.CardCornerRadius,
        insideMargin = PaddingValues(),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer,
            contentColor = MiuixTheme.colorScheme.onSurfaceContainer
        ),
        pressFeedbackType = pressFeedbackType,
        showIndication = pressFeedbackType != PressFeedbackType.None,
        holdDownState = false,
        onClick = onClick ?: {},
        onLongPress = {}
    ) {
        content()
    }
}

@Composable
internal fun AboutArrowPreference(
    title: String,
    summary: String?,
    modifier: Modifier = Modifier,
    url: String? = null,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    ArrowPreference(
        modifier = modifier,
        title = title,
        summary = summary,
        onClick = {
            if (onClick != null) {
                onClick()
            } else if (!url.isNullOrBlank()) {
                openUrl(context, url)
            }
        }
    )
}
