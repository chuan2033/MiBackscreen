package hook.HyperBackscreen.ui

import androidx.annotation.StringRes
import hook.HyperBackscreen.R
import hook.HyperBackscreen.hook.AutoWallpaperRotationPolicy

/**
 * 定时切换背屏壁纸时的挑图方式。
 *
 * 取值必须与 [AutoWallpaperRotationPolicy] 里的字符串常量一致 —— 那里是宿主侧解析的唯一来源，
 * 这里只负责界面顺序与文案。
 */
internal enum class AutoWallpaperOrder(
    val prefValue: String,
    @get:StringRes val labelRes: Int,
) {
    SEQUENTIAL(AutoWallpaperRotationPolicy.ORDER_SEQUENTIAL, R.string.auto_wallpaper_order_sequential),
    RANDOM(AutoWallpaperRotationPolicy.ORDER_RANDOM, R.string.auto_wallpaper_order_random);

    companion object {
        fun fromPref(value: String?): AutoWallpaperOrder =
            entries.firstOrNull { it.prefValue == value } ?: SEQUENTIAL
    }
}
