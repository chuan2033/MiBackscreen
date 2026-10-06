package hook.HyperBackscreen.ui.components

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.ui.theme.HomeUiTokens
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.util.openUrl
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
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
        // AnimatedVisibility 内部是单槽布局，多个子项会叠在同一位置，这里统一包一层纵向排列。
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

/**
 * 可折叠的功能分组行：标题行尾随展开箭头（收起为 ExpandMore、展开为 ExpandLess），
 * 点标题展开或收起，展开内容带纵向展开/淡入动画。
 *
 * 只渲染行与展开内容，卡片容器由调用方提供，便于把多个分组合并在同一个盒子内。
 * 折叠状态只存在本地 UI，不涉及导航栈，因此不会影响已有二级页面。
 */
@Composable
internal fun ColumnScope.CollapsibleFunctionGroup(
    title: String,
    @DrawableRes iconRes: Int,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    // 用标题做 key，否则同页多个分组会共用同一个 saveable 状态（展开一个就全展开）。
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    // BasicComponent 是基础行组件，没有自带尾部箭头，两端插槽完全自控。
    BasicComponent(
        title = title,
        startAction = {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        },
        endActions = {
            Crossfade(
                targetState = expanded,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
            ) { isExpanded ->
                Icon(
                    imageVector = if (isExpanded) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions
                )
            }
        },
        onClick = { expanded = !expanded }
    )
    AnimatedPreferenceVisibility(visible = expanded) {
        content()
    }
}

@Composable
internal fun AboutArrowPreference(
    title: String,
    summary: String?,
    modifier: Modifier = Modifier,
    url: String? = null,
    @DrawableRes avatar: Int? = null,
    startAction: (@Composable () -> Unit)? = null,
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

    val avatarPainter = avatar?.let { painterResource(it) }

    ArrowPreference(
        modifier = modifier,
        title = title,
        summary = summary,
        startAction = startAction ?: if (avatarPainter != null) {
            {
                Image(
                    painter = avatarPainter,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(HomeUiTokens.AboutAvatarSize)
                        .clip(CircleShape)
                )
            }
        } else null,
        onClick = {
            if (onClick != null) {
                onClick()
            } else if (!url.isNullOrBlank()) {
                launchUrl()
            }
        }
    )
}
