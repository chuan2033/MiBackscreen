package hook.HyperBackscreen.ui.about

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
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
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
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = MiuixIcons.Basic.Close,
                        contentDescription = stringResource(R.string.log_close),
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state is LogExportState.Ready && !fileMissing) {
                        TextButton(
                            text = stringResource(R.string.log_regenerate),
                            modifier = Modifier.weight(1f),
                            onClick = onExport,
                        )
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        onClick = if (state is LogExportState.Ready && !fileMissing) onShare else onExport,
                    ) {
                        Text(stringResource(when {
                            fileMissing -> R.string.log_regenerate
                            shareFailed || state is LogExportState.Failed -> R.string.common_retry
                            state is LogExportState.Ready -> R.string.common_share
                            else -> R.string.log_export_start
                        }))
                    }
                }
            }
        }
    }
}
