package hook.HyperBackscreen.ui.about

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.ui.components.BlurredBar
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.updater.UpdateChecker
import hook.HyperBackscreen.ui.updater.UpdateDialog
import hook.HyperBackscreen.ui.updater.UpdateInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private const val LAST_CHECKED_KEY = "last_update_check_at"

/**
 * 「应用更新」二级页：检查更新、自动检测开关、当前版本。
 *
 * 检查本身复用 [UpdateChecker]（带 10 分钟节流），检测到新版时弹 [UpdateDialog]。
 */
@Composable
internal fun AppUpdatePage(
    checkUpdates: Boolean,
    onCheckUpdatesChange: (Boolean) -> Unit,
    onVersionHistoryClick: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) {
        context.getSharedPreferences(Constants.PREF_GROUP, Context.MODE_PRIVATE)
    }
    var checking by remember { mutableStateOf(false) }
    var lastCheckedAt by remember {
        mutableStateOf(maxOf(prefs.getLong(LAST_CHECKED_KEY, 0L), UpdateChecker.lastCheckAtWallClock))
    }
    var pendingUpdate by remember { mutableStateOf(UpdateChecker.last()) }
    var downloadFailed by remember { mutableStateOf(false) }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    fun checkNow() {
        if (checking) return
        checking = true
        scope.launch {
            val update = UpdateChecker.check(force = true)
            checking = false
            pendingUpdate = update
            lastCheckedAt = maxOf(lastCheckedAt, UpdateChecker.lastCheckAtWallClock)
            prefs.edit().putLong(LAST_CHECKED_KEY, lastCheckedAt).apply()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = stringResource(R.string.about_update_title),
                    largeTitle = stringResource(R.string.about_update_title),
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
                item {
                    Spacer(Modifier.height(12.dp))
                }
                item {
                    UpdateStatusCard(
                        checking = checking,
                        hasUpdate = pendingUpdate != null,
                        lastCheckedAt = lastCheckedAt,
                        onCheck = ::checkNow
                    )
                }
                item {
                    SmallTitle(
                        text = stringResource(R.string.about_update_settings_title),
                        insideMargin = PaddingValues(16.dp, 8.dp)
                    )
                }
                item {
                    CardBlock(pressFeedbackType = PressFeedbackType.None) {
                        SwitchPreference(
                            checked = checkUpdates,
                            onCheckedChange = onCheckUpdatesChange,
                            title = stringResource(R.string.config_check_updates_title),
                            summary = if (checkUpdates) {
                                stringResource(R.string.config_check_updates_summary_on)
                            } else {
                                stringResource(R.string.config_check_updates_summary_off)
                            }
                        )
                    }
                }
                item {
                    SmallTitle(
                        text = stringResource(R.string.about_update_version_title),
                        insideMargin = PaddingValues(16.dp, 8.dp)
                    )
                }
                item {
                    CardBlock {
                        ArrowPreference(
                            title = stringResource(R.string.about_version_history),
                            onClick = onVersionHistoryClick
                        )
                    }
                }
                item {
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    UpdateDialog(
        update = pendingUpdate,
        downloadFailed = downloadFailed,
        onDownload = {
            pendingUpdate?.let {
                downloadFailed = !UpdateChecker.openDownload(context, it)
                if (!downloadFailed) pendingUpdate = null
            }
        },
        onDismiss = { pendingUpdate = null; downloadFailed = false }
    )
}

/** 状态卡片：当前检测状态、上次检测时间、手动检查按钮。 */
@Composable
private fun UpdateStatusCard(
    checking: Boolean,
    hasUpdate: Boolean,
    lastCheckedAt: Long,
    onCheck: () -> Unit
) {
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (checking) {
                    LoadingRing(
                        modifier = Modifier.size(20.dp),
                        color = MiuixTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = stringResource(
                        when {
                            checking -> R.string.about_update_status_checking
                            hasUpdate -> R.string.about_update_status_available
                            else -> R.string.about_update_status_latest
                        }
                    ),
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.title4
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (lastCheckedAt > 0L) {
                    stringResource(R.string.about_update_last_checked, formatTime(lastCheckedAt))
                } else {
                    stringResource(R.string.about_update_never_checked)
                },
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                style = MiuixTheme.textStyles.body2
            )
            Spacer(Modifier.height(16.dp))
            Button(
                modifier = Modifier.height(48.dp),
                onClick = onCheck,
                enabled = !checking,
                colors = ButtonDefaults.buttonColorsPrimary(),
                content = {
                    Text(stringResource(R.string.about_update_check_now), color = Color.White)
                }
            )
        }
    }
}

@Composable
private fun LoadingRing(modifier: Modifier = Modifier, color: Color) {
    val transition = rememberInfiniteTransition(label = "updateLoading")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "updateLoadingAngle"
    )
    Canvas(modifier = modifier.rotate(angle)) {
        val stroke = 2.5.dp.toPx()
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
