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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.bridge.PrefsBridge
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.common.PickupCodes
import hook.HyperBackscreen.ui.components.BlurredBar
import hook.HyperBackscreen.ui.util.ThemePrefs
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.theme.platformDynamicColors
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import androidx.compose.ui.state.ToggleableState

private data class PickupGroup(val station: String, val codes: List<String>) {
    val identity: String get() = PickupCodes.displayIdentity(codes, station)
}

private data class PickupPayload(val groups: List<PickupGroup> = emptyList()) {
    val codeCount: Int get() = groups.sumOf { it.codes.size }
}

class PickupCodesActivity : ComponentActivity() {
    private var payload by mutableStateOf(PickupPayload())
    private var sessionId = ""
    private var pageToken = ""
    private var confirmedReceiver: android.content.BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readPayload(intent)
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
    val initialSelections = remember(payload) {
        val storedSelection = PrefsBridge.readPickupIslandSelectionForUi(context)
        payload.groups.associate { group ->
            group.identity to initialVisibleCodes(group, group.identity, storedSelection)
        }
    }
    var selectedByGroup by remember(payload) { mutableStateOf(initialSelections) }
    val allSelected = payload.codeCount > 0 && payload.groups.all { group ->
        selectedByGroup[group.identity].orEmpty().containsAll(group.codes)
    }

    fun applySelections(nextSelections: Map<String, Set<String>>) {
        var updatedStored = PrefsBridge.readPickupIslandSelectionForUi(context)
        payload.groups.forEach { group ->
            val selected = nextSelections[group.identity].orEmpty()
            updatedStored = PickupCodes.upsertIslandSelection(
                updatedStored,
                group.identity,
                group.codes.filter(selected::contains),
                selected.size >= group.codes.size
            )
        }
        selectedByGroup = nextSelections
        PrefsBridge.writePickupIslandSelectionFromUi(context, updatedStored)
        requestIslandRefresh(context)
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
                payload.groups.forEachIndexed { index, group ->
                    item(key = group.identity) {
                        PickupGroupBlock(
                            group = group,
                            visibleCodes = selectedByGroup[group.identity].orEmpty(),
                            showTopSpacing = index > 0,
                            showSelectAll = index == 0 && payload.codeCount > 0,
                            allSelected = allSelected,
                            onSelectAll = { setAllSelected(!allSelected) },
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
    visibleCodes: Set<String>,
    showTopSpacing: Boolean,
    showSelectAll: Boolean,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onVisibleCodesChange: (Set<String>) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (showTopSpacing) Modifier.padding(top = 20.dp) else Modifier)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (group.station.isNotEmpty()) {
                        Text(
                            group.station,
                            color = MiuixTheme.colorScheme.onSurface,
                            style = MiuixTheme.textStyles.body1
                        )
                    }
                    Text(
                        pluralStringResource(R.plurals.pickup_count, group.codes.size, group.codes.size),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2
                    )
                }
                if (showSelectAll) {
                    TextButton(
                        text = stringResource(
                            if (allSelected) R.string.pickup_deselect_all
                            else R.string.pickup_select_all
                        ),
                        onClick = onSelectAll
                    )
                }
            }
        }
        group.codes.forEachIndexed { index, code ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .clickable {
                        onVisibleCodesChange(
                            if (visibleCodes.contains(code)) visibleCodes - code
                            else visibleCodes + code
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val checked = visibleCodes.contains(code)
                Text(
                    (index + 1).toString(),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2
                )
                Text(
                    code,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.title2.copy(fontFamily = FontFamily.Monospace)
                )
                Checkbox(
                    state = if (checked) ToggleableState.On else ToggleableState.Off,
                    onClick = {
                        onVisibleCodesChange(if (checked) visibleCodes - code else visibleCodes + code)
                    }
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
}

private fun initialVisibleCodes(
    group: PickupGroup,
    stationIdentity: String,
    stored: String
): Set<String> {
    val stationSelection = PickupCodes.selectionForIdentity(stored, stationIdentity)
    if (stationSelection.isNotEmpty()) {
        return PickupCodes.applyIslandSelection(group.codes, stationIdentity, stationSelection).toSet()
    }
    // 兼容旧版按完整码串保存的记录。
    val legacyIdentity = PickupCodes.identity(group.codes, group.station)
    return PickupCodes.applyIslandSelection(
        group.codes,
        legacyIdentity,
        PickupCodes.selectionForIdentity(stored, legacyIdentity)
    ).toSet()
}

private fun requestIslandRefresh(context: Context) {
    try {
        context.sendBroadcast(
            Intent(Constants.ACTION_REFRESH_PICKUP_ISLAND).setPackage(Constants.VOICE_ASSIST_PACKAGE)
        )
    } catch (_: RuntimeException) {
    }
}
