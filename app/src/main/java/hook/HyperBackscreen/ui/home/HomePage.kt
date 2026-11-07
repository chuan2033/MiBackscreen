package hook.HyperBackscreen.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import hook.HyperBackscreen.ui.components.DialogButtonRow
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.roundToInt
import androidx.lifecycle.viewmodel.compose.viewModel
import hook.HyperBackscreen.ui.about.LogExportState
import hook.HyperBackscreen.ui.about.LogExportViewModel
import hook.HyperBackscreen.ui.about.LocalLogExportOperation
import hook.HyperBackscreen.ui.about.LogExportPopup
import androidx.compose.runtime.Composable
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
import hook.HyperBackscreen.ui.util.RootAccess
import hook.HyperBackscreen.ui.theme.HomeUiTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import hook.HyperBackscreen.app.ModuleApp
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun HomePage(
    moduleActivated: Boolean,
    onDonateClick: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var rootGranted by remember { mutableStateOf<Boolean?>(null) }
    // 「重新检测」只是让这个 key 变一下，复用同一段回到前台时的检测逻辑。
    var rootCheckRequest by remember { mutableStateOf(0) }
    var rootChecking by remember { mutableStateOf(false) }
    LaunchedEffect(lifecycleOwner, rootCheckRequest) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            rootChecking = true
            try {
                rootGranted = runInterruptible(Dispatchers.IO) { RootAccess.isGranted() }
            } finally {
                rootChecking = false
            }
        }
    }
    // 作用域注入状态：每次回到前台刷新（用户可能刚去 LSPosed 勾选或重启了作用域）。
    var scopeStatus by remember { mutableStateOf<List<ScopeStatusItem>?>(null) }
    var showScopeDialog by remember { mutableStateOf(false) }
    LaunchedEffect(lifecycleOwner, moduleActivated) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            scopeStatus = withContext(Dispatchers.IO) { readScopeStatus(ModuleApp.getService()) }
        }
    }
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

    if (rootGranted == false) {
        // errorContainer 底色：提醒但不刺眼；右侧给一个就地重试，授权完不用切页面再回来。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .background(
                    MiuixTheme.colorScheme.errorContainer,
                    RoundedCornerShape(HomeUiTokens.CardCornerRadius)
                )
                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.home_root_permission_required),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp),
                color = MiuixTheme.colorScheme.onErrorContainer,
                style = MiuixTheme.textStyles.body2
            )
            Text(
                text = stringResource(
                    if (rootChecking) R.string.home_root_checking else R.string.home_root_recheck
                ),
                modifier = Modifier
                    .clickable(enabled = !rootChecking) { rootCheckRequest++ }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                color = MiuixTheme.colorScheme.onErrorContainer,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium
            )
        }
    }

    HomeStatusCard(
        activated = moduleActivated,
        scopeStatus = scopeStatus,
        onClick = if (moduleActivated && scopeStatus != null) {
            { showScopeDialog = true }
        } else {
            null
        }
    )

    WindowDialog(
        show = showScopeDialog,
        title = stringResource(R.string.scope_status_title),
        summary = stringResource(R.string.scope_status_summary),
        onDismissRequest = { showScopeDialog = false }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            scopeStatus.orEmpty().forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(item.labelRes),
                        modifier = Modifier.weight(1f),
                        style = MiuixTheme.textStyles.body1
                    )
                    Text(
                        text = stringResource(item.descriptionRes),
                        color = when (item.state) {
                            ScopeState.FAILED, ScopeState.STALE -> MiuixTheme.colorScheme.error
                            ScopeState.ACTIVE -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                            else -> MiuixTheme.colorScheme.onSurface
                        },
                        style = MiuixTheme.textStyles.body2
                    )
                }
            }
            DialogButtonRow(
                modifier = Modifier.padding(top = 8.dp),
                primaryText = stringResource(R.string.common_confirm),
                onPrimary = { showScopeDialog = false }
            )
        }
    }

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
                onCancel = {
                    exporter.cancel()
                    showLogPopup = false
                },
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
