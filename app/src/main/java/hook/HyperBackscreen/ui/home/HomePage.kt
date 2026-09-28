package hook.HyperBackscreen.ui.home

import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import kotlin.math.roundToInt
import androidx.lifecycle.viewmodel.compose.viewModel
import hook.HyperBackscreen.ui.about.LogExportState
import hook.HyperBackscreen.ui.about.LogExportViewModel
import hook.HyperBackscreen.ui.about.LocalLogExportOperation
import hook.HyperBackscreen.ui.about.LogExportPopup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.ui.about.LocalLogShareOperation
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.components.InfoRow
import hook.HyperBackscreen.ui.util.currentDeviceName
import hook.HyperBackscreen.ui.util.currentHyperOSVersion
import hook.HyperBackscreen.ui.util.currentPackageVersion
import hook.HyperBackscreen.ui.util.currentSystemVersion
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun HomePage(
    moduleActivated: Boolean,
    usesDynamicColors: Boolean,
    onDonateClick: () -> Unit
) {
    val context = LocalContext.current
    val operation = LocalLogExportOperation.current
    val factory = remember(context.applicationContext, operation) { LogExportViewModel.Factory(context, operation) }
    val exporter: LogExportViewModel = viewModel(factory = factory)
    val exportState by exporter.state.collectAsState()
    val currentContext by rememberUpdatedState(context)
    val shareOperation by rememberUpdatedState(LocalLogShareOperation.current)
    var showLogPopup by rememberSaveable { mutableStateOf(false) }
    var popupShareFailed by rememberSaveable { mutableStateOf(false) }
    var popupFileMissing by rememberSaveable { mutableStateOf(false) }
    var logEntryBounds by remember { mutableStateOf(IntRect.Zero) }
    fun startExport() {
        popupShareFailed = false
        popupFileMissing = false
        showLogPopup = true
        exporter.start()
    }
    fun share(file: java.io.File) {
        if (!file.isFile || file.length() == 0L) {
            popupFileMissing = true
            return
        }
        try {
            shareOperation.share(currentContext, file)
            popupShareFailed = false
        } catch (_: Exception) {
            popupShareFailed = true
        }
    }

    HomeStatusCard(
        activated = moduleActivated,
        usesDynamicColors = usesDynamicColors
    )

    CardBlock {
        InfoRow(label = stringResource(R.string.home_device), value = currentDeviceName())
        InfoRow(label = stringResource(R.string.home_system_version), value = currentHyperOSVersion())
        InfoRow(label = stringResource(R.string.home_android_version), value = currentSystemVersion())
        InfoRow(
            label = stringResource(R.string.home_theme_store_version),
            value = currentPackageVersion(context, Constants.THEME_STORE_PACKAGE)
        )
        InfoRow(
            label = stringResource(R.string.home_backscreen_version),
            value = currentPackageVersion(context, Constants.TARGET_PACKAGE)
        )
    }

    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        Box(Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
            val position = coordinates.positionInWindow()
            logEntryBounds = IntRect(IntOffset(position.x.roundToInt(), position.y.roundToInt()), coordinates.size)
        }) {
            ArrowPreference(
                title = stringResource(R.string.home_log_title),
                summary = when (val state = exportState) {
                    LogExportState.Idle -> stringResource(R.string.log_summary)
                    is LogExportState.Running -> stringResource(R.string.log_progress, state.completed, state.total)
                    is LogExportState.Ready -> stringResource(R.string.log_export_view)
                    LogExportState.Failed -> stringResource(R.string.log_failed_retry)
                },
                onClick = {
                    showLogPopup = true
                    if (exportState is LogExportState.Idle || exportState is LogExportState.Failed) {
                        startExport()
                    }
                }
            )
            LogExportPopup(
                show = showLogPopup,
                anchorBounds = logEntryBounds,
                state = exportState,
                shareFailed = popupShareFailed,
                fileMissing = popupFileMissing,
                onDismiss = { showLogPopup = false },
                onExport = { startExport() },
                onShare = { (exportState as? LogExportState.Ready)?.let { share(it.file) } },
            )
        }
    }

    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        ArrowPreference(
            title = stringResource(R.string.about_donate_title),
            summary = stringResource(R.string.about_donate_qr_entry),
            onClick = onDonateClick
        )
    }
}
