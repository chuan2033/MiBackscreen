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
import org.json.JSONObject

/**
 * 关于页里「怎么找到做这个模块的人」——全应用唯一的一份，换人、换群只改这里。
 *
 * 成员的昵称、简介、头像都从各自的 GitHub 主页读（[rememberGitHubProfile]），这里的 [name]、[role]
 * 只是读不到 GitHub 时的兜底：国内网络经常连不上，关于页不能因此空着。
 */
@Immutable
internal data class TeamMember(
    val name: String,
    val role: String,
    val github: String,
) {
    /**
     * GitHub 头像站按用户名直接返回图片，不经 github.com 跳转（国内网络下 github.com 常连不上），
     * 也不占 API 次数，资料没读到时头像照样能显示。头像显示 40dp，3 倍屏约 120px，按 256px 要图。
     */
    val avatarUrl: String
        get() = "https://avatars.githubusercontent.com/$github?s=256"

    val profileUrl: String
        get() = "https://github.com/$github"
}

/** 排序即署名顺序，不按用户名或拼音自动排。 */
internal val TEAM: List<TeamMember> = listOf(
    TeamMember(name = "小语义", role = "作者", github = "chuan2033"),
    TeamMember(name = "川意", role = "UI设计", github = "MiChongs"),
)

/** GitHub 公开资料里关于页要用的部分；没填的字段为 null。 */
@Immutable
internal data class GitHubProfile(
    val name: String?,
    val bio: String?,
)

/**
 * 读成员的 GitHub 公开资料。先给缓存（内存，其次缓存目录），缓存超过 [GitHubProfiles.REFRESH_MS]
 * 才再请求接口；请求失败就继续用旧缓存，一次都没读到时为 null，由调用方用 [TeamMember] 的兜底文字。
 */
@Composable
internal fun rememberGitHubProfile(login: String): GitHubProfile? {
    val context = LocalContext.current
    val profile by produceState(initialValue = GitHubProfiles.memory[login], login) {
        value = GitHubProfiles.load(context.applicationContext, login) ?: value
    }
    return profile
}

private object GitHubProfiles {
    val memory = ConcurrentHashMap<String, GitHubProfile>()

    /** 未登录的 GitHub API 每小时只给 60 次，资料一天刷一次足够。 */
    const val REFRESH_MS = 24L * 60 * 60 * 1000
    private const val TIMEOUT_MS = 8_000

    suspend fun load(context: Context, login: String): GitHubProfile? = withContext(Dispatchers.IO) {
        val file = File(File(context.cacheDir, "github").apply { mkdirs() }, "$login.json")
        val cached = runCatching { parse(file.readText()) }.getOrNull()
        if (cached != null) memory[login] = cached
        val fresh = cached != null && System.currentTimeMillis() - file.lastModified() < REFRESH_MS
        if (fresh) return@withContext cached
        val body = runCatching { fetch(login) }.getOrNull() ?: return@withContext cached
        val profile = runCatching { parse(body) }.getOrNull() ?: return@withContext cached
        runCatching { file.writeText(body) }
        memory[login] = profile
        profile
    }

    private fun parse(json: String): GitHubProfile {
        val obj = JSONObject(json)
        fun field(key: String) = obj.optString(key).takeIf { !obj.isNull(key) && it.isNotBlank() }?.trim()
        return GitHubProfile(name = field("name"), bio = field("bio"))
    }

    private fun fetch(login: String): String {
        val connection = URL("https://api.github.com/users/$login").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "MiBackscreen")
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

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
    url: String?,
    fallback: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val context = LocalContext.current
    val image by produceState(initialValue = url?.let { AvatarCache.memory[it] }, url) {
        value = url?.let { AvatarCache.memory[it] ?: AvatarCache.load(context.applicationContext, it) }
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

    /**
     * 解码后的最短边不小于这个像素数。头像显示 40dp，3 倍屏要 120px，留一倍余量；
     * 不能写死缩放倍数：QQ 头像 640px、GitHub 头像 256px，固定缩到 1/4 会把后者缩成 64px 以下而发糊。
     */
    private const val MIN_DECODED_PX = 256
    private const val TIMEOUT_MS = 8_000

    suspend fun load(context: Context, url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        memory[url]?.let { return@withContext it }
        val file = File(File(context.cacheDir, "avatars").apply { mkdirs() }, "${url.hashCode().toUInt()}.jpg")
        val bytes = runCatching { file.takeIf { it.isFile && it.length() > 0 }?.readBytes() }.getOrNull()
            ?: runCatching { download(url) }.getOrNull()?.also { runCatching { file.writeBytes(it) } }
            ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MIN_DECODED_PX) sample *= 2
        val bitmap: Bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
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
