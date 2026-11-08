package hook.HyperBackscreen.ui.home

import hook.HyperBackscreen.ui.components.AppDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.layout.LayoutCoordinates
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
import hook.HyperBackscreen.ui.theme.currentUiStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import hook.HyperBackscreen.app.ModuleApp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun HomePage(
    moduleActivated: ActivationState,
    visible: Boolean = true,
    onDonateClick: () -> Unit
) {
    val context = LocalContext.current
    // 用 Activity 级生命周期：NavDisplay 给每个页面的 LocalLifecycleOwner 在被二级页覆盖时降到
    // STARTED、返回时又 RESUMED，按它跑会每次从二级页返回都重新起一个 su、再查一遍作用域。
    // 只在整个 App 回到前台时刷新（用户可能刚去授权 root、在 LSPosed 勾选或重启了作用域）。
    val pageLifecycleOwner = LocalLifecycleOwner.current
    val lifecycleOwner = remember(context, pageLifecycleOwner) {
        context.findLifecycleOwnerActivity() ?: pageLifecycleOwner
    }
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
    // 作用域注入状态：框架没有「目标进程变化」的回调，只能轮询。首页可见时每隔一会儿读一次，
    // 应用内重启作用域、宿主自己崩溃重启后圆点跟着变，不用切后台再回来。
    // 切到别的 Tab、被二级页盖住或 App 退到后台就停（这里用页面自己的生命周期，二级页会让它降到 STARTED）。
    var scopeStatus by remember { mutableStateOf<List<ScopeStatusItem>?>(null) }
    var showScopeDialog by remember { mutableStateOf(false) }
    LaunchedEffect(pageLifecycleOwner, moduleActivated, visible) {
        if (!visible) return@LaunchedEffect
        pageLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val service = ModuleApp.getService()
                val latest = withContext(Dispatchers.IO) { readScopeStatus(service) }
                // 偶发的读取失败不把圆点整排撤掉；服务真断开了才清空。
                val next = latest ?: scopeStatus.takeIf { service != null }
                if (next != scopeStatus) {
                    logScopeStatusChange(next)
                    scopeStatus = next
                }
                delay(ScopeStatusPollMillis)
            }
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
    // 只记下坐标对象、不写状态：滚动时 onGloballyPositioned 每帧都会回调，
    // 每帧写 IntRect 会让日志卡片和弹窗跟着每帧重组。打开弹窗时再换算成锚点区域。
    val logEntryCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    fun refreshLogAnchor() {
        val coordinates = logEntryCoordinates[0]?.takeIf { it.isAttached } ?: return
        val position = coordinates.positionInWindow()
        logEntryBounds = IntRect(IntOffset(position.x.roundToInt(), position.y.roundToInt()), coordinates.size)
    }
    fun startExport() {
        popupShareFailed = false
        popupFileMissing = false
        refreshLogAnchor()
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

    // Root 检测是异步的：提示条展开 + 淡入，不再在检测完成那一帧把整页猛地往下推。
    AnimatedVisibility(
        visible = rootGranted == false,
        enter = expandVertically(tween(260, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
            fadeIn(tween(260, easing = FastOutSlowInEasing)),
        exit = shrinkVertically(tween(180, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) +
            fadeOut(tween(180, easing = FastOutSlowInEasing))
    ) {
        // errorContainer 底色：提醒但不刺眼；右侧给一个就地重试，授权完不用切页面再回来。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .background(
                    MiuixTheme.colorScheme.errorContainer,
                    RoundedCornerShape(currentUiStyle.cardCorner)
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
        activation = moduleActivated,
        scopeStatus = scopeStatus,
        onClick = if (moduleActivated == ActivationState.ACTIVE && scopeStatus != null) {
            { showScopeDialog = true }
        } else {
            null
        }
    )

    AppDialog(
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

    // 设备信息只算一次：SystemProperties 走反射、包版本是 PackageManager Binder 调用，
    // 不放在组合阶段反复执行。rememberSaveable 让列表项滑出再滑回时直接复用。
    val deviceName = rememberSaveable { currentDeviceName() }
    val hyperOsVersion = rememberSaveable { currentHyperOSVersion() }
    val systemVersion = rememberSaveable { currentSystemVersion() }
    var themeStoreVersion by rememberSaveable { mutableStateOf<String?>(null) }
    var backscreenVersion by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (themeStoreVersion == null || backscreenVersion == null) {
            val appContext = context.applicationContext
            val (themeStore, backscreen) = withContext(Dispatchers.IO) {
                currentPackageVersion(appContext, Constants.THEME_STORE_PACKAGE) to
                    currentPackageVersion(appContext, Constants.TARGET_PACKAGE)
            }
            themeStoreVersion = themeStore
            backscreenVersion = backscreen
        }
    }
    CardBlock {
        InfoRow(label = stringResource(R.string.home_device), value = deviceName)
        InfoRow(label = stringResource(R.string.home_system_version), value = hyperOsVersion)
        InfoRow(label = stringResource(R.string.home_android_version), value = systemVersion)
        InfoRow(
            label = stringResource(R.string.home_theme_store_version),
            value = themeStoreVersion ?: "—"
        )
        InfoRow(
            label = stringResource(R.string.home_backscreen_version),
            value = backscreenVersion ?: "—"
        )
    }

    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        Box(Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
            logEntryCoordinates[0] = coordinates
            // 弹窗开着时才跟随位置；IntRect 相等时写状态不会触发重组。
            if (showLogPopup) refreshLogAnchor()
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
                    refreshLogAnchor()
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

/** 沿 ContextWrapper 链找到宿主 Activity，取它的生命周期（而不是导航页面级的）。 */
private fun android.content.Context.findLifecycleOwnerActivity(): androidx.lifecycle.LifecycleOwner? {
    var current: android.content.Context? = this
    while (current != null) {
        if (current is android.app.Activity) return current as? androidx.lifecycle.LifecycleOwner
        current = (current as? android.content.ContextWrapper)?.baseContext
    }
    return null
}
