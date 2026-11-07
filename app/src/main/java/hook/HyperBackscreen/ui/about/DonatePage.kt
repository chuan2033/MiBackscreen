package hook.HyperBackscreen.ui.about

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import hook.HyperBackscreen.ui.components.DetailPageScaffold
import hook.HyperBackscreen.ui.theme.currentAppColors
import hook.HyperBackscreen.ui.theme.HomeUiTokens
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hook.HyperBackscreen.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme


private val CardCornerRadius = HomeUiTokens.CornerLarge
private val QrBlockCornerRadius = HomeUiTokens.CornerCard
private val ButtonCornerRadius = 26.dp
private val ButtonHeight = 52.dp

private fun donateQrRes(index: Int): Int = if (index == 0) R.drawable.qr_wechat else R.drawable.qr_alipay

private fun donateQrFileName(index: Int): String =
    if (index == 0) "mibackscreen_wechat_qr.png" else "mibackscreen_alipay_qr.jpg"

@Composable
internal fun DonatePage(onBack: () -> Unit) {
    val tabs = listOf(
        stringResource(R.string.about_donate_wechat),
        stringResource(R.string.about_donate_alipay)
    )
    var selectedIndex by rememberSaveable { mutableStateOf(0) }
    var saveMessage by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isWeChat = selectedIndex == 0
    // 品牌渐变来自 AppColors：微信绿 / 支付宝蓝，深色模式下已压暗一档。
    val colors = currentAppColors
    val cardTop = if (isWeChat) colors.weChatTop else colors.alipayTop
    val cardBottom = if (isWeChat) colors.weChatBottom else colors.alipayBottom

    fun saveQr() {
        val res = donateQrRes(selectedIndex)
        val fileName = donateQrFileName(selectedIndex)
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                QrActions.saveToGallery(context, res, fileName)
            }
            saveMessage = if (saved) R.string.about_donate_saved else R.string.about_donate_save_failed
        }
    }

    DetailPageScaffold(
        title = stringResource(R.string.about_donate_title),
        onBack = onBack,
    ) {
        item {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val tabWidth = (maxWidth - 9.dp) / 2
                TabRow(
                    tabs = tabs,
                    selectedTabIndex = selectedIndex,
                    onTabSelected = {
                        selectedIndex = it
                        saveMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minWidth = tabWidth,
                    maxWidth = tabWidth
                )
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
        }
        item {
            DonationCard(
                topColor = cardTop,
                bottomColor = cardBottom,
                qrRes = donateQrRes(selectedIndex),
                name = stringResource(R.string.about_donate_name)
            )
        }
        item {
            Spacer(Modifier.height(24.dp))
        }
        item {
            DonateButton(
                label = stringResource(R.string.about_donate_save_qr),
                containerColor = cardTop,
                contentColor = Color.White,
                onClick = { saveQr() }
            )
        }
        item {
            val message = saveMessage
            if (message != null) {
                Text(
                    text = stringResource(message),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** 赞赏卡片：渐变底、白色二维码块、署名。 */
@Composable
private fun DonationCard(
    topColor: Color,
    bottomColor: Color,
    qrRes: Int,
    name: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardCornerRadius))
            .background(Brush.verticalGradient(listOf(topColor, bottomColor)))
            .padding(horizontal = 20.dp, vertical = 22.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(QrBlockCornerRadius))
                    .background(Color.White)
                    .padding(18.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = qrRes),
                    contentDescription = stringResource(R.string.about_donate_qr_description),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    contentScale = ContentScale.Fit
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = name,
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun DonateButton(
    label: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    // 用 miuix Button：自带按压反馈与 Button 语义，颜色沿用当前收款方式的品牌色。
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(ButtonHeight),
        cornerRadius = ButtonCornerRadius,
        colors = ButtonDefaults.buttonColors(
            color = containerColor,
            contentColor = contentColor
        )
    ) {
        Text(
            text = label,
            color = contentColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
