package hook.HyperBackscreen.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.ui.theme.HomeUiTokens
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 上下两行式信息条：标签在上、值在下。 */
@Composable
internal fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    minHeight: Dp = HomeUiTokens.InfoRowHeight
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .padding(horizontal = HomeUiTokens.ListHorizontalPadding, vertical = 16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onBackground,
            style = MiuixTheme.textStyles.headline1
        )
        Text(
            text = value,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 左右两端式信息条：标签在左、值在右。 */
@Composable
internal fun SettingsInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    minHeight: Dp = HomeUiTokens.InfoRowHeight
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .padding(horizontal = HomeUiTokens.ListHorizontalPadding, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onBackground,
            style = MiuixTheme.textStyles.headline1,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
