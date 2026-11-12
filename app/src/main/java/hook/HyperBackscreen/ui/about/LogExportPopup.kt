package hook.HyperBackscreen.ui.about

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import hook.HyperBackscreen.ui.components.DialogButtonRow
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Close

/** Place inside the same Box as the log row so the popup is anchored to that entry. */
@Composable
internal fun LogExportPopup(
    show: Boolean,
    anchorBounds: IntRect,
    state: LogExportState,
    shareFailed: Boolean,
    fileMissing: Boolean,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
) {
    LogExportPopupHost(
        show = show,
        anchorBounds = anchorBounds,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                // 生成中 → 完成时会多出文件信息和按钮：面板贴着「日志」行往上长，平滑过渡，不要一下跳高。
                // 裁到动画中的尺寸：否则新内容按最终高度先画出来，按钮会伸出面板底边。
                .clipToBounds()
                .animateContentSize(spring(dampingRatio = 0.9f, stiffness = 500f))
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val running = state is LogExportState.Running
            // 标题直接说当前状态，正文不再重复一遍。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(when (state) {
                        is LogExportState.Running -> R.string.log_title_running
                        is LogExportState.Ready -> R.string.log_export_complete
                        LogExportState.Failed -> R.string.log_failed
                        LogExportState.Idle -> R.string.home_log_title
                    }),
                    style = MiuixTheme.textStyles.title4,
                    modifier = Modifier.weight(1f).semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                // × 只表示关闭弹窗：生成中关掉也会在后台继续，进度看「日志」行；取消用底部按钮。
                // 关闭按钮保持 48dp 点击区域，图标使用 20dp 尺寸。不用玻璃圆钮：它会采样面板下面的页面，
                // 在玻璃面板上看起来像挖了个洞。
                IconButton(
                    onClick = onDismiss,
                    minWidth = 48.dp,
                    minHeight = 48.dp,
                    modifier = Modifier.offset(x = 12.dp),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Glass.Close,
                        contentDescription = stringResource(R.string.log_close),
                        modifier = Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
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
            // 导出的包会同时往手机存储根目录放一份：写出文件名和所在目录，方便在文件管理器里直接找到。
            val savedFile = (state as? LogExportState.Ready)?.sharedPath?.takeIf { !fileMissing }
            val savedDirectory = savedFile?.parent
            if (savedFile != null && savedDirectory != null) {
                val context = LocalContext.current
                var copied by remember(savedFile) { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                    Text(
                        text = stringResource(if (copied) R.string.log_path_copied else R.string.log_copy_path),
                        modifier = Modifier
                            .clickable(role = Role.Button) {
                                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                    ClipData.newPlainText("MiBackscreen log", savedFile.absolutePath)
                                )
                                copied = true
                            }
                            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
            if (running) {
                GlassDialogButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text(stringResource(R.string.common_cancel), style = MiuixTheme.textStyles.button) }
            } else {
                val ready = state is LogExportState.Ready && !fileMissing
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
