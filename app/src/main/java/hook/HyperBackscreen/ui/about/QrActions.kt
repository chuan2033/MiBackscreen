package hook.HyperBackscreen.ui.about

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.DrawableRes

/**
 * 收款码相关的系统动作：保存到相册。
 */
internal object QrActions {

    /** 把 drawable 资源写进相册 Pictures/MiBackscreen 目录；成功返回 true。 */
    fun saveToGallery(context: Context, @DrawableRes res: Int, displayName: String): Boolean {
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
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                context.resources.openRawResource(res).use { input -> input.copyTo(out) }
            } ?: return false
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Exception) {
            false
        }
    }
}
