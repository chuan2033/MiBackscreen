package hook.HyperBackscreen.ui.function

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.components.DetailPageScaffold
import hook.HyperBackscreen.ui.components.LocalUiFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** 管理背屏中心的通知监听授权，并提供短时实时通知演示。 */
@Composable
internal fun RearLiveUpdatePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val feedback = LocalUiFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var available by remember { mutableStateOf<Boolean?>(null) }
    var granted by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val state = withContext(Dispatchers.IO) {
            RearLiveUpdateAccess.isAvailable(context) to RearLiveUpdateAccess.isGranted(context)
        }
        available = state.first
        granted = state.second
    }

    LaunchedEffect(context) { refresh() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { refresh() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun postDemo() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { RearLiveUpdateDemo.post(context) }
            feedback?.show(
                resources.getString(
                    when (result) {
                        RearLiveUpdateDemo.Result.POSTED -> R.string.rear_live_update_demo_posted
                        RearLiveUpdateDemo.Result.NOTIFICATIONS_DISABLED -> R.string.rear_live_update_demo_permission
                        RearLiveUpdateDemo.Result.PROMOTION_DISABLED -> R.string.rear_live_update_demo_promotion
                        RearLiveUpdateDemo.Result.INVALID -> R.string.rear_live_update_demo_invalid
                        RearLiveUpdateDemo.Result.FAILED -> R.string.rear_live_update_demo_failed
                    }
                )
            )
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) postDemo() else feedback?.show(resources.getString(R.string.rear_live_update_demo_permission))
    }

    DetailPageScaffold(
        title = stringResource(R.string.rear_live_update_title),
        onBack = onBack,
    ) {
        item(key = "access") {
            CardBlock(pressFeedbackType = PressFeedbackType.None) {
                SwitchPreference(
                    checked = granted,
                    onCheckedChange = { requested ->
                        if (!busy) {
                            busy = true
                            scope.launch {
                                val success = withContext(Dispatchers.IO) {
                                    val commandSucceeded = RearLiveUpdateAccess.setGranted(requested)
                                    delay(400)
                                    commandSucceeded && RearLiveUpdateAccess.isGranted(context) == requested
                                }
                                refresh()
                                busy = false
                                feedback?.show(
                                    resources.getString(
                                        if (success) R.string.rear_live_update_access_saved
                                        else R.string.rear_live_update_access_failed
                                    )
                                )
                            }
                        }
                    },
                    title = stringResource(R.string.rear_live_update_access_title),
                    summary = stringResource(
                        when (available) {
                            null -> R.string.rear_live_update_access_checking
                            false -> R.string.rear_live_update_access_unavailable
                            true -> R.string.rear_live_update_access_summary
                        }
                    ),
                    enabled = available == true && !busy,
                )
            }
        }
        item(key = "demo") {
            CardBlock(pressFeedbackType = PressFeedbackType.None) {
                BasicComponent(
                    title = stringResource(R.string.rear_live_update_demo_title),
                    summary = stringResource(R.string.rear_live_update_demo_summary),
                    bottomAction = {
                        Button(
                            onClick = {
                                if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    postDemo()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
                            enabled = available == true && granted && !busy,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text(stringResource(R.string.rear_live_update_demo_send))
                        }
                    },
                )
            }
        }
        item(key = "hint") {
            Text(
                text = stringResource(R.string.rear_live_update_hint),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
