package hook.HyperBackscreen.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.remember
import android.content.Context
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
        cornerRadius = HomeUiTokens.CornerCard,
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
 *
 * [iconRes] 可空：页面内的普通折叠项没有专属图标，不必为了它去凑一张图。
 */
@Composable
internal fun ColumnScope.CollapsibleFunctionGroup(
    title: String,
    @DrawableRes iconRes: Int? = null,
    initiallyExpanded: Boolean = false,
    /** 分组内已开启的项数；大于 0 时显示在箭头左侧，折叠时也能看出这组有东西开着。 */
    enabledCount: Int? = null,
    /** 给了 key 就把展开状态写进本地 UI 偏好，下次打开 App 保持上次的展开 / 收起。 */
    persistKey: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val context = LocalContext.current
    val uiPrefs = remember(context) {
        context.getSharedPreferences(GROUP_EXPANDED_PREFS, Context.MODE_PRIVATE)
    }
    // 用标题做 key，否则同页多个分组会共用同一个 saveable 状态（展开一个就全展开）。
    var expanded by rememberSaveable(title) {
        mutableStateOf(
            persistKey?.let { uiPrefs.getBoolean(GROUP_EXPANDED_KEY_PREFIX + it, initiallyExpanded) }
                ?: initiallyExpanded
        )
    }
    val startAction: (@Composable () -> Unit)? = if (iconRes != null) {
        {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }
    } else {
        null
    }
    // BasicComponent 是基础行组件，没有自带尾部箭头，两端插槽完全自控。
    BasicComponent(
        title = title,
        startAction = startAction,
        endActions = {
            if (enabledCount != null && enabledCount > 0) {
                Text(
                    text = pluralStringResource(R.plurals.function_group_enabled_count, enabledCount, enabledCount),
                    modifier = Modifier.padding(end = 8.dp),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions
                )
            }
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
        onClick = {
            expanded = !expanded
            persistKey?.let {
                uiPrefs.edit().putBoolean(GROUP_EXPANDED_KEY_PREFIX + it, expanded).apply()
            }
        }
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
    endActions: @Composable RowScope.() -> Unit = {},
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
        endActions = endActions,
        onClick = {
            if (onClick != null) {
                onClick()
            } else if (!url.isNullOrBlank()) {
                launchUrl()
            }
        }
    )
}

/** 与颜色模式共用的本地 UI 偏好文件；只存界面状态，不进模块远程偏好、也不进配置备份。 */
private const val GROUP_EXPANDED_PREFS = "app_ui_prefs"
private const val GROUP_EXPANDED_KEY_PREFIX = "group_expanded_"
