package hook.HyperBackscreen.ui.pickup

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import hook.HyperBackscreen.ui.components.rememberUiFeedback
import top.yukonga.miuix.kmp.basic.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.bridge.PrefsBridge
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.common.PickupCodes
import hook.HyperBackscreen.ui.components.BlurredBar
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.util.ThemePrefs
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.theme.platformDynamicColors
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

class PickupCodesActivity : ComponentActivity() {
    private var payload by mutableStateOf(PickupPayload())
    private var sessionId = ""
    private var pageToken = ""
    private var confirmedReceiver: android.content.BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readPayload(intent)
        if (savedInstanceState != null) {
            val groups = savedInstanceState.getBundle("pickup_groups")
            if (groups != null) {
                payload = PickupPayload((0 until groups.getInt("count")).map { index ->
                    PickupGroup(groups.getString("station_$index").orEmpty(),
                        groups.getStringArrayList("codes_$index").orEmpty())
                })
                sessionId = savedInstanceState.getString("pickup_session").orEmpty()
                pageToken = savedInstanceState.getString("pickup_page_token").orEmpty()
            }
        }
        registerConfirmedReceiver()
        setContent {
            val themeMode = remember { ThemePrefs.getThemeMode(this) }
            val dark = themeMode.resolve(isSystemInDarkTheme())
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT) { dark }
                )
                window.isNavigationBarContrastEnforced = false
                onDispose {}
            }
            val colors = if (themeMode.usesDynamicColors) platformDynamicColors(dark)
                else if (dark) darkColorScheme() else lightColorScheme()
            MiuixTheme(colors = colors) {
                PickupCodesPage(payload, onBack = { finish() })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        notifyPageOpened()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("pickup_groups", Bundle().apply {
            putInt("count", payload.groups.size)
            payload.groups.forEachIndexed { index, group ->
                putString("station_$index", group.station)
                putStringArrayList("codes_$index", ArrayList(group.codes))
            }
        })
        outState.putString("pickup_session", sessionId)
        outState.putString("pickup_page_token", pageToken)
        super.onSaveInstanceState(outState)
    }

    private fun registerConfirmedReceiver() {
        if (confirmedReceiver != null) return
        confirmedReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == PickupCodes.ACTION_CONFIRMED
                    && pageToken.isNotEmpty()
                    && intent.getStringExtra(PickupCodes.EXTRA_TOKEN) == pageToken
                ) {
                    val identity = intent.getStringExtra(PickupCodes.EXTRA_IDENTITY).orEmpty()
                    if (payload.groups.none { it.identity == identity }) return
                    try {
                        val stored = PrefsBridge.readPickupIslandSelectionForUi(context)
                        val updated = PickupCodes.upsertIslandSelection(
                            stored,
                            identity,
                            emptyList(),
                            true
                        )
                        PrefsBridge.writePickupIslandSelectionFromUi(context, updated)
                    } catch (error: RuntimeException) {
                        android.util.Log.w(Constants.LOG_TAG, "Unable to clear confirmed pickup selection", error)
                    } finally {
                        finish()
                    }
                }
            }
        }
        registerReceiver(
            confirmedReceiver,
            android.content.IntentFilter(PickupCodes.ACTION_CONFIRMED),
            Context.RECEIVER_EXPORTED
        )
    }

    override fun onDestroy() {
        confirmedReceiver?.let {
            unregisterReceiver(it)
            confirmedReceiver = null
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mergePayload(intent)
        notifyPageOpened()
    }

    private fun readPayload(intent: Intent?) {
        sessionId = intent?.getStringExtra(PickupCodes.EXTRA_SESSION).orEmpty()
        payload = parsePayload(intent)
        pageToken = if (payload.groups.isEmpty()) ""
        else intent?.getStringExtra(PickupCodes.EXTRA_TOKEN).orEmpty()
    }

    private fun mergePayload(intent: Intent?) {
        val incoming = parsePayload(intent)
        val incomingGroup = incoming.groups.singleOrNull() ?: return
        val incomingSession = intent?.getStringExtra(PickupCodes.EXTRA_SESSION).orEmpty()
        pageToken = intent?.getStringExtra(PickupCodes.EXTRA_TOKEN).orEmpty()
        if (payload.groups.isNotEmpty() && incomingSession.isNotEmpty()
            && sessionId.isNotEmpty() && incomingSession != sessionId
        ) {
            // 不同一批记忆岛代表新的识别结果，不能把上一批历史码带进来。
            sessionId = incomingSession
            payload = incoming
            pageToken = intent?.getStringExtra(PickupCodes.EXTRA_TOKEN).orEmpty()
            return
        }
        if (sessionId.isEmpty() && incomingSession.isNotEmpty()) sessionId = incomingSession
        val existingIndex = payload.groups.indexOfFirst { it.identity == incomingGroup.identity }
        val nextGroups = payload.groups.toMutableList()
        if (existingIndex >= 0) {
            val existing = nextGroups[existingIndex]
            nextGroups[existingIndex] = existing.copy(
                codes = PickupCodes.mergeDistinct(existing.codes, incomingGroup.codes)
            )
        } else {
            nextGroups += incomingGroup
        }
        payload = PickupPayload(nextGroups)
    }

    private fun parsePayload(intent: Intent?): PickupPayload {
        return try {
            if (intent?.action != PickupCodes.ACTION_VIEW) PickupPayload()
            else PickupCodes.parse(intent.getStringExtra(PickupCodes.EXTRA_CODES)).let { codes ->
                if (codes.isEmpty()) PickupPayload()
                else {
                    val station = PickupCodes.station(intent.getStringExtra(PickupCodes.EXTRA_STATION))
                    // PendingIntent 已经携带本次识别结果；令牌只用于确认取件后的页面关联。
                    // 旧灵动岛卡片可能保留旧令牌，不能因此丢弃仍然有效的取件码。
                    PickupPayload(
                    groups = listOf(
                        PickupGroup(
                            station = station,
                            codes = codes
                        )
                    )
                    )
                }
            }
        } catch (_: RuntimeException) {
            PickupPayload()
        }
    }

    private fun notifyPageOpened() {
        if (pageToken.isEmpty()) return
        sendBroadcast(
            Intent(PickupCodes.ACTION_PAGE_OPENED)
                .setPackage(Constants.VOICE_ASSIST_PACKAGE)
                .putExtra(PickupCodes.EXTRA_TOKEN, pageToken)
        )
    }
}

