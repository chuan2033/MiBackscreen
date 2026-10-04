package hook.HyperBackscreen.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
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
private val CardCornerRadius = 16.dp

// miuix 的颜色令牌里没有绿色系，绿卡只能写死；浅色用浅绿底配亮绿勾，深色用深绿底。
private val WorkingContainerLight = Color(0xFFDFFAE4)
private val WorkingContainerDark = Color(0xFF1A3825)
private val WorkingGlyph = Color(0xFF36D167)

@Composable
internal fun HomeStatusCard(
    activated: Boolean,
    usesDynamicColors: Boolean
) {
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    // Monet 下固定绿跟主题色系打架，改跟主题走；只有非动态取色才用写死的那套绿。
    val containerColor = when {
        !activated -> MiuixTheme.colorScheme.errorContainer
        usesDynamicColors -> MiuixTheme.colorScheme.secondaryContainer
        dark -> WorkingContainerDark
        else -> WorkingContainerLight
    }
    val contentColor = when {
        !activated -> MiuixTheme.colorScheme.onErrorContainer
        usesDynamicColors -> MiuixTheme.colorScheme.onSecondaryContainer
        else -> MiuixTheme.colorScheme.onSurfaceContainer
    }
    val glyphColor = when {
        !activated -> MiuixTheme.colorScheme.error
        usesDynamicColors -> MiuixTheme.colorScheme.primary
        else -> WorkingGlyph
    }

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
        showIndication = false,
        holdDownState = false,
        onClick = null,
        onLongPress = null
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CardHeight)
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
                    .padding(start = 16.dp, top = 14.dp, end = 16.dp)
            ) {
                Text(
                    text = stringResource(
                        if (activated) R.string.home_status_activated else R.string.home_status_inactive
                    ),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium
                )
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
