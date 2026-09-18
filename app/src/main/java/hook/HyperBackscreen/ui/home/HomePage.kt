package hook.HyperBackscreen.ui.home

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.ui.about.FeedbackLogExporter
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.components.InfoRow
import hook.HyperBackscreen.ui.util.currentDeviceName
import hook.HyperBackscreen.ui.util.currentHyperOSVersion
import hook.HyperBackscreen.ui.util.currentPackageVersion
import hook.HyperBackscreen.ui.util.currentSystemVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun HomePage(
    moduleActivated: Boolean,
    usesDynamicColors: Boolean,
    onDonateClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var generatingLog by remember { mutableStateOf(false) }

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
        ArrowPreference(
            title = stringResource(R.string.home_log_title),
            summary = stringResource(R.string.log_summary),
            onClick = {
                if (generatingLog) return@ArrowPreference
                generatingLog = true
                Toast.makeText(context, R.string.log_generating, Toast.LENGTH_SHORT).show()
                scope.launch {
                    val file = withContext(Dispatchers.IO) {
                        FeedbackLogExporter.create(context)
                    }
                    generatingLog = false
                    if (file != null) {
                        FeedbackLogExporter.share(context, file)
                    } else {
                        Toast.makeText(context, R.string.log_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        ArrowPreference(
            title = stringResource(R.string.about_donate_title),
            summary = stringResource(R.string.about_donate_qr_entry),
            onClick = onDonateClick
        )
    }
}
