package hook.HyperBackscreen.ui.home

import hook.HyperBackscreen.ui.theme.HomeUiTokens
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import hook.HyperBackscreen.ui.theme.currentAppColors
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

private val DotSize = 7.dp
private const val ScopeFadeMillis = 220
private const val StatusColorMillis = 300
private const val StatusMarkMillis = 360

/**
 * 模块激活状态。Xposed 服务是异步绑定的：绑定完成前不知道是否激活，此时显示「检测中」，
 * 而不是先按「未激活」画一帧红卡再跳成绿卡。
 */
internal enum class ActivationState { CHECKING, ACTIVE, INACTIVE }

@Composable
internal fun HomeStatusCard(
    activation: ActivationState,
    scopeStatus: List<ScopeStatusItem>? = null,
    onClick: (() -> Unit)? = null
) {
    val activated = activation == ActivationState.ACTIVE
    // 颜色统一来自 AppColors：绿卡在 Monet 下自动改跟主题色。检测中用中性卡片色。
    val colors = currentAppColors
    val scheme = MiuixTheme.colorScheme
    val targetContainer = when (activation) {
        ActivationState.ACTIVE -> colors.statusActiveContainer
        ActivationState.INACTIVE -> scheme.errorContainer
        ActivationState.CHECKING -> scheme.surfaceContainer
    }
    val targetContent = when (activation) {
        ActivationState.ACTIVE -> colors.onStatusActiveContainer
        ActivationState.INACTIVE -> scheme.onErrorContainer
        ActivationState.CHECKING -> scheme.onSurfaceContainer
    }
    val targetGlyph = when (activation) {
        ActivationState.ACTIVE -> colors.statusActiveGlyph
        ActivationState.INACTIVE -> scheme.error
        ActivationState.CHECKING -> scheme.onSurfaceVariantSummary.copy(alpha = 0.25f)
    }
    // 状态确定后颜色渐变过去，不是瞬间换色。
    val containerColor by animateColorAsState(targetContainer, tween(StatusColorMillis), label = "statusContainer")
    val contentColor by animateColorAsState(targetContent, tween(StatusColorMillis), label = "statusContent")
    val glyphColor by animateColorAsState(targetGlyph, tween(StatusColorMillis), label = "statusGlyph")
    // 对勾 / 叉号在状态确定时一笔画出来；检测中只有圆圈。
    val markProgress by animateFloatAsState(
        targetValue = if (activation == ActivationState.CHECKING) 0f else 1f,
        animationSpec = tween(StatusMarkMillis, delayMillis = StatusColorMillis / 3, easing = FastOutSlowInEasing),
        label = "statusMark"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(),
        cornerRadius = HomeUiTokens.CornerCard,
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
                    activated = activation != ActivationState.INACTIVE,
                    markProgress = markProgress,
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
                    Crossfade(
                        targetState = activation,
                        animationSpec = tween(StatusColorMillis),
                        label = "statusTitle"
                    ) { state ->
                        Text(
                            text = stringResource(
                                when (state) {
                                    ActivationState.ACTIVE -> R.string.home_status_activated
                                    ActivationState.INACTIVE -> R.string.home_status_inactive
                                    ActivationState.CHECKING -> R.string.home_status_checking
                                }
                            ),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    // 作用域状态是异步读到的：淡入出现，不在读到的那一帧突然蹦出来。
                    // 退场时继续用上一次的数据画完淡出，避免内容先消失再淡出。
                    var lastStatus by remember { mutableStateOf(scopeStatus) }
                    if (scopeStatus != null) lastStatus = scopeStatus
                    AnimatedVisibility(
                        visible = activated && scopeStatus != null,
                        enter = fadeIn(tween(ScopeFadeMillis)),
                        exit = fadeOut(tween(ScopeFadeMillis))
                    ) {
                        lastStatus?.let { items ->
                            ScopeStatusRow(
                                items = items,
                                contentColor = contentColor,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }
                Crossfade(
                    targetState = activation,
                    animationSpec = tween(StatusColorMillis),
                    label = "statusSummary"
                ) { state ->
                    Text(
                        text = when (state) {
                            ActivationState.ACTIVE ->
                                stringResource(R.string.common_version, BuildConfig.VERSION_NAME)
                            ActivationState.INACTIVE -> stringResource(R.string.home_status_inactive_summary)
                            ActivationState.CHECKING -> stringResource(R.string.home_status_checking_summary)
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
}

@Composable
private fun StatusGlyph(
    activated: Boolean,
    markProgress: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    val mark = remember { Path() }
    val measure = remember { PathMeasure() }
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.075f
        drawCircle(
            color = color,
            radius = size.minDimension / 2f - stroke / 2f,
            style = Stroke(width = stroke)
        )
        if (markProgress <= 0f) return@Canvas
        val style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (activated) {
            val check = Path().apply {
                moveTo(size.width * 0.30f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.66f)
                lineTo(size.width * 0.71f, size.height * 0.35f)
            }
            measure.setPath(check, false)
            mark.reset()
            measure.getSegment(0f, measure.length * markProgress, mark, true)
            drawPath(path = mark, color = color, style = style)
        } else {
            val inset = size.minDimension * 0.31f
            // 叉号两笔依次画出：前半段画第一笔，后半段画第二笔。
            val first = (markProgress * 2f).coerceIn(0f, 1f)
            val second = (markProgress * 2f - 1f).coerceIn(0f, 1f)
            drawLine(
                color = color,
                start = Offset(inset, inset),
                end = Offset(inset + (size.width - 2 * inset) * first, inset + (size.height - 2 * inset) * first),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            if (second > 0f) {
                drawLine(
                    color = color,
                    start = Offset(size.width - inset, inset),
                    end = Offset(
                        size.width - inset - (size.width - 2 * inset) * second,
                        inset + (size.height - 2 * inset) * second
                    ),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
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
