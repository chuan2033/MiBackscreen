package hook.HyperBackscreen.ui.battery

import hook.HyperBackscreen.common.Constants

/**
 * 背屏电量显示的位置 / 尺寸微调。
 *
 * 六项都以字符串随偏好保存（沿用颜色、位置那条字符串通道，不需要额外的数值读写），
 * 键名见 [Constants] 里的 `KEY_BATTERY_*` 系列。[entries] 是唯一的落盘口径、[of] 是唯一的
 * 读取口径，两边共用同一份键清单，加项时不会漏掉一边。
 * 和颜色一样**在进入背屏桌面挂载时读一次**，改完重新进入背屏桌面即生效。
 */
internal data class BatteryRingLayout(
    /** 百分比数字水平偏移，单位：摄像头模组宽度百分比。 */
    val percentOffsetXPercent: Int,
    /** 百分比数字垂直偏移，单位：摄像头模组高度百分比。 */
    val percentOffsetYPercent: Int,
    /** 电量线条整体缩放，单位：百分比。100 表示严格贴模组轮廓。 */
    val ringScalePercent: Int,
    /** 电量线条笔画粗细，单位：背屏像素（背屏按参考尺寸绘制，不用折算）。 */
    val ringStrokeWidth: Int,
    /** 百分比数字字号，单位：背屏像素。 */
    val percentTextSize: Int,
    /** 百分比数字字重，100~900；400 常规、700 加粗。 */
    val percentTextWeight: Int,
    /** 是否在未充部分画一圈底轨。 */
    val ringTrackVisible: Boolean,
    /** 线条样式：连体轮廓 / 上下双环。 */
    val ringStyle: BatteryRingStyle,
) {
    /**
     * 逐项落盘的键值对，[of] 的反向操作。
     *
     * 键顺序与 [of] 的读取顺序一致，两边一起看不会错行。[position] 决定水平偏移落到哪一侧的键。
     */
    fun entries(position: BatteryPercentPosition): List<Pair<String, String>> = listOf(
        percentOffsetXKey(position) to encode(percentOffsetXPercent),
        Constants.KEY_BATTERY_PERCENT_OFFSET_Y to encode(percentOffsetYPercent),
        Constants.KEY_BATTERY_RING_SCALE to encode(ringScalePercent),
        Constants.KEY_BATTERY_RING_STROKE to encode(ringStrokeWidth),
        Constants.KEY_BATTERY_PERCENT_TEXT_SIZE to encode(percentTextSize),
        Constants.KEY_BATTERY_PERCENT_TEXT_WEIGHT to encode(percentTextWeight),
        Constants.KEY_BATTERY_RING_TRACK to encode(ringTrackVisible),
        Constants.KEY_BATTERY_RING_STYLE to ringStyle.prefValue,
    )

    companion object {
        const val PERCENT_OFFSET_MIN = -50
        const val PERCENT_OFFSET_MAX = 50
        const val RING_SCALE_MIN = 80
        const val RING_SCALE_MAX = 115
        const val RING_STROKE_MIN = 4
        const val RING_STROKE_MAX = 20
        const val PERCENT_TEXT_SIZE_MIN = 24
        const val PERCENT_TEXT_SIZE_MAX = 56

        /**
         * 数字字重的可调区间与档位。
         *
         * 按 100 一档量化：系统字体是可变字重，但相邻档位之间的差异肉眼几乎看不出来，
         * 留给用户连续拖动只会在 537 这种数值上纠结，实际渲染还可能被就近匹配掉。
         */
        const val PERCENT_TEXT_WEIGHT_MIN = 300
        const val PERCENT_TEXT_WEIGHT_MAX = 900
        const val PERCENT_TEXT_WEIGHT_STEP = 100

        /**
         * 底轨的透明度 = 线条颜色的透明度乘这个系数。
         *
         * 底轨不单独占一个颜色槽：它表达的是「这段线还没充到」，用线条自己的颜色压暗最自然，
         * 换颜色时底轨跟着走，也不用多出一套色槽让用户在颜色页里再选一遍。
         */
        const val RING_TRACK_ALPHA_SCALE = 0.25f

        /** 垂直偏移、线条缩放、线条粗细、数字字号的默认值，所有位置预设共用。 */
        const val DEFAULT_PERCENT_OFFSET_Y = 0
        const val DEFAULT_RING_SCALE = 100
        const val DEFAULT_RING_STROKE = 10
        const val DEFAULT_PERCENT_TEXT_SIZE = 38
        /** 与旧版本固定 BOLD 一致，升级后观感不变。 */
        const val DEFAULT_PERCENT_TEXT_WEIGHT = 700
        const val DEFAULT_RING_TRACK = false

        private const val TRACK_ON = "1"
        private const val TRACK_OFF = "0"

        /**
         * 水平偏移的允许区间，也跟着位置预设走。
         *
         * 数字贴在左边时只能在 `-20% ~ 0` 之间微调，贴在右边时 `0 ~ +20%`——模组两侧留给
         * 数字的余量本来就窄，放开到 ±50% 会直接压到摄像头模组上，所以两侧各只给一段。
         * 「不显示」时数字不画，区间取两者的并集，只是为了让已存的旧值不会被夹变形。
         */
        fun percentOffsetXRange(position: BatteryPercentPosition): IntRange = when (position) {
            BatteryPercentPosition.LEFT -> -20..0
            BatteryPercentPosition.RIGHT -> 0..20
            BatteryPercentPosition.OFF -> -20..20
        }

        /**
         * 水平偏移当前的落盘键：左右各自一个槽，切位置不会互相覆盖。
         *
         * 「不显示」时数字不画，没有可归属的一侧，落到右侧键上——与「关掉再打开回到右侧」
         * 的默认行为一致，也让键集合不随位置变化。
         */
        fun percentOffsetXKey(position: BatteryPercentPosition): String = when (position) {
            BatteryPercentPosition.LEFT -> Constants.KEY_BATTERY_PERCENT_OFFSET_X_LEFT
            BatteryPercentPosition.RIGHT,
            BatteryPercentPosition.OFF -> Constants.KEY_BATTERY_PERCENT_OFFSET_X_RIGHT
        }

        /**
         * 某个位置预设下的水平偏移：读该侧自己的槽，空值 / 脏值回落到该侧默认值，再夹进该侧区间。
         *
         * 该侧槽为空时再看一眼历史单槽键：老版本只存了一个槽，值是用户在某一侧调过的，
         * 按当前位置的区间夹取后当作该侧初值，免得升级后又要重调一遍。
         */
        fun percentOffsetX(position: BatteryPercentPosition, read: (String) -> String?): Int {
            val range = percentOffsetXRange(position)
            val own = read(percentOffsetXKey(position))?.trim()?.toIntOrNull()
            val legacy = read(Constants.KEY_BATTERY_PERCENT_OFFSET_X)?.trim()?.toIntOrNull()
            val value = own ?: legacy ?: defaultPercentOffsetX(position)
            return value.coerceIn(range.first, range.last)
        }

        /**
         * 水平偏移的默认值：左侧 -10%、右侧 +10%，这是实机确认过的「数字贴住摄像头模组」
         * 位置。区间上限放到 ±20% 是把余量留给用户自己往外推，默认值不动，免得新装用户
         * 一上来数字就压到模组上。两侧给数字留的空间不一样，各自贴一个默认位置才是常态，
         * 所以这里不是全局一个值。
         */
        fun defaultPercentOffsetX(position: BatteryPercentPosition): Int = when (position) {
            BatteryPercentPosition.LEFT -> -10
            BatteryPercentPosition.RIGHT -> 10
            BatteryPercentPosition.OFF -> 0
        }

        /** 某个位置预设下的完整默认布局：滑杆、输入弹窗、恢复默认都从这里取默认值，口径一致。 */
        fun defaultFor(position: BatteryPercentPosition): BatteryRingLayout = BatteryRingLayout(
            percentOffsetXPercent = defaultPercentOffsetX(position),
            percentOffsetYPercent = DEFAULT_PERCENT_OFFSET_Y,
            ringScalePercent = DEFAULT_RING_SCALE,
            ringStrokeWidth = DEFAULT_RING_STROKE,
            percentTextSize = DEFAULT_PERCENT_TEXT_SIZE,
            percentTextWeight = DEFAULT_PERCENT_TEXT_WEIGHT,
            ringTrackVisible = DEFAULT_RING_TRACK,
            ringStyle = BatteryRingStyle.DEFAULT,
        )

        /**
         * 从偏好解析。[read] 收偏好键、返回原始字符串（可以缺省），解析与兜底都在这里做：
         * 空值、非数字、越界一律收进合法区间，避免脏偏好把环缩到看不见或把数字推到屏幕外。
         *
         * [position] 参与水平偏移的读取：左右各有自己的槽与区间，偏好为空代表该侧没调过，
         * 按该侧默认值来；有值就原样采纳，只是夹进该侧的区间里，免得旧版本存下的 ±50 溢出。
         */
        fun of(
            position: BatteryPercentPosition,
            read: (String) -> String?,
        ): BatteryRingLayout {
            return BatteryRingLayout(
                percentOffsetXPercent = percentOffsetX(position, read),
                percentOffsetYPercent = clamp(
                    read(Constants.KEY_BATTERY_PERCENT_OFFSET_Y),
                    PERCENT_OFFSET_MIN,
                    PERCENT_OFFSET_MAX,
                    DEFAULT_PERCENT_OFFSET_Y),
                ringScalePercent = clamp(
                    read(Constants.KEY_BATTERY_RING_SCALE),
                    RING_SCALE_MIN,
                    RING_SCALE_MAX,
                    DEFAULT_RING_SCALE),
                ringStrokeWidth = clamp(
                    read(Constants.KEY_BATTERY_RING_STROKE),
                    RING_STROKE_MIN,
                    RING_STROKE_MAX,
                    DEFAULT_RING_STROKE),
                percentTextSize = clamp(
                    read(Constants.KEY_BATTERY_PERCENT_TEXT_SIZE),
                    PERCENT_TEXT_SIZE_MIN,
                    PERCENT_TEXT_SIZE_MAX,
                    DEFAULT_PERCENT_TEXT_SIZE),
                // 旧版本没有这个键，读到的空值落在 700（原固定 BOLD），升级后观感不变。
                percentTextWeight = quantizeWeight(
                    clamp(
                        read(Constants.KEY_BATTERY_PERCENT_TEXT_WEIGHT),
                        PERCENT_TEXT_WEIGHT_MIN,
                        PERCENT_TEXT_WEIGHT_MAX,
                        DEFAULT_PERCENT_TEXT_WEIGHT)),
                // 只有明确的 "1" 才算开：旧版本没有这个键，读到的空串要落在「关」上，
                // 否则升级后每台机器的背屏都会凭空多出一圈底轨。
                ringTrackVisible = read(Constants.KEY_BATTERY_RING_TRACK)?.trim() == TRACK_ON,
                // 旧版本没有这个键，读到的空值落在默认的连体轮廓上，升级后观感不变。
                ringStyle = BatteryRingStyle.fromPref(
                    read(Constants.KEY_BATTERY_RING_STYLE)),
            )
        }

        /**
         * 把任意字重对齐到最近的档位并夹进可调区间。
         *
         * 滑杆给的是连续值，这里统一收口：读偏好、写偏好、恢复默认都走它，
         * 免得存下 537 这种数值、翻到设置页再显示成一个不像字的数字。
         */
        fun quantizeWeight(weight: Int): Int {
            val stepped = ((weight + PERCENT_TEXT_WEIGHT_STEP / 2) / PERCENT_TEXT_WEIGHT_STEP) *
                PERCENT_TEXT_WEIGHT_STEP
            return stepped.coerceIn(PERCENT_TEXT_WEIGHT_MIN, PERCENT_TEXT_WEIGHT_MAX)
        }

        fun encode(value: Int): String = value.toString()

        fun encode(visible: Boolean): String = if (visible) TRACK_ON else TRACK_OFF

        private fun clamp(raw: String?, min: Int, max: Int, fallback: Int): Int =
            (raw?.trim()?.toIntOrNull() ?: fallback).coerceIn(min, max)
    }
}