@Composable
private fun PickupCodesPage(payload: PickupPayload, onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val surfaceColor = MiuixTheme.colorScheme.surface
    val factory = remember(context.applicationContext) { PickupSelectionViewModel.Factory(context) }
    val selectionModel: PickupSelectionViewModel = viewModel(factory = factory)
    val selectionState by selectionModel.state.collectAsState()
    LaunchedEffect(payload) { selectionModel.bind(payload) }
    val selectedByGroup = selectionState.selections
    val saving = selectionState.busy || selectionState.payload != payload
    val allSelected = payload.codeCount > 0 && payload.groups.all { group ->
        selectedByGroup[group.identity].orEmpty().containsAll(group.codes)
    }

    val feedback = rememberUiFeedback()
    val retryLabel = stringResource(R.string.common_retry)
    val saveFailed = stringResource(R.string.settings_save_failed)
    val pendingMessage = stringResource(R.string.pickup_saved_pending)
    val refreshFailed = stringResource(R.string.pickup_refresh_failed)

    fun applySelections(nextSelections: Map<String, Set<String>>) {
        selectionModel.save(payload, nextSelections)
    }
    LaunchedEffect(payload, selectionState.busy, selectionState.result, selectionState.loadFailed) {
        feedback.dismiss()
        if (selectionState.payload == payload && !saving) {
            when {
                selectionState.loadFailed || selectionState.result == PickupSaveResult.Failed ->
                    feedback.show(saveFailed, retryLabel) { selectionModel.retry(payload) }
                selectionState.result == PickupSaveResult.Pending -> feedback.show(pendingMessage)
                selectionState.result == PickupSaveResult.RefreshFailed ->
                    feedback.show(refreshFailed, retryLabel) { selectionModel.retry(payload) }
            }
        }
    }

    fun setAllSelected(checked: Boolean) {
        applySelections(payload.groups.associate { group ->
            group.identity to if (checked) group.codes.toSet() else emptySet()
        })
    }

    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    BackHandler(onBack = onBack)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(state = feedback.host) },
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = stringResource(R.string.pickup_title),
                    largeTitle = stringResource(R.string.pickup_title),
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
                    },
                    actions = {
                        if (payload.codeCount > 0) {
                            TextButton(
                                enabled = !saving && !selectionState.loadFailed,
                                text = if (saving) stringResource(R.string.settings_saving) else stringResource(
                                    if (allSelected) R.string.pickup_deselect_all
                                    else R.string.pickup_select_all
                                ),
                                onClick = { setAllSelected(!allSelected) }
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
                item(key = "top_spacer") {
                    Spacer(Modifier.height(12.dp))
                }
                if (payload.codeCount == 0) {
                    item {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                stringResource(R.string.pickup_empty),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.body2
                            )
                        }
                    }
                }
                payload.groups.forEach { group ->
                    item(key = group.identity) {
                        PickupGroupBlock(
                            group = group,
                            enabled = !saving && !selectionState.loadFailed,
                            visibleCodes = selectedByGroup[group.identity].orEmpty(),
                            onVisibleCodesChange = { next ->
                                applySelections(selectedByGroup + (group.identity to next))
                            }
                        )
                    }
                }
                item {
                    Spacer(Modifier.height(24.dp).navigationBarsPadding())
                }
            }
        }
    }
}

@Composable
private fun PickupGroupBlock(
    group: PickupGroup,
    enabled: Boolean,
    visibleCodes: Set<String>,
    onVisibleCodesChange: (Set<String>) -> Unit
) {
    val groupTitle = if (group.station.isEmpty()) stringResource(R.string.pickup_title) else group.station
    Column(modifier = Modifier.fillMaxWidth()) {
        SmallTitle(
            text = groupTitle,
            insideMargin = PaddingValues(16.dp, 8.dp)
        )
        Text(
            pluralStringResource(R.plurals.pickup_count, group.codes.size, group.codes.size),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2
        )
        CardBlock(pressFeedbackType = PressFeedbackType.None) {
            group.codes.forEach { code ->
                val checked = visibleCodes.contains(code)
                CheckboxPreference(
                    title = code,
                    enabled = enabled,
                    checked = checked,
                    checkboxLocation = CheckboxLocation.End,
                    onCheckedChange = { next ->
                        onVisibleCodesChange(if (next) visibleCodes + code else visibleCodes - code)
                    }
                )
            }
        }
    }
}
