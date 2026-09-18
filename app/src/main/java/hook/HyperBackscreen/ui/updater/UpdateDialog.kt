package hook.HyperBackscreen.ui.updater

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hook.HyperBackscreen.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** 检测到新版本时的弹窗：标题 + 更新内容 + 「取消」「下载」。 */
@Composable
fun UpdateDialog(
    update: UpdateInfo?,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    WindowDialog(
        show = update != null,
        title = stringResource(R.string.update_available_title),
        onDismissRequest = onDismiss
    ) {
        update?.let { info ->
            val body = remember(info) { updateBody(info) }
            Text(
                text = body,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.body1
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(
                text = stringResource(R.string.update_cancel),
                modifier = Modifier.weight(1f).height(48.dp),
                onClick = onDismiss
            )
            Button(
                modifier = Modifier.weight(1f).height(48.dp),
                onClick = onDownload,
                colors = ButtonDefaults.buttonColorsPrimary(),
                content = { Text(stringResource(R.string.update_download)) }
            )
        }
    }
}

/** 版本号加粗，正文按 Markdown 的常用子集渲染（标题/加粗/行内代码/列表）。 */
private fun updateBody(info: UpdateInfo): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp)) {
        append("v")
        append(info.versionName)
    }
    append("\n\n")
    append(updateNotes(info.notes))
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^[-*]\\s+(.*)$")
private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
private val INLINE = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")

/** 逐行转换 Markdown；空行保留为段落间隔。 */
private fun updateNotes(markdown: String): AnnotatedString = buildAnnotatedString {
    var first = true
    markdown.lineSequence().forEach { raw ->
        if (!first) append('\n')
        first = false
        val line = LINK.replace(raw.trim(), "$1")
        when {
            line.isEmpty() -> Unit
            else -> {
                val heading = HEADING.find(line)
                if (heading != null) {
                    withStyle(headingStyle(heading.groupValues[1].length)) {
                        appendInline(heading.groupValues[2])
                    }
                } else {
                    val bullet = BULLET.matchEntire(line)
                    if (bullet != null) {
                        append("•  ")
                        appendInline(bullet.groupValues[1])
                    } else {
                        appendInline(line)
                    }
                }
            }
        }
    }
}

private fun headingStyle(level: Int) = SpanStyle(
    fontWeight = FontWeight.Bold,
    fontSize = when (level) {
        1 -> 19.sp
        2 -> 17.sp
        else -> 16.sp
    }
)

/** 行内样式：**加粗** 与 `代码`；其余原样。 */
private fun AnnotatedString.Builder.appendInline(text: String) {
    var cursor = 0
    for (match in INLINE.findAll(text)) {
        if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
        val bold = match.groupValues[1]
        if (bold.isNotEmpty()) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
        } else {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(match.groupValues[2]) }
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}
