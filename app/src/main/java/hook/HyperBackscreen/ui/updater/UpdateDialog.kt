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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
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
    downloadFailed: Boolean = false,
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
        if (downloadFailed) {
            Text(
                text = stringResource(R.string.activity_open_failed),
                modifier = Modifier.padding(top = 8.dp),
                color = MiuixTheme.colorScheme.error,
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
                content = { Text(stringResource(if (downloadFailed) R.string.common_retry else R.string.update_download)) }
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
private val ORDERED = Regex("^(\\d+)[.)]\\s+(.*)$")
private val HORIZONTAL_RULE = Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$")
private val QUOTE = Regex("^>\\s?(.*)$")
private val TABLE_ROW = Regex("^\\|(.+)\\|$")
private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
private val INLINE = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|(?<!\\*)\\*([^*]+)\\*(?!\\*)")

/** 列表项的悬挂缩进：折行后与文字对齐，不让第二行顶到圆点下面。 */
private fun listParagraphStyle() = ParagraphStyle(
    textIndent = TextIndent(firstLine = 0.sp, restLine = 24.sp),
    lineHeight = 21.sp
)

/** 空行只做段落分隔，压到半行高，否则标题上下会空出一大片。 */
private val SpacerLine = ParagraphStyle(lineHeight = 8.sp)

/** 逐行转换 Markdown：标题、列表、引用、分隔线、表格与行内样式。 */
internal fun updateNotes(markdown: String): AnnotatedString = buildAnnotatedString {
    var first = true
    var lastWasBlank = false
    markdown.lineSequence().forEach { raw ->
        val line = LINK.replace(raw.trim(), "$1")
        val blank = line.isEmpty()
        // 连续空行按 Markdown 语义只算一个段落分隔，不然空行会叠出一大片。
        if (blank && lastWasBlank) return@forEach
        if (!first) append('\n')
        first = false
        lastWasBlank = blank
        when {
            blank -> withStyle(SpacerLine) { append(" ") }
            HORIZONTAL_RULE.matches(line) -> withStyle(ParagraphStyle(lineHeight = 20.sp)) {
                append("────────────────────")
            }
            else -> {
                val heading = HEADING.find(line)
                val bullet = BULLET.matchEntire(line)
                val ordered = ORDERED.matchEntire(line)
                val quote = QUOTE.matchEntire(line)
                when {
                    heading != null -> withStyle(ParagraphStyle(lineHeight = 26.sp)) {
                        withStyle(headingStyle(heading.groupValues[1].length)) {
                            appendInline(heading.groupValues[2])
                        }
                    }
                    bullet != null -> withStyle(listParagraphStyle()) {
                        append("•  ")
                        appendInline(bullet.groupValues[1])
                    }
                    ordered != null -> withStyle(listParagraphStyle()) {
                        append(ordered.groupValues[1])
                        append(".  ")
                        appendInline(ordered.groupValues[2])
                    }
                    quote != null -> withStyle(listParagraphStyle()) {
                        append("│ ")
                        appendInline(quote.groupValues[1])
                    }
                    TABLE_ROW.matches(line) -> withStyle(listParagraphStyle()) {
                        appendInline(line.trim('|').replace("|", "   "))
                    }
                    else -> withStyle(ParagraphStyle(lineHeight = 21.sp)) { appendInline(line) }
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
        else -> 17.sp
    }
)

/** 行内样式：**加粗**、`代码`、*斜体*；其余原样。 */
private fun AnnotatedString.Builder.appendInline(text: String) {
    var cursor = 0
    for (match in INLINE.findAll(text)) {
        if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
        val bold = match.groupValues[1]
        val code = match.groupValues[2]
        val italic = match.groupValues[3]
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            code.isNotEmpty() -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    // 半透明灰在深浅色下都能形成代码底色，不依赖主题色。
                    background = Color.Gray.copy(alpha = 0.22f)
                )
            ) { append(code) }
            italic.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}
