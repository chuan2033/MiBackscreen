package hook.HyperBackscreen.ui.battery

import androidx.annotation.StringRes
import hook.HyperBackscreen.R

/**
 * 电量线条的样式。
 *
 * [CONTOUR] 是绕摄像头模组一整圈的连体轮廓；[TWIN] 拆成上下两个独立圆环，
 * 电量先绕满下环（0~50%）再接着绕上环（50~100%），100% 时两圈都闭上。
 *
 * 两种样式共用同一套尺寸、颜色与数字参数，只换绘制轨道，切换不动其它偏好。
 */
internal enum class BatteryRingStyle(
    val prefValue: String,
    @get:StringRes val labelRes: Int,
) {
    CONTOUR("contour", R.string.battery_ring_style_contour),
    TWIN("twin", R.string.battery_ring_style_twin);

    companion object {
        /** 默认样式：升级上来的机器沿用原来的连体轮廓，观感不变。 */
        val DEFAULT = CONTOUR

        fun fromPref(value: String?): BatteryRingStyle =
            entries.firstOrNull { it.prefValue == value } ?: DEFAULT
    }
}
