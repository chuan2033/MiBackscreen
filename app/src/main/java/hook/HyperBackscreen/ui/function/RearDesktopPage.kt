package hook.HyperBackscreen.ui.function

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import hook.HyperBackscreen.R
import hook.HyperBackscreen.bridge.PrefsBridge
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.common.RearDesktopPolicy
import hook.HyperBackscreen.desktop.RearDesktopController
import hook.HyperBackscreen.desktop.RearDesktopState
import hook.HyperBackscreen.ui.components.AppDropdownPreference
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.components.DetailPageScaffold
import hook.HyperBackscreen.ui.components.LocalUiFeedback
import hook.HyperBackscreen.ui.recorder.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 「背屏桌面」页：在背屏打开 / 退出背屏桌面、背屏密度档位、浮动控制条需要的悬浮窗权限，以及使用说明。
 */
@Composable
internal fun RearDesktopPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val feedback = LocalUiFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val running by RearDesktopState.running.collectAsState()
    var density by remember { mutableIntStateOf(PrefsBridge.readRearDesktopDensity(context)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) overlayGranted = Settings.canDrawOverlays(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DetailPageScaffold(
        title = stringResource(R.string.rear_desktop_title),
        onBack = onBack,
        topSpacing = 0.dp,
    ) {
        item(key = "control") {
            CardBlock(pressFeedbackType = PressFeedbackType.None) {
                ArrowPreference(
                    title = stringResource(if (running) R.string.rear_desktop_exit else R.string.rear_desktop_open),
                    summary = stringResource(if (running) R.string.rear_desktop_running else R.string.rear_desktop_open_summary),
                    onClick = {
                        if (running) RearDesktopController.exit(context) else RearDesktopController.open(context)
                    }
                )
                val labels = RearDesktopPolicy.DENSITY_OPTIONS.map { densityLabel(context, it) }
                AppDropdownPreference(
                    items = labels,
                    selectedIndex = RearDesktopPolicy.DENSITY_OPTIONS.indexOf(density).coerceAtLeast(0),
                    title = stringResource(R.string.rear_desktop_density_title),
                    summary = stringResource(R.string.rear_desktop_density_summary),
                    onSelectedIndexChange = { index ->
                        val value = RearDesktopPolicy.DENSITY_OPTIONS.getOrNull(index) ?: return@AppDropdownPreference
                        density = value
                        PrefsBridge.writeRearDesktopDensity(context, value)
                        RearDesktopController.refreshDensity(context)
                    }
                )
                SwitchPreference(
                    checked = overlayGranted,
                    onCheckedChange = { requested ->
                        if (!requested) {
                            openOverlaySettings(context)
                            return@SwitchPreference
                        }
                        scope.launch {
                            val granted = withContext(Dispatchers.IO) {
                                RootShell.run(
                                    "appops set ${Constants.MODULE_PACKAGE} SYSTEM_ALERT_WINDOW allow\n",
                                    timeoutSeconds = 15,
                                    maxStdoutBytes = 4 * 1024
                                )?.exitCode == 0
                            }
                            overlayGranted = Settings.canDrawOverlays(context)
                            if (!granted || !overlayGranted) {
                                feedback?.show(resources.getString(R.string.rear_desktop_overlay_manual))
                                openOverlaySettings(context)
                            }
                        }
                    },
                    title = stringResource(R.string.rear_desktop_overlay_title),
                    summary = stringResource(R.string.rear_desktop_overlay_summary)
                )
            }
        }
        item(key = "notes") {
            SmallTitle(
                text = stringResource(R.string.rear_cast_section_notes),
                insideMargin = PaddingValues(16.dp, 8.dp)
            )
            CardBlock(pressFeedbackType = PressFeedbackType.None) {
                BasicComponent(summary = stringResource(R.string.rear_desktop_note_reboot))
                BasicComponent(summary = stringResource(R.string.rear_desktop_note_locked))
                BasicComponent(summary = stringResource(R.string.rear_desktop_note_tile))
                BasicComponent(summary = stringResource(R.string.rear_desktop_note_timeout))
                BasicComponent(summary = stringResource(R.string.rear_desktop_note_compat))
            }
        }
    }
}

private fun densityLabel(context: Context, density: Int): String =
    if (density == RearDesktopPolicy.DENSITY_UNCHANGED) {
        context.getString(R.string.rear_desktop_density_unchanged)
    } else {
        context.getString(R.string.rear_desktop_density_value, density)
    }

private fun openOverlaySettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
    }
}
