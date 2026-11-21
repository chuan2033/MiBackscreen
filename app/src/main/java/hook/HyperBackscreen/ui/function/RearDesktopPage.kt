package hook.HyperBackscreen.ui.function

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.bridge.PrefsBridge
import hook.HyperBackscreen.bridge.RearCastClient
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.common.RearDesktopPolicy
import hook.HyperBackscreen.ui.components.AppDropdownPreference
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.components.DetailPageScaffold
import hook.HyperBackscreen.ui.components.LocalUiFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 「背屏桌面」页：背屏密度档位与使用说明。应用从背屏应用卡打开的快捷面板里启动。
 */
@Composable
internal fun RearDesktopPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val feedback = LocalUiFeedback.current
    val scope = rememberCoroutineScope()
    var density by remember { mutableIntStateOf(PrefsBridge.readRearDesktopDensityForUi(context)) }

    DetailPageScaffold(
        title = stringResource(R.string.rear_desktop_title),
        onBack = onBack,
        topSpacing = 0.dp,
    ) {
        item(key = "density") {
            CardBlock(pressFeedbackType = PressFeedbackType.None) {
                val labels = RearDesktopPolicy.DENSITY_OPTIONS.map { densityLabel(context, it) }
                AppDropdownPreference(
                    items = labels,
                    selectedIndex = RearDesktopPolicy.DENSITY_OPTIONS.indexOf(density).coerceAtLeast(0),
                    title = stringResource(R.string.rear_desktop_density_title),
                    summary = stringResource(R.string.rear_desktop_density_summary),
                    onSelectedIndexChange = { index ->
                        val value = RearDesktopPolicy.DENSITY_OPTIONS.getOrNull(index) ?: return@AppDropdownPreference
                        density = value
                        scope.launch {
                            val synced = withContext(Dispatchers.IO) {
                                PrefsBridge.writeRearDesktopDensityFromUi(context, value)
                            }
                            if (synced) {
                                refreshSessionDensity(context)
                            } else {
                                feedback?.show(resources.getString(R.string.rear_desktop_density_pending))
                            }
                        }
                    }
                )
            }
        }
        item(key = "notes") {
            SmallTitle(
                text = stringResource(R.string.rear_cast_section_notes),
                insideMargin = PaddingValues(16.dp, 8.dp)
            )
            CardBlock(pressFeedbackType = PressFeedbackType.None) {
                BasicComponent(summary = stringResource(R.string.rear_desktop_note_entry))
                BasicComponent(summary = stringResource(R.string.rear_desktop_note_reboot))
                BasicComponent(summary = stringResource(R.string.rear_desktop_note_locked))
                BasicComponent(summary = stringResource(R.string.rear_desktop_note_compat))
                BasicComponent(summary = stringResource(R.string.rear_desktop_note_timeout))
            }
        }
    }
}

/** 档位已同步到远程偏好：请 system_server 为正在显示的会话重新读取。没有会话时系统回报 NO_SESSION。 */
private fun refreshSessionDensity(context: Context) {
    RearCastClient.send(context.applicationContext, Constants.REAR_CAST_OP_DENSITY_REFRESH, Bundle(),
        RearCastClient.ResultCallback { outcome, _, _ ->
            Log.d(Constants.LOG_TAG, "Rear desktop density refresh -> $outcome")
        })
}

private fun densityLabel(context: Context, density: Int): String =
    if (density == RearDesktopPolicy.DENSITY_UNCHANGED) {
        context.getString(R.string.rear_desktop_density_unchanged)
    } else {
        context.getString(R.string.rear_desktop_density_value, density)
    }
