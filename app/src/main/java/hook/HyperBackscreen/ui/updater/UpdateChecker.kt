package hook.HyperBackscreen.ui.updater

import android.content.Context
import hook.HyperBackscreen.ui.util.openUrl
import android.os.SystemClock
import android.util.Log
import hook.HyperBackscreen.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 一条可下载的更新：版本名、发布日期、变更说明、APK 直链。 */
data class UpdateInfo(
    val versionName: String,
    val publishedAt: String,
    val notes: String,
    val apkUrl: String,
)

/** 一次检查的结果：有新版 / 已是最新 / 检查失败（网络或接口异常），三者要区分开展示。 */
sealed interface UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object Failed : UpdateCheckResult
}

/**
 * 查 GitHub Release 有没有比本机更新的正式版。
 *
 * 结果在进程内缓存，两次真实请求之间至少隔 [MIN_INTERVAL_MS]，避免把 GitHub
 * 对未登录请求的配额（每 IP 每小时 60 次）耗尽。检查失败不覆盖缓存、也不计入节流，
 * 之前查到的新版本会保留，下次可以立即重试。
 */
object UpdateChecker {

    private const val RELEASES_URL =
        "https://api.github.com/repos/chuan2033/MiBackscreen/releases"
    private const val MIN_INTERVAL_MS = 10L * 60 * 1000

    @Volatile
    private var cached: UpdateInfo? = null

    @Volatile
    private var checkedAt = 0L

    /** 上次真实检查的墙钟时间（毫秒）；0 表示从未检查过。用于「上次检测」展示。 */
    @Volatile
    var lastCheckAtWallClock: Long = 0L
        private set

    /** 最近一次真实请求是否失败；用于页面提示「检查更新失败」。 */
    @Volatile
    var lastCheckFailed: Boolean = false
        private set

    /** 上次成功检查的结果；可能过期，仅供展示。 */
    fun last(): UpdateInfo? = cached

    /** 距上次真实请求是否已满 [MIN_INTERVAL_MS]；从未检查过时为 true。 */
    private fun due(): Boolean {
        val at = checkedAt
        return at == 0L || SystemClock.elapsedRealtime() - at >= MIN_INTERVAL_MS
    }

    /**
     * 查一次。
     *
     * @param force 忽略 [MIN_INTERVAL_MS] 节流，强制发起请求；页面上的手动「检查更新」用它，
     *   否则启动自动检查之后的十分钟内点按钮没有任何反应。
     */
    suspend fun check(force: Boolean = false): UpdateCheckResult {
        if (!force && !due()) {
            return cached?.let { UpdateCheckResult.Available(it) } ?: UpdateCheckResult.UpToDate
        }
        val result = fetch()
        if (result == UpdateCheckResult.Failed) {
            // 失败时保留之前的缓存与检测时间，不能把已查到的新版本清掉、更不能显示成「已是最新」。
            lastCheckFailed = true
            return result
        }
        cached = (result as? UpdateCheckResult.Available)?.info
        lastCheckFailed = false
        checkedAt = SystemClock.elapsedRealtime()
        lastCheckAtWallClock = System.currentTimeMillis()
        return result
    }

    /** 全部正式版，新的在前；网络失败返回空列表。用于「版本历史」。 */
    suspend fun history(): List<UpdateInfo> = withContext(Dispatchers.IO) {
        try {
            readReleases()
        } catch (e: Exception) {
            Log.w("MiBackscreen", "release history failed", e)
            emptyList()
        }
    }

    private suspend fun fetch(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            // GitHub 按创建时间排序：先发 1.3.0、后给旧分支补发 1.2.7 时，第一条是 1.2.7。
            // 取版本号最大的一条，而不是列表第一条。
            val newest = readReleases().maxWithOrNull { a, b -> compareVersions(a.versionName, b.versionName) }
                ?: return@withContext UpdateCheckResult.UpToDate
            if (!versionAfter(newest.versionName, BuildConfig.VERSION_NAME)) {
                return@withContext UpdateCheckResult.UpToDate
            }
            UpdateCheckResult.Available(newest)
        } catch (e: Exception) {
            Log.w("MiBackscreen", "update check failed", e)
            UpdateCheckResult.Failed
        }
    }

    /** 拉取并解析 Release 列表，只保留带 APK 直链、且 tag 可解析的正式版，新的在前。 */
    private fun readReleases(): List<UpdateInfo> {
        val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "MiBackscreen")
            // 非 200（限流、服务异常）算失败而不是「没有发布」，交给调用方区分。
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw java.io.IOException("HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(body)
            val out = ArrayList<UpdateInfo>()
            for (i in 0 until array.length()) {
                val release = array.getJSONObject(i)
                if (release.optBoolean("prerelease", false)) continue
                val tag = release.optString("tag_name", "").removePrefix("v").trim()
                if (!looksLikeVersion(tag)) continue
                val apk = apkUrl(release) ?: continue
                out.add(
                    UpdateInfo(
                        versionName = tag,
                        publishedAt = release.optString("published_at", ""),
                        notes = cleanNotes(release.optString("body", "")),
                        apkUrl = apk
                    )
                )
            }
            out
        } finally {
            connection.disconnect()
        }
    }

    private fun apkUrl(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val url = assets.getJSONObject(i).optString("browser_download_url", "")
            if (url.endsWith(".apk", ignoreCase = true)) return url
        }
        return null
    }

    /** 裁掉 GitHub 自动生成的「Full Changelog」跳转，其余按原文保留。 */
    private fun cleanNotes(body: String): String {
        val marker = "**Full Changelog**"
        val index = body.indexOf(marker)
        val trimmed = if (index >= 0) body.substring(0, index) else body
        return trimmed.trim()
    }

    /** 形如 1.2.3，最少两段、全数字。 */
    private fun looksLikeVersion(value: String): Boolean {
        val parts = value.split('.')
        return parts.size >= 2 && parts.all { segment ->
            segment.isNotEmpty() && segment.all(Char::isDigit)
        }
    }

    /** a 是否比 b 新；按点分段逐段比，段数更多且在共同段相等时算新。 */
    private fun versionAfter(a: String, b: String): Boolean = compareVersions(a, b) > 0

    /** 按点分段逐段比较；共同段相等时段数更多的算新。无法解析的段按 0 处理。 */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        val n = minOf(pa.size, pb.size)
        for (i in 0 until n) {
            if (pa[i] != pb[i]) return pa[i].compareTo(pb[i])
        }
        return pa.size.compareTo(pb.size)
    }

    /** 用浏览器打开 APK 直链，交给浏览器自动下载。 */
    fun openDownload(context: Context, info: UpdateInfo): Boolean = openUrl(context, info.apkUrl)
}
