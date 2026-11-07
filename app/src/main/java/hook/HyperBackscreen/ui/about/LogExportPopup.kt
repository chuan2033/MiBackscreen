package hook.HyperBackscreen.ui.about

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import hook.HyperBackscreen.ui.components.DialogButtonRow
import androidx.compose.foundation.clickable
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
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Close

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
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.home_log_title),
                    style = MiuixTheme.textStyles.title4,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                val exporting = state is LogExportState.Running
                // 视觉上仍是 20dp 的叉，点击区扩到 48dp（最小触控尺寸），用负外边距贴回右上角。
                IconButton(
                    onClick = { if (exporting) onCancel() else onDismiss() },
                    modifier = Modifier
                        .offset(x = 12.dp)
                        .size(48.dp)
                ) {
                    Icon(
                        imageVector = MiuixIcons.Basic.Close,
                        contentDescription = stringResource(
                            if (exporting) R.string.log_cancel_export else R.string.log_close
                        ),
                        modifier = Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Text(
                text = stringResource(when {
                    fileMissing -> R.string.log_file_missing
                    shareFailed -> R.string.log_share_failed
                    state is LogExportState.Running -> R.string.log_exporting
                    state is LogExportState.Ready -> R.string.log_export_complete
                    state is LogExportState.Failed -> R.string.log_failed
                    else -> R.string.log_summary
                }),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            // 导出的包会同时往手机存储根目录放一份，这里把落点写出来，省得用户去翻目录。
            val savedFile = (state as? LogExportState.Ready)?.sharedPath
            val savedDirectory = savedFile?.parent
            if (savedFile != null && savedDirectory != null) {
                val context = LocalContext.current
                var copied by remember(savedFile) { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.log_saved_to, savedDirectory),
                        modifier = Modifier.weight(1f),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
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
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
            if (state is LogExportState.Running || state is LogExportState.Ready) {
                val completed = (state as? LogExportState.Running)?.completed ?: 10
                val total = (state as? LogExportState.Running)?.total ?: 10
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.weight(1f),
                        progress = completed.toFloat() / total.coerceAtLeast(1),
                    )
                    Text(
                        text = stringResource(R.string.log_stage_count, completed, total),
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            if (state !is LogExportState.Running) {
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
