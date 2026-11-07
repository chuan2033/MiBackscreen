package hook.HyperBackscreen.lyric

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager

/**
 * 模块 App 里检测 SuperLyric 是否可用（界面提示与反馈日志用）。
 *
 * SuperLyric 的 system 作用域生效后会把服务 Binder 放进每个应用进程（包括模块 App）的 ServiceManager 缓存，
 * 所以在这里能拿到，背屏中心里也一定能拿到。有 Binder 调用，调用方放后台线程。
 */
internal enum class SuperLyricAvailability {
    /** 没装 SuperLyric。 */
    NOT_INSTALLED,

    /** 装了但拿不到服务：没在 LSPosed 里启用 / 没勾 system，或刚更新还没重启手机。 */
    SERVICE_UNAVAILABLE,

    /** 3.x 服务（super_lyric）。 */
    LEGACY,

    /** 4.x 服务（super_lyric.v4）。 */
    V4;

    companion object {
        const val PACKAGE = "com.hchen.superlyric"

        fun detect(context: Context): SuperLyricAvailability {
            val installed = runCatching {
                context.packageManager.getPackageInfo(PACKAGE, PackageManager.PackageInfoFlags.of(0))
            }.isSuccess
            return from(installed, SuperLyricBackend.select(::lookupService))
        }

        /** 纯判定：服务能拿到就以服务为准（包名改过也照样可用），否则看有没有安装。 */
        fun from(installed: Boolean, backend: SuperLyricBackend): SuperLyricAvailability = when (backend) {
            SuperLyricBackend.V4 -> V4
            SuperLyricBackend.LEGACY -> LEGACY
            SuperLyricBackend.NONE -> if (installed) SERVICE_UNAVAILABLE else NOT_INSTALLED
        }

        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        private fun lookupService(name: String): Any? = runCatching {
            Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java)
                .invoke(null, name)
        }.getOrNull()
    }
}
