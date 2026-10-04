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

/** 一条可下载的更新：版本名、变更说明、APK 直链。 */
data class UpdateInfo(
    val versionName: String,
    val notes: String,
    val apkUrl: String,
)

/**
 * 查 GitHub Release 有没有比本机更新的正式版。
 *
 * 结果在进程内缓存，两次真实请求之间至少隔 [MIN_INTERVAL_MS]，避免把 GitHub
 * 对未登录请求的配额（每 IP 每小时 60 次）耗尽。检查失败和没有新版是同一个结果——null。
 */
object UpdateChecker {

    private const val RELEASES_URL =
        "https://api.github.com/repos/chuan2033/MiBackscreen/releases"
    private const val MIN_INTERVAL_MS = 10L * 60 * 1000

    @Volatile
    private var cached: UpdateInfo? = null

    @Volatile
    private var checkedAt = 0L

    /** 上次检查的结果；可能过期，仅供展示。 */
    fun last(): UpdateInfo? = cached

    /** 距上次真实请求是否已满 [MIN_INTERVAL_MS]；从未检查过时为 true。 */
    private fun due(): Boolean {
        val at = checkedAt
        return at == 0L || SystemClock.elapsedRealtime() - at >= MIN_INTERVAL_MS
    }

    /** 查一次；不满足间隔时直接复用上次结果，不发网络请求。 */
    suspend fun check(): UpdateInfo? {
        if (!due()) return cached
        val result = fetch()
        cached = result
        checkedAt = SystemClock.elapsedRealtime()
        return result
    }

    private suspend fun fetch(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val newest = readReleases().firstOrNull() ?: return@withContext null
            if (!versionAfter(newest.versionName, BuildConfig.VERSION_NAME)) {
                return@withContext null
            }
            newest
        } catch (e: Exception) {
            Log.w("MiBackscreen", "update check failed", e)
            null
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
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(body)
            val out = ArrayList<UpdateInfo>()
            for (i in 0 until array.length()) {
                val release = array.getJSONObject(i)
                if (release.optBoolean("prerelease", false)) continue
                val tag = release.optString("tag_name", "").removePrefix("v").trim()
                if (!looksLikeVersion(tag)) continue
                val apk = apkUrl(release) ?: continue
                out.add(UpdateInfo(tag, cleanNotes(release.optString("body", "")), apk))
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
    private fun versionAfter(a: String, b: String): Boolean {
        val pa = a.split('.').map(String::toInt)
        val pb = b.split('.').map(String::toInt)
        val n = minOf(pa.size, pb.size)
        for (i in 0 until n) {
            if (pa[i] != pb[i]) return pa[i] > pb[i]
        }
        return pa.size > pb.size
    }

    /** 用浏览器打开 APK 直链，交给浏览器自动下载。 */
    fun openDownload(context: Context, info: UpdateInfo): Boolean = openUrl(context, info.apkUrl)
}
