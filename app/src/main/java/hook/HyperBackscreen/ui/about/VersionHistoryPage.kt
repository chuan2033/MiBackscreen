package hook.HyperBackscreen.ui.about

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import hook.HyperBackscreen.ui.components.DetailPageScaffold
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import hook.HyperBackscreen.BuildConfig
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.updater.UpdateChecker
import hook.HyperBackscreen.ui.updater.UpdateInfo
import hook.HyperBackscreen.ui.updater.updateNotes
import hook.HyperBackscreen.ui.theme.currentAppColors
import java.text.SimpleDateFormat
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** 「版本历史」二级页：按发布时间倒序列出每个正式版的更新说明。 */
@Composable
internal fun VersionHistoryPage(onBack: () -> Unit) {
    var releases by remember { mutableStateOf<List<UpdateInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val loadingDescription = stringResource(R.string.about_version_history_loading)
    LaunchedEffect(Unit) {
        releases = UpdateChecker.history()
        loading = false
    }


    DetailPageScaffold(
        title = stringResource(R.string.about_version_history),
        onBack = onBack,
    ) {
        when {
            loading -> items(4) {
                ReleaseSkeletonCard(
                    // 只在第一张骨架卡上挂描述，读屏念一次「正在加载」，不重复四遍。
                    modifier = if (it == 0) {
                        Modifier.semantics {
                            contentDescription = loadingDescription
                            liveRegion = LiveRegionMode.Polite
                        }
                    } else {
                        Modifier.clearAndSetSemantics {}
                    }
                )
                Spacer(Modifier.height(6.dp))
            }
            releases.isEmpty() -> item {
                StatusText(stringResource(R.string.about_version_history_empty))
            }
            else -> items(releases.size) { index ->
                ReleaseCard(releases[index])
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        style = MiuixTheme.textStyles.body2
    )
}

@Composable
private fun ReleaseCard(info: UpdateInfo) {
    var expanded by remember { mutableStateOf(false) }
    val expandedState = stringResource(R.string.common_expanded)
    val collapsedState = stringResource(R.string.common_collapsed)
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 整张卡可点：读屏会念出「按钮，已收起，双击展开」，而不只是一段没有角色的文字。
                .clickable(
                    onClickLabel = stringResource(
                        if (expanded) R.string.about_version_history_collapse
                        else R.string.about_version_history_expand
                    ),
                    role = Role.Button
                ) { expanded = !expanded }
                .semantics {
                    stateDescription = if (expanded) expandedState else collapsedState
                }
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "v" + info.versionName,
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.title4
                )
                // 标出当前安装的版本，翻历史时能知道自己落后了几个版本。
                if (info.versionName == BuildConfig.VERSION_NAME) {
                    Text(
                        text = stringResource(R.string.about_version_history_current),
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        color = MiuixTheme.colorScheme.primary,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatReleaseDate(info.publishedAt),
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    style = MiuixTheme.textStyles.body2
                )
            }
            Spacer(Modifier.height(8.dp))
            // 同一个 Text 只换内容和行数，高度由 animateContentSize 平滑过渡，
            // 不再是两个 AnimatedVisibility 一出一进导致卡片高度先缩后涨。
            val preview = remember(info) { AnnotatedString(notesPreview(info.notes)) }
            val codeBackground = currentAppColors.codeBackground
            val full = remember(info, codeBackground) { releaseNotes(info, codeBackground) }
            Text(
                text = if (expanded) full else preview,
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(tween(durationMillis = 260, easing = FastOutSlowInEasing)),
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = if (expanded) 0.85f else 0.7f),
                style = MiuixTheme.textStyles.body2,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        if (expanded) R.string.about_version_history_collapse
                        else R.string.about_version_history_expand
                    ),
                    color = MiuixTheme.colorScheme.primary,
                    style = MiuixTheme.textStyles.body2
                )
            }
        }
    }
}

/** 默认预览：取更新说明的前两条非空行，去掉列表符号与行内标记。 */
private fun notesPreview(markdown: String): String {
    val lines = markdown.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            line.removePrefix("- ").removePrefix("* ").removePrefix("•  ")
                .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
                .replace(Regex("`([^`]+)`"), "$1")
                .replace(Regex("\\*([^*]+)\\*"), "$1")
        }
        .take(2)
        .toList()
    return lines.joinToString("\n").ifEmpty { markdown.trim().take(60) }
}

/** 加载占位卡片：四条流光灰条，布局对齐真实的版本卡片。 */
@Composable
private fun ReleaseSkeletonCard(modifier: Modifier = Modifier) {
    CardBlock(modifier = modifier, pressFeedbackType = PressFeedbackType.None) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ShimmerBar(widthFraction = 0.45f, height = 16.dp)
            ShimmerBar(widthFraction = 0.3f, height = 11.dp)
            ShimmerBar(widthFraction = 0.92f, height = 13.dp)
            ShimmerBar(widthFraction = 0.62f, height = 13.dp)
        }
    }
}

@Composable
private fun ShimmerBar(widthFraction: Float, height: Dp) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerProgress"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(
                Brush.linearGradient(
                    colors = shimmerColors,
                    start = Offset(progress * 600f, 0f),
                    end = Offset(progress * 600f + 300f, 0f)
                )
            )
    )
}

private val shimmerColors: List<Color>
    @Composable get() {
        val colors = currentAppColors
        return listOf(colors.shimmerBase, colors.shimmerHighlight, colors.shimmerBase)
    }

private fun releaseNotes(info: UpdateInfo, codeBackground: Color): AnnotatedString =
    updateNotes(info.notes, codeBackground)

private fun formatReleaseDate(iso: String): String {
    if (iso.isEmpty()) return ""
    return try {
        val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).parse(iso)
        if (parsed != null) SimpleDateFormat("yyyy-MM-dd", Locale.US).format(parsed) else iso.take(10)
    } catch (_: Exception) {
        iso.take(10)
    }
}
