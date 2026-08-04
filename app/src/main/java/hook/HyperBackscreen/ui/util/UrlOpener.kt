package hook.HyperBackscreen.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri

/** 无浏览器（ActivityNotFound）或被策略拦截（SecurityException）时静默失败。 */
internal fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
    }
}
