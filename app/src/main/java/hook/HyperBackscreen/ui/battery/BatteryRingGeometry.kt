package hook.HyperBackscreen.ui.battery

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs

/**
 * 背屏电量轮廓几何。
 *
 * 绘制端（[BatteryRingHost]）和设置页预览共用同一份设备 Profile，预览和真实背屏保持一致。
 */
internal object BatteryRingGeometry {
    /**
     * 预览里镜头相对轮廓内缩的距离。
     *
     * 纯构图用的常量，和用户可调的笔画粗细无关——镜头该摆在哪由面板几何决定，
     * 不该跟着线条粗细一起动。
     */
    private const val PREVIEW_LENS_INSET = 5f

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
        // 双环样式用的两个独立圆：上下各一个，直径同模组宽度。顺序是 [下环, 上环]。
        twinRingRectsProvider = {
            listOf(
                RectF(7.5f, 306f, 288.5f, 588f),
                RectF(7.5f, 8f, 288.5f, 290f),
            )
        },
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
            previewLensX = (left + PREVIEW_LENS_INSET).rounded(),
            previewTopLensY = (topRect.top + PREVIEW_LENS_INSET).rounded(),
            previewBottomLensY = (bottomRect.top + PREVIEW_LENS_INSET).rounded(),
            twinRingRectsProvider = { listOf(RectF(bottomRect), RectF(topRect)) },
        )
    }

    private val profiles = listOf(popsicle, pandora)

    fun forDevice(device: String?): Profile? = profiles.firstOrNull { it.device == device }

    fun isCalibratedDevice(device: String?): Boolean = forDevice(device) != null

    /**
     * 圆环的起笔角度：正下方 90°（屏幕坐标 y 轴向下）。
     *
     * 与连体轮廓共用同一个起点，两种样式切换时电量的起始位置不会跳。
     */
    private const val RING_START_ANGLE = 90f

    /**
     * 单个圆环：从正下方起笔，沿与连体轮廓一致的绕行方向走满一圈。
     *
     * 拆成两段半圆而不是一次 `-360°`：单段整圈弧在部分机型上量出来的长度不稳。
     */
    private fun ringPath(rect: RectF): Path = Path().apply {
        arcTo(rect, RING_START_ANGLE, -180f)
        arcTo(rect, RING_START_ANGLE - 180f, -180f)
        close()
    }

    private fun Float.rounded(): Int = (this + if (this >= 0f) 0.5f else -0.5f).toInt()

    internal data class Profile(
        val device: String,
        val referenceWidth: Float,
        val referenceHeight: Float,
        val panelCornerRadius: Float,
        private val moduleBoundsProvider: () -> RectF,
        private val contourPathProvider: () -> Path,
        /** 双环样式用的两个独立圆，顺序是 [下环, 上环]，也就是电量走过的先后。 */
        private val twinRingRectsProvider: () -> List<RectF>,
        val previewLensSize: Int,
        val previewLensX: Int,
        val previewTopLensY: Int,
        val previewBottomLensY: Int,
    ) {
        fun contourPath(): Path = contourPathProvider()

        /**
         * 按 [scalePercent] 绕模组中心整体缩放轮廓。
         *
         * 用等比缩放而不是逐边内缩：模组是细长形，等比缩放能在四条边上都产生位移，
         * 而且圆角保持为正圆，不会像非等比缩放那样把模组两端的弧拉成椭圆。
         */
        fun contourPath(scalePercent: Int): Path =
            scaleAboutCenter(contourPathProvider(), scalePercent)

        /**
         * 电量线的绘制轨道：按样式给一到多条**闭合**路径。
         *
         * 连体轮廓是一条绕模组一圈的路径；上下双环是两条各自闭合的圆，列表顺序就是电量
         * 走过的顺序 —— 先绕满下环，再从上环起笔。绘制端按各段实际长度分配进度
         * （见 [ringProgressStops]），所以两圈不等长也没关系。
         */
        fun ringPaths(style: BatteryRingStyle, scalePercent: Int): List<Path> = when (style) {
            BatteryRingStyle.CONTOUR -> listOf(contourPath(scalePercent))
            BatteryRingStyle.TWIN -> twinRingRectsProvider().map { rect ->
                scaleAboutCenter(ringPath(rect), scalePercent)
            }
        }

        /** 按 [scalePercent] 绕模组中心整体缩放单条路径。 */
        private fun scaleAboutCenter(path: Path, scalePercent: Int): Path {
            if (scalePercent == 100) return path
            val bounds = moduleBounds()
            val factor = scalePercent / 100f
            path.transform(
                Matrix().apply { setScale(factor, factor, bounds.centerX(), bounds.centerY()) }
            )
            return path
        }

        fun moduleBounds(): RectF = moduleBoundsProvider()

        /**
         * 百分比数字的锚点：预设左 / 右位置，再叠加用户微调。
         * 偏移以模组尺寸为基准，所以换机型时观感一致。
         */
        fun percentTextAnchor(
            position: BatteryPercentPosition,
            offsetXPercent: Int = 0,
            offsetYPercent: Int = 0,
        ): PointF? {
            if (position == BatteryPercentPosition.OFF) return null
            val bounds = moduleBounds()
            val preset = bounds.width() * 0.22f
            val x = bounds.centerX() +
                if (position == BatteryPercentPosition.LEFT) -preset else preset
            return PointF(
                x + bounds.width() * offsetXPercent / 100f,
                bounds.centerY() + bounds.height() * offsetYPercent / 100f,
            )
        }

        fun matchesSize(width: Int, height: Int): Boolean {
            return abs(width - referenceWidth.toInt()) <= 1 &&
                abs(height - referenceHeight.toInt()) <= 1
        }
    }
}

/**
 * 把总进度按各段长度切成每段要画的长度。
 *
 * 单条轨道时结果就是「总长 × 百分比」；双环时前一段不满则后面一律是 0，
 * 前一段满了才从下一段起笔，于是 50% 正好是下环闭合、51% 从上环起一小段。
 *
 * 返回的每一项是对应段 `PathMeasure` 的 stop 值，0 表示这段不画。
 * 满电直接原样返回各段长度，避免浮点累加让最后一圈差一丝没闭上。
 */
internal fun ringProgressStops(percent: Float, lengths: List<Float>): List<Float> {
    if (lengths.isEmpty()) return emptyList()
    if (percent >= 100f) return lengths
    if (percent <= 0f) return List(lengths.size) { 0f }
    var remaining = lengths.sum() * (percent / 100f)
    return lengths.map { length ->
        val take = remaining.coerceIn(0f, length)
        remaining -= take
        take
    }
}
