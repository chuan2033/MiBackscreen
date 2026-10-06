package hook.HyperBackscreen.ui.battery

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs

/**
 * 背屏电量轮廓几何。
 *
 * 绘制端（[BatteryRingHost]）和设置页预览共用同一份设备 Profile，预览和真实背屏保持一致。
 */
internal object BatteryRingGeometry {
    const val STROKE_WIDTH = 10f

    private const val POPSICLE_DEVICE = "popsicle"
    private const val PANDORA_DEVICE = "pandora"

    private const val Q200_WIDTH = 904f
    private const val Q200_HEIGHT = 572f
    private const val Q200_CAMERA_WIDTH_RATIO = 0.3086f
    private const val Q200_SHAPE_VIEWPORT_WIDTH = 104f
    private const val Q200_SHAPE_VIEWPORT_HEIGHT = 213f
    private const val Q200_SHAPE_TOP_CENTER_Y = 52f
    private const val Q200_SHAPE_BOTTOM_CENTER_Y = 161f
    private const val Q200_PANEL_CORNER_RADIUS = 97f

    val popsicle: Profile = Profile(
        device = POPSICLE_DEVICE,
        referenceWidth = 976f,
        referenceHeight = 596f,
        panelCornerRadius = 101f,
        moduleBoundsProvider = { RectF(7.5f, 8f, 288.5f, 588f) },
        contourPathProvider = {
            Path().apply {
                moveTo(148f, 588f)
                arcTo(RectF(7.5f, 306f, 288.5f, 588f), 90f, -90f)
                lineTo(288.5f, 149f)
                arcTo(RectF(7.5f, 8f, 288.5f, 290f), 0f, -180f)
                lineTo(7.5f, 447f)
                arcTo(RectF(7.5f, 306f, 288.5f, 588f), 180f, -90f)
                close()
            }
        },
        previewLensSize = 272,
        previewLensX = 12,
        previewTopLensY = 12,
        previewBottomLensY = 308,
    )

    val pandora: Profile = run {
        val cameraWidth = Q200_WIDTH * Q200_CAMERA_WIDTH_RATIO
        val scale = cameraWidth / Q200_SHAPE_VIEWPORT_WIDTH
        val shapeHeight = Q200_SHAPE_VIEWPORT_HEIGHT * scale
        val top = ((Q200_HEIGHT - shapeHeight) / 2f).coerceAtLeast(0f)
        val left = 7f
        val right = left + cameraWidth
        val radius = cameraWidth / 2f
        val centerX = left + radius
        val topCenterY = top + Q200_SHAPE_TOP_CENTER_Y * scale
        val bottomCenterY = top + Q200_SHAPE_BOTTOM_CENTER_Y * scale
        val topRect = RectF(left, topCenterY - radius, right, topCenterY + radius)
        val bottomRect = RectF(left, bottomCenterY - radius, right, bottomCenterY + radius)
        Profile(
            device = PANDORA_DEVICE,
            referenceWidth = Q200_WIDTH,
            referenceHeight = Q200_HEIGHT,
            panelCornerRadius = Q200_PANEL_CORNER_RADIUS,
            moduleBoundsProvider = { RectF(left, topRect.top, right, bottomRect.bottom) },
            contourPathProvider = {
                Path().apply {
                    moveTo(centerX, bottomRect.bottom)
                    arcTo(bottomRect, 90f, -90f)
                    lineTo(right, topCenterY)
                    arcTo(topRect, 0f, -180f)
                    lineTo(left, bottomCenterY)
                    arcTo(bottomRect, 180f, -90f)
                    close()
                }
            },
            previewLensSize = 269,
            previewLensX = (left + STROKE_WIDTH / 2f).rounded(),
            previewTopLensY = (topRect.top + STROKE_WIDTH / 2f).rounded(),
            previewBottomLensY = (bottomRect.top + STROKE_WIDTH / 2f).rounded(),
        )
    }

    private val profiles = listOf(popsicle, pandora)

    fun forDevice(device: String?): Profile? = profiles.firstOrNull { it.device == device }

    fun isCalibratedDevice(device: String?): Boolean = forDevice(device) != null

    private fun Float.rounded(): Int = (this + if (this >= 0f) 0.5f else -0.5f).toInt()

    internal data class Profile(
        val device: String,
        val referenceWidth: Float,
        val referenceHeight: Float,
        val panelCornerRadius: Float,
        private val moduleBoundsProvider: () -> RectF,
        private val contourPathProvider: () -> Path,
        val previewLensSize: Int,
        val previewLensX: Int,
        val previewTopLensY: Int,
        val previewBottomLensY: Int,
    ) {
        fun contourPath(): Path = contourPathProvider()

        fun moduleBounds(): RectF = moduleBoundsProvider()

        fun matchesSize(width: Int, height: Int): Boolean {
            return abs(width - referenceWidth.toInt()) <= 1 &&
                abs(height - referenceHeight.toInt()) <= 1
        }
    }
}
