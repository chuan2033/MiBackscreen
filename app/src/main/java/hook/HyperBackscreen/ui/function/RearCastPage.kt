package hook.HyperBackscreen.ui.function

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.components.DetailPageScaffold
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 「应用投放到背屏」说明页。功能默认可用、没有开关，这里只讲入口与注意事项：
 * 控制中心磁贴、背屏面板的「投放」卡片、需要重启、背屏兼容性、背屏息屏时间（直达「息屏与定时」）。
 */
@Composable
internal fun RearCastPage(
    onRearScreenTimingClick: () -> Unit,
    onBack: () -> Unit,
) {
    DetailPageScaffold(
        title = stringResource(R.string.rear_cast_title),
        onBack = onBack,
        topSpacing = 0.dp,
    ) {
        item(key = "usage") {
            SmallTitle(
                text = stringResource(R.string.rear_cast_section_usage),
                insideMargin = PaddingValues(16.dp, 8.dp)
            )
            CardBlock(pressFeedbackType = PressFeedbackType.None) {
                BasicComponent(
                    title = stringResource(R.string.rear_cast_tile_usage_title),
                    summary = stringResource(R.string.rear_cast_tile_usage_summary)
                )
                BasicComponent(
                    title = stringResource(R.string.rear_cast_panel_usage_title),
                    summary = stringResource(R.string.rear_cast_panel_usage_summary)
                )
            }
        }
        item(key = "notes") {
            SmallTitle(
                text = stringResource(R.string.rear_cast_section_notes),
                insideMargin = PaddingValues(16.dp, 8.dp)
            )
            CardBlock(pressFeedbackType = PressFeedbackType.None) {
                BasicComponent(summary = stringResource(R.string.rear_cast_note_reboot))
                BasicComponent(summary = stringResource(R.string.rear_cast_note_running_only))
                BasicComponent(summary = stringResource(R.string.rear_cast_note_compat))
                ArrowPreference(
                    title = stringResource(R.string.rear_screen_timing_title),
                    summary = stringResource(R.string.rear_cast_note_timeout),
                    onClick = onRearScreenTimingClick
                )
            }
        }
    }
}
