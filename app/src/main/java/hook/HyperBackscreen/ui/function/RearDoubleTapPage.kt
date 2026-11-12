package hook.HyperBackscreen.ui.function

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.PackageListCodec
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.components.DetailPageScaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** 按应用设置背屏双击亮屏与熄屏。 */
@Composable
internal fun RearDoubleTapPage(
    doubleTapWakeDisabledPackages: String,
    doubleTapSleepDisabledPackages: String,
    onWakeAppsClick: () -> Unit,
    onSleepAppsClick: () -> Unit,
    onBack: () -> Unit,
) {
    val wakeCount = PackageListCodec.parse(doubleTapWakeDisabledPackages).size
    val sleepCount = PackageListCodec.parse(doubleTapSleepDisabledPackages).size
    DetailPageScaffold(
        title = stringResource(R.string.rear_double_tap_title),
        onBack = onBack,
        topSpacing = 0.dp,
    ) {
        item(key = "wake") {
            SmallTitle(
                text = stringResource(R.string.rear_double_tap_wake_section),
                insideMargin = PaddingValues(16.dp, 8.dp)
            )
            CardBlock(pressFeedbackType = PressFeedbackType.None) {
                ArrowPreference(
                    title = stringResource(R.string.rear_double_tap_choose_apps),
                    summary = countSummary(wakeCount),
                    onClick = onWakeAppsClick
                )
            }
        }
        item(key = "sleep") {
            SmallTitle(
                text = stringResource(R.string.rear_double_tap_sleep_section),
                insideMargin = PaddingValues(16.dp, 8.dp)
            )
            CardBlock(pressFeedbackType = PressFeedbackType.None) {
                ArrowPreference(
                    title = stringResource(R.string.rear_double_tap_choose_apps),
                    summary = countSummary(sleepCount),
                    onClick = onSleepAppsClick
                )
            }
        }
    }
}

@Composable
private fun countSummary(count: Int): String = if (count == 0) {
    stringResource(R.string.double_tap_wake_summary_none)
} else {
    pluralStringResource(R.plurals.double_tap_wake_summary_count, count, count)
}

/** 功能页入口的摘要：两份名单都空时说「未启用」，否则分别给出数量。 */
@Composable
internal fun rearDoubleTapEntrySummary(wakeCount: Int, sleepCount: Int): String =
    if (wakeCount == 0 && sleepCount == 0) {
        stringResource(R.string.rear_double_tap_summary_none)
    } else {
        stringResource(R.string.rear_double_tap_summary_counts, wakeCount, sleepCount)
    }
