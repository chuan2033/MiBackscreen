package hook.HyperBackscreen.ui.battery

import android.graphics.BitmapFactory
import android.os.Process
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import hook.HyperBackscreen.common.Constants
import java.io.File
import org.json.JSONArray

/**
 * 读取背屏当前壁纸的真实画面。
 *
 * 主题引擎在 `/data/system/theme_magic/users/<user>/rearScreen/runtime.json` 里按 position 降序
 * 记录已应用的背屏壁纸，每项的 `snapshotPreviewPath` 指向显示时刻生成的背屏截图。
 * 该目录与文件对所有应用可读，不需要 root；列表首项即最近切换或应用的那张。
 *
 * 截图是快照而非实时画面，壁纸内的时间等动态元素会停留在当时的值。
 */
internal object RearScreenWallpaper {

    private const val PER_USER_RANGE = 100000
    private const val SNAPSHOT_FIELD = "snapshotPreviewPath"
    private const val RUNTIME_JSON_TEMPLATE =
        "/data/system/theme_magic/users/%d/rearScreen/runtime.json"

    /** 当前壁纸的截图路径与指纹；指纹变化即说明壁纸记录或截图已更新。 */
    internal data class Source(val screenshotPath: String, val stamp: String)

    private var cachedRuntimeStamp: String? = null
    private var cachedScreenshotPath: String? = null

    /** 解析当前壁纸来源；只在 runtime 列表本身变化时才重新解析 JSON。 */
    fun resolve(): Source? {
        val runtime = File(RUNTIME_JSON_TEMPLATE.format(Process.myUid() / PER_USER_RANGE))
        val runtimeStamp = "${runtime.lastModified()}:${runtime.length()}"
        if (runtimeStamp != cachedRuntimeStamp) {
            cachedScreenshotPath = readScreenshotPath(runtime)
            cachedRuntimeStamp = runtimeStamp
        }
        val path = cachedScreenshotPath ?: return null
        val screenshot = File(path)
        if (!screenshot.exists()) return null
        return Source(path, "$runtimeStamp|${screenshot.lastModified()}:${screenshot.length()}")
    }

    fun decode(source: Source): ImageBitmap? = runCatching {
        BitmapFactory.decodeFile(source.screenshotPath)?.asImageBitmap()
    }.getOrElse { error ->
        Log.w(Constants.LOG_TAG, "Unable to decode rear screen screenshot", error)
        null
    }

    private fun readScreenshotPath(runtime: File): String? = runCatching {
        val items = JSONArray(runtime.readText())
        if (items.length() == 0) {
            null
        } else {
            items.getJSONObject(0).optString(SNAPSHOT_FIELD).takeIf { it.isNotBlank() }
        }
    }.getOrElse { error ->
        Log.w(Constants.LOG_TAG, "Unable to read rear screen runtime list", error)
        null
    }
}
