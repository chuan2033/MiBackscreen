package hook.HyperBackscreen.ui.recorder

import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.components.DetailPageScaffold
import hook.HyperBackscreen.ui.components.LocalUiFeedback
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 「背屏录屏」二级页：一个开始 / 停止按钮加录制计时。
 *
 * 录制状态在 [RearScreenRecorder] 单例里，这里只展示与发指令。离开本页、App 退到后台都会
 * 停止录制并保存；录制期间保持主屏常亮，免得主屏超时熄灭把 App 切到后台、录制被提前结束。
 */
@Composable
internal fun RearScreenRecordPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val feedback = LocalUiFeedback.current
    val view = LocalView.current
    val state by RearScreenRecorder.state.collectAsState()

    LaunchedEffect(Unit) {
        RearScreenRecorder.events.collect { event ->
            val message = when (event) {
                is RearRecorderEvent.Saved -> resources.getString(R.string.rear_record_saved, event.displayName)
                is RearRecorderEvent.Failed -> resources.getString(event.messageRes)
            }
            feedback?.show(message)
        }
    }

    // 录背屏时人通常会把手机翻过来操作背屏，主屏随之熄灭、App 退到后台，所以退后台不能停；
    // 只有离开这个页面才停。Activity 因切语言 / 深色模式重建时录制继续，新页面接着显示状态。
    DisposableEffect(Unit) {
        onDispose {
            var host: android.content.Context? = context
            while (host is android.content.ContextWrapper && host !is android.app.Activity) host = host.baseContext
            val recreating = (host as? android.app.Activity)?.isChangingConfigurations == true
            if (!recreating) RearScreenRecorder.stop(context)
        }
    }

    val active = state !is RearRecorderState.Idle
    DisposableEffect(view, active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state) {
        while (state is RearRecorderState.Recording) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }

    DetailPageScaffold(
        title = stringResource(R.string.rear_record_title),
        onBack = onBack,
    ) {
        item(key = "record_hint") {
            Text(
                text = stringResource(R.string.rear_record_hint),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        item(key = "record_control") {
            CardBlock(pressFeedbackType = PressFeedbackType.None) {
                val current = state
                val summary = when (current) {
                    RearRecorderState.Idle -> stringResource(R.string.rear_record_idle_summary)
                    RearRecorderState.Starting -> stringResource(R.string.rear_record_starting)
                    is RearRecorderState.Recording -> stringResource(
                        R.string.rear_record_recording,
                        formatDuration((now - current.startedAtElapsed).coerceAtLeast(0L) / 1000L),
                        formatDuration(RearScreenRecorder.MAX_DURATION_SECONDS)
                    )
                    RearRecorderState.Saving -> stringResource(R.string.rear_record_saving)
                }
                BasicComponent(
                    title = stringResource(
                        if (current is RearRecorderState.Recording) R.string.rear_record_state_recording
                        else R.string.rear_record_state_idle
                    ),
                    summary = summary,
                    bottomAction = {
                        val recording = current is RearRecorderState.Recording
                        Button(
                            onClick = {
                                if (recording) RearScreenRecorder.stop(context)
                                else RearScreenRecorder.start(context)
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
                            enabled = current == RearRecorderState.Idle || recording,
                            colors = if (recording) ButtonDefaults.buttonColors()
                            else ButtonDefaults.buttonColorsPrimary(),
                            content = {
                                Text(
                                    stringResource(
                                        if (recording) R.string.rear_record_stop else R.string.rear_record_start
                                    )
                                )
                            }
                        )
                    }
                )
            }
        }
    }
}

/** 秒数格式化成 mm:ss。 */
internal fun formatDuration(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0L)
    return "%02d:%02d".format(java.util.Locale.US, seconds / 60, seconds % 60)
}
