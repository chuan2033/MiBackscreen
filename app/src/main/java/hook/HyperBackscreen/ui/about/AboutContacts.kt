package hook.HyperBackscreen.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.common.ActivityLaunch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * 关于页里「怎么找到做这个模块的人」——全应用唯一的一份，换号、换群只改这里。
 */
@Immutable
internal data class TeamMember(
    val name: String,
    val role: String,
    val qq: String,
) {
    /** QQ 头像的公开端点，号码即地址。 */
    val avatarUrl: String
        get() = "https://q.qlogo.cn/headimg_dl?dst_uin=$qq&spec=640&img_type=jpg"

    /** 临时会话；对方不必是好友。 */
    val chatUri: String
        get() = "mqqwpa://im/chat?chat_type=wpa&uin=$qq&version=1"
}

/** 排序即署名顺序，不按号码或拼音自动排。 */
internal val TEAM: List<TeamMember> = listOf(
    TeamMember(name = "小语义", role = "作者", qq = "1284025052"),
    TeamMember(name = "川意", role = "UI设计", qq = "3299699002"),
)

internal const val GROUP_QQ = "1098311165"

/** 群头像的公开端点，群号即地址；取不到时显示图标。 */
internal const val GROUP_AVATAR_URL = "https://p.qlogo.cn/gh/$GROUP_QQ/$GROUP_QQ/640"

/** 群名片。加群要走 QQ 自己的确认页，这里只负责把人送到那一页。 */
internal const val GROUP_CARD_URI =
    "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$GROUP_QQ" +
        "&card_type=group&source=qrcode"

/** 能接 QQ 协议的客户端，依次点名尝试，最后再交给系统自己选。 */
private val QQ_CLIENT_PACKAGES: List<String> = listOf(
    "com.tencent.mobileqq",
    "com.tencent.tim",
    "com.tencent.minihd.qq",
)

/**
 * 用 QQ 系客户端打开 [uri]。都打不开时把号码复制到剪贴板，返回 false，调用方提示用户手动搜。
 */
internal fun openInQq(context: Context, uri: String, number: String): Boolean {
    val target = Uri.parse(uri)
    val opened = QQ_CLIENT_PACKAGES.any { pkg ->
        ActivityLaunch.attempt {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, target).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    } || ActivityLaunch.attempt {
        context.startActivity(Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    if (!opened) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("QQ", number))
    }
    return opened
}

/**
 * 联网加载的圆形头像。先查内存，再查缓存目录，最后才下载；任何一步失败都显示 [fallback] 图标，
 * 没网、被墙或接口改版都不会让关于页出错。
 */
@Composable
internal fun AboutAvatar(
    url: String,
    fallback: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val context = LocalContext.current
    val image by produceState(initialValue = AvatarCache.memory[url], url) {
        if (value == null) value = AvatarCache.load(context.applicationContext, url)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            Icon(
                imageVector = fallback,
                contentDescription = null,
                modifier = Modifier.size(size * 0.55f),
                tint = MiuixTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private object AvatarCache {
    val memory = ConcurrentHashMap<String, ImageBitmap>()

    /** 头像只显示几十 dp，640px 的原图缩到 1/4 就够了。 */
    private const val SAMPLE_SIZE = 4
    private const val TIMEOUT_MS = 8_000

    suspend fun load(context: Context, url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        memory[url]?.let { return@withContext it }
        val file = File(File(context.cacheDir, "avatars").apply { mkdirs() }, "${url.hashCode().toUInt()}.jpg")
        val bytes = runCatching { file.takeIf { it.isFile && it.length() > 0 }?.readBytes() }.getOrNull()
            ?: runCatching { download(url) }.getOrNull()?.also { runCatching { file.writeBytes(it) } }
            ?: return@withContext null
        val bitmap: Bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = SAMPLE_SIZE },
        ) ?: run {
            file.delete()
            return@withContext null
        }
        bitmap.asImageBitmap().also { memory[url] = it }
    }

    private fun download(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
