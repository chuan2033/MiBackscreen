package hook.HyperBackscreen.ui.function

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * 「定时关闭背屏」的时间选择：小时 + 分钟两列，整分钟可选。
 *
 * 结果按「当天第几分钟」回传，格式化交给 `RearScreenSchedule.format`，
 * 避免两边各写一份补零逻辑。
 */
@Composable
internal fun RearScreenScheduleTimeDialog(
    initialMinutes: Int,
    onApply: (Int) -> Unit,
    onCancel: () -> Unit
) {
    var hour by remember(initialMinutes) { mutableIntStateOf(initialMinutes / 60) }
    var minute by remember(initialMinutes) { mutableIntStateOf(initialMinutes % 60) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NumberPicker(
                value = hour,
                onValueChange = { hour = it },
                range = 0..23,
                modifier = Modifier.weight(1f)
            )
            NumberPicker(
                value = minute,
                onValueChange = { minute = it },
                range = 0..59,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                text = stringResource(R.string.common_cancel),
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                text = stringResource(R.string.common_confirm),
                onClick = { onApply(hour * 60 + minute) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
