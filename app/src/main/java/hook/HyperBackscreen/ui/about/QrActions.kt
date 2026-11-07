package hook.HyperBackscreen.ui.about

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.DrawableRes

/**
 * 收款码相关的系统动作：保存到相册。
 */
internal object QrActions {

    /** 把 drawable 资源写进相册 Pictures/MiBackscreen 目录；成功返回 true。 */
    fun saveToGallery(context: Context, @DrawableRes res: Int, displayName: String): Boolean {
        val resolver = context.contentResolver
        var uri: Uri? = null
        return try {
            val mimeType = if (displayName.endsWith(".jpg", ignoreCase = true)) "image/jpeg" else "image/png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/MiBackscreen"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val inserted = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            uri = inserted
            val written = resolver.openOutputStream(inserted)?.use { out ->
                context.resources.openRawResource(res).use { input -> input.copyTo(out) }
                true
            } ?: false
            if (!written) {
                discardPending(context, inserted)
                return false
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(inserted, values, null, null)
            true
        } catch (_: Exception) {
            // 插入后写入失败：删掉这条 pending 记录，不在相册里留一个空的占位。
            uri?.let { discardPending(context, it) }
            false
        }
    }

    private fun discardPending(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }
}
