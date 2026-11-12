package hook.HyperBackscreen.ui.about

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import hook.HyperBackscreen.ui.components.AppDialog
import hook.HyperBackscreen.ui.components.DialogButtonRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import hook.HyperBackscreen.ui.util.openUrl
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 日志导出对话框：用 miuix 官方 GlassDialog（经 [AppDialog]），和应用里其它对话框外观、动画一致。 */
@Composable
internal fun LogExportPopup(
    show: Boolean,
    state: LogExportState,
    shareFailed: Boolean,
    fileMissing: Boolean,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
) {
    // 标题直接说当前状态，正文不再重复一遍。
    // 点外面或返回只关闭对话框：生成中关掉也会在后台继续，进度看「日志」行；取消用底部按钮。
    AppDialog(
        show = show,
        title = stringResource(when (state) {
            is LogExportState.Running -> R.string.log_title_running
            is LogExportState.Ready -> R.string.log_export_complete
            LogExportState.Failed -> R.string.log_failed
            LogExportState.Idle -> R.string.home_log_title
        }),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                // 生成中 → 完成时会多出文件信息和按钮：高度平滑过渡，不要一下跳高。
                // 裁到动画中的尺寸：否则新内容按最终高度先画出来，按钮会伸出对话框。
                .clipToBounds()
                .animateContentSize(spring(dampingRatio = 0.9f, stiffness = 500f)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val running = state is LogExportState.Running
            // 只有出错时才需要正文说明。
            val notice = when {
                fileMissing -> R.string.log_file_missing
                shareFailed -> R.string.log_share_failed
                else -> null
            }
            if (notice != null) {
                Text(
                    text = stringResource(notice),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (state is LogExportState.Running) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.weight(1f),
                        progress = state.completed.toFloat() / state.total.coerceAtLeast(1),
                    )
                    Text(
                        text = stringResource(R.string.log_stage_count, state.completed, state.total),
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            // 导出的包会同时往手机存储根目录放一份：文件名、所在目录和「复制路径」收进一张小卡片，
            // 方便在文件管理器里直接找到。
            val savedFile = (state as? LogExportState.Ready)?.sharedPath?.takeIf { !fileMissing }
            val savedDirectory = savedFile?.parent
            if (savedFile != null && savedDirectory != null) {
                val context = LocalContext.current
                var copied by remember(savedFile) { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MiuixTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                            RoundedCornerShape(16.dp),
                        )
                        .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = savedFile.name,
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                        Text(
                            text = stringResource(R.string.log_saved_to, savedDirectory),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                    }
                    // 复制完整文件路径，方便在文件管理器或电脑上直接定位这份日志。
                    PressFadeLink(
                        text = stringResource(if (copied) R.string.log_path_copied else R.string.log_copy_path),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        copyToClipboard(context, savedFile.absolutePath)
                        copied = true
                    }
                }
            }
            if (running) {
                GlassDialogButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text(stringResource(R.string.common_cancel), style = MiuixTheme.textStyles.button) }
            } else {
                val ready = state is LogExportState.Ready && !fileMissing
                // 去 GitHub 新建 Issue：链接预填环境信息；文件没法随链接上传，先复制日志路径，提示用户在网页里添加附件。
                val readyState = state as? LogExportState.Ready
                if (ready && readyState != null) {
                    val context = LocalContext.current
                    GlassDialogButton(
                        onClick = {
                            val archive = readyState.sharedPath ?: readyState.file
                            val url = FeedbackIssue.buildUrl(context, archive.name)
                            if (openUrl(context, url)) {
                                copyToClipboard(context, archive.absolutePath)
                                Toast.makeText(context, R.string.log_report_issue_copied, Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, R.string.log_report_issue_failed, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text(stringResource(R.string.log_report_issue), style = MiuixTheme.textStyles.button) }
                }
                DialogButtonRow(
                    secondaryText = if (ready) stringResource(R.string.log_regenerate) else null,
                    onSecondary = onExport,
                    primaryText = stringResource(when {
                        fileMissing -> R.string.log_regenerate
                        shareFailed || state is LogExportState.Failed -> R.string.common_retry
                        state is LogExportState.Ready -> R.string.common_share
                        else -> R.string.log_export_start
                    }),
                    onPrimary = if (ready) onShare else onExport
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
        ClipData.newPlainText("MiBackscreen log", text)
    )
}

/**
 * 主题色文字链接。不用 clickable 默认的按压高亮：它是个直角灰框，在圆角面板里很突兀；按下时只让文字变淡。
 */
@Composable
private fun PressFadeLink(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.5f else 1f,
        animationSpec = tween(if (pressed) 0 else 150),
        label = "pressFadeLink",
    )
    Text(
        text = text,
        modifier = Modifier
            .graphicsLayer { this.alpha = alpha }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .then(modifier),
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.primary,
    )
}
