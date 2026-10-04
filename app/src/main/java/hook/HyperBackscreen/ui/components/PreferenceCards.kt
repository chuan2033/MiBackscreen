package hook.HyperBackscreen.ui.components

import android.widget.Toast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.ui.theme.HomeUiTokens
import hook.HyperBackscreen.R
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
    translucent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        cornerRadius = HomeUiTokens.CardCornerRadius,
        insideMargin = PaddingValues(),
        colors = CardDefaults.defaultColors(
            color = if (translucent) {
                MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f)
            } else {
                MiuixTheme.colorScheme.surfaceContainer
            },
            contentColor = MiuixTheme.colorScheme.onSurfaceContainer
        ),
        pressFeedbackType = pressFeedbackType,
        showIndication = pressFeedbackType != PressFeedbackType.None,
        holdDownState = false,
        onClick = onClick,
        onLongPress = null
    ) {
        content()
    }
}

/** 卡片里受开关控制的选项：出现时向下展开并淡入，消失时收起并淡出。 */
@Composable
internal fun ColumnScope.AnimatedPreferenceVisibility(
    visible: Boolean,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
            expandFrom = Alignment.Top
        ) + fadeIn(
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
        ),
        exit = shrinkVertically(
            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            shrinkTowards = Alignment.Top
        ) + fadeOut(
            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
        )
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
    val feedback = LocalUiFeedback.current
    val failedMessage = stringResource(R.string.activity_open_failed)
    val retryLabel = stringResource(R.string.common_retry)
    fun launchUrl() {
        if (!url.isNullOrBlank() && !openUrl(context, url)) {
            if (feedback != null) feedback.show(failedMessage, retryLabel) { launchUrl() }
            else Toast.makeText(context, failedMessage, Toast.LENGTH_LONG).show()
        }
    }
    ArrowPreference(
        modifier = modifier,
        title = title,
        summary = summary,
        onClick = {
            if (onClick != null) {
                onClick()
            } else if (!url.isNullOrBlank()) {
                launchUrl()
            }
        }
    )
}
