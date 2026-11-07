package hook.HyperBackscreen.ui.home

import hook.HyperBackscreen.ui.theme.currentAppColors
import hook.HyperBackscreen.ui.theme.HomeUiTokens
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hook.HyperBackscreen.BuildConfig
import hook.HyperBackscreen.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

private val CardHeight = 118.dp
private val GlyphSize = 116.dp
private val GlyphOffsetX = 26.dp
private val GlyphOffsetY = 30.dp
private val CardCornerRadius = HomeUiTokens.CornerCard

private val DotSize = 7.dp

@Composable
internal fun HomeStatusCard(
    activated: Boolean,
    scopeStatus: List<ScopeStatusItem>? = null,
    onClick: (() -> Unit)? = null
) {
    // 颜色统一来自 AppColors：绿卡在 Monet 下自动改跟主题色。
    val colors = currentAppColors
    val containerColor = if (activated) colors.statusActiveContainer else MiuixTheme.colorScheme.errorContainer
    val contentColor = if (activated) colors.onStatusActiveContainer else MiuixTheme.colorScheme.onErrorContainer
    val glyphColor = if (activated) colors.statusActiveGlyph else MiuixTheme.colorScheme.error

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(),
        cornerRadius = CardCornerRadius,
        colors = CardDefaults.defaultColors(
            color = containerColor,
            contentColor = contentColor
        ),
        pressFeedbackType = PressFeedbackType.None,
        showIndication = onClick != null,
        holdDownState = false,
        onClick = onClick,
        onLongPress = null
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = CardHeight)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = GlyphOffsetX, y = GlyphOffsetY)
            ) {
                StatusGlyph(
                    activated = activated,
                    color = glyphColor,
                    modifier = Modifier.size(GlyphSize)
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp)
            ) {
                // 作用域圆点和「已激活」放在同一行：一眼看到激活状态和各作用域是否需要重启。
                // 右侧留出对勾图形的位置，太窄时圆点自动折到下一行。
                Row(
                    modifier = Modifier.padding(end = 56.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(
                            if (activated) R.string.home_status_activated else R.string.home_status_inactive
                        ),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (activated && scopeStatus != null) {
                        ScopeStatusRow(
                            items = scopeStatus,
                            contentColor = contentColor,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
                Text(
                    text = if (activated) {
                        stringResource(R.string.common_version, BuildConfig.VERSION_NAME)
                    } else {
                        stringResource(R.string.home_status_inactive_summary)
                    },
                    modifier = Modifier.padding(top = 3.dp),
                    fontSize = 15.sp,
                    color = contentColor.copy(alpha = 0.78f),
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun StatusGlyph(
    activated: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.075f
        drawCircle(
            color = color,
            radius = size.minDimension / 2f - stroke / 2f,
            style = Stroke(width = stroke)
        )
        if (activated) {
            val check = Path().apply {
                moveTo(size.width * 0.30f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.66f)
                lineTo(size.width * 0.71f, size.height * 0.35f)
            }
            drawPath(
                path = check,
                color = color,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        } else {
            val inset = size.minDimension * 0.31f
            drawLine(
                color = color,
                start = Offset(inset, inset),
                end = Offset(size.width - inset, size.height - inset),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = color,
                start = Offset(size.width - inset, inset),
                end = Offset(inset, size.height - inset),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * 各作用域的注入状态：一个色点 + 简称。
 * 蓝 = 已注入当前版本；红 = 需要重启（更新后还是旧版本）或加载失败；灰 = 未运行；空心 = 没勾选作用域。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScopeStatusRow(
    items: List<ScopeStatusItem>,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { item ->
            val label = stringResource(item.shortLabelRes)
            val description = stringResource(item.labelRes) + ": " + stringResource(item.descriptionRes)
            Row(
                modifier = Modifier.semantics { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                ScopeDot(item.state, contentColor)
                Text(
                    text = label,
                    fontSize = 12.sp,
                    color = contentColor.copy(alpha = 0.78f),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ScopeDot(state: ScopeState, contentColor: Color) {
    val base = Modifier.size(DotSize)
    val colors = currentAppColors
    when (state) {
        ScopeState.ACTIVE -> Box(base.background(colors.scopeActive, CircleShape))
        // 需要重启和加载失败都要用户处理，统一用红点；具体原因点卡片看。
        ScopeState.STALE, ScopeState.FAILED -> Box(base.background(colors.scopeNeedsRestart, CircleShape))
        ScopeState.IDLE -> Box(base.background(contentColor.copy(alpha = 0.3f), CircleShape))
        // 没勾选作用域画空心圈，和「勾了但没运行」的实心灰点区分开。
        ScopeState.NOT_IN_SCOPE -> Box(base.border(1.2.dp, contentColor.copy(alpha = 0.45f), CircleShape))
    }
}
