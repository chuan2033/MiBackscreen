package hook.HyperBackscreen.ui.about

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.components.BlurredBar
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.updater.UpdateChecker
import hook.HyperBackscreen.ui.updater.UpdateInfo
import hook.HyperBackscreen.ui.updater.updateNotes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 「版本历史」二级页：按发布时间倒序列出每个正式版的更新说明。 */
@Composable
internal fun VersionHistoryPage(onBack: () -> Unit) {
    var releases by remember { mutableStateOf<List<UpdateInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        releases = UpdateChecker.history()
        loading = false
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = stringResource(R.string.about_version_history),
                    largeTitle = stringResource(R.string.about_version_history),
                    color = Color.Transparent,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MiuixTheme.colorScheme.onBackground
                            )
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.layerBackdrop(backdrop)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding()
                )
            ) {
                item { Spacer(Modifier.height(12.dp)) }
                when {
                    loading -> items(4) {
                        ReleaseSkeletonCard()
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
                item { Spacer(Modifier.height(24.dp)) }
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
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "v" + info.versionName,
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.title4,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatReleaseDate(info.publishedAt),
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    style = MiuixTheme.textStyles.body2
                )
            }
            Spacer(Modifier.height(8.dp))
            AnimatedVisibility(visible = !expanded) {
                Text(
                    text = notesPreview(info.notes),
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    style = MiuixTheme.textStyles.body2,
                    maxLines = 2
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = releaseNotes(info),
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    style = MiuixTheme.textStyles.body2
                )
            }
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
private fun ReleaseSkeletonCard() {
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
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
                    colors = ShimmerColors,
                    start = Offset(progress * 600f, 0f),
                    end = Offset(progress * 600f + 300f, 0f)
                )
            )
    )
}

private val ShimmerColors = listOf(
    Color(0xFFE4E4E7),
    Color(0xFFF4F4F6),
    Color(0xFFE4E4E7)
)

private fun releaseNotes(info: UpdateInfo): AnnotatedString = updateNotes(info.notes)

private fun formatReleaseDate(iso: String): String {
    if (iso.isEmpty()) return ""
    return try {
        val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).parse(iso)
        if (parsed != null) SimpleDateFormat("yyyy-MM-dd", Locale.US).format(parsed) else iso.take(10)
    } catch (_: Exception) {
        iso.take(10)
    }
}
