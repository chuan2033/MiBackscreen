package hook.HyperBackscreen.batteryring

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import android.os.BatteryManager
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator

@SuppressLint("ViewConstructor", "DiscouragedApi")
internal class BatteryRingView(
    context: Context,
    private val geometry: BatteryRingGeometry.Profile,
    private val overrides: BatteryColorOverrides,
    private val percentPosition: BatteryPercentPosition,
    private val layout: BatteryRingLayout,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // 绘制轨道按样式定：连体轮廓一条，上下双环两条。各段长度只量一次。
    private val ringPaths = geometry.ringPaths(layout.ringStyle, layout.ringScalePercent)
    private val ringMeasures = ringPaths.map { PathMeasure(it, true) }
    private val ringLengths = ringMeasures.map { it.length }
    private val progress = Path()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        // 字重按偏好取（100~900）。旧版本固定 BOLD，默认值仍是 700，升级后观感不变。
        typeface = Typeface.create(Typeface.DEFAULT, layout.percentTextWeight, false)
        textSize = layout.percentTextSize.toFloat()
        setShadowLayer(4f, 0f, 1.5f, 0x99000000.toInt())
    }
    private val systemUiResources = runCatching {
        context.packageManager.getResourcesForApplication("com.android.systemui")
    }.getOrNull()
    private val colors = mutableMapOf<String, Int>()
    private var state: BatteryRingState? = null
    private var displayedPercent = 0f
    private var animator: ValueAnimator? = null
    private var registered = false
    /** 充电流光：只在充电且背屏可见时跑，停充 / 熄屏 / 卸载即停。 */
    private var sweepAnimator: ValueAnimator? = null
    private var sweepPhase = 0f
    private val sweepPath = Path()
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    var preview: BatteryRingState? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { update(intent) }
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        runCatching {
            val battery = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                Context.RECEIVER_EXPORTED)
            registered = true
            battery?.let(::update)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        stopSweep()
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            animator?.cancel()
            displayedPercent = state?.percent?.toFloat() ?: 0f
        }
        updateSweep()
    }

    fun refresh() {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        battery?.let(::update)
    }

    private fun update(intent: Intent) {
        val next = preview ?: BatteryRingState.from(
            intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
            intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
            layout.lowThresholdPercent,
        )
        if (next == state) return
        val previous = state
        state = next
        updateSweep()
        animator?.cancel()
        if (next == null) {
            invalidate()
            return
        }
        if (previous == null || previous.percent == next.percent
            || windowVisibility != VISIBLE || preview != null) {
            displayedPercent = next.percent.toFloat()
        } else if (previous.percent != next.percent) {
            animator = ValueAnimator.ofFloat(displayedPercent, next.percent.toFloat()).apply {
                duration = 450
                interpolator = DecelerateInterpolator()
                addUpdateListener { displayedPercent = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        invalidate()
    }

    private fun updateSweep() {
        val shouldRun = layout.chargeSweepEnabled && state?.charging == true &&
            isAttachedToWindow && windowVisibility == VISIBLE
        if (shouldRun && sweepAnimator == null) {
            sweepAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = CHARGE_SWEEP_DURATION_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { sweepPhase = it.animatedValue as Float; invalidate() }
                start()
            }
        } else if (!shouldRun) {
            stopSweep()
        }
    }

    private fun stopSweep() {
        sweepAnimator?.cancel()
        sweepAnimator = null
        invalidate()
    }

    private fun drawSweep(canvas: Canvas, color: Int) {
        val stops = if (displayedPercent >= 100f) ringLengths else ringProgressStops(displayedPercent, ringLengths)
        val pieces = chargeSweepSegments(sweepPhase, stops)
        if (pieces.isEmpty()) return
        sweepPath.reset()
        pieces.forEach { (index, from, to) -> ringMeasures[index].getSegment(from, to, sweepPath, true) }
        sweepPaint.color = chargeSweepColor(color)
        sweepPaint.strokeWidth = layout.ringStrokeWidth.toFloat()
        canvas.drawPath(sweepPath, sweepPaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = state ?: return
        // A rotated or inset window must not place a calibrated ring over unrelated content.
        if (!geometry.matchesSize(width, height)) return
        // 用户覆盖色优先；未覆盖时沿用 SystemUI 电池资源色，取不到再回落默认三色。
        val color = overrides.overrideFor(current) ?: colors.getOrPut(current.colorRole) {
            runCatching {
                val resources = systemUiResources ?: return@getOrPut current.fallbackColor
                val id = resources.getIdentifier(current.colorRole, "color", "com.android.systemui")
                if (id == 0) current.fallbackColor else resources.getColor(id, null)
            }.getOrDefault(current.fallbackColor)
        }
        // 底轨先铺满整圈，已充部分随后盖在上面。放在电量判断之外：0% 时更要画，
        // 否则电量见底反而整圈消失，看起来像功能坏了。
        if (layout.ringTrackVisible) {
            paint.color = trackColorOf(color)
            paint.strokeWidth = layout.ringStrokeWidth.toFloat()
            ringPaths.forEach { canvas.drawPath(it, paint) }
        }
        if (displayedPercent > 0f) {
            paint.color = color
            paint.strokeWidth = layout.ringStrokeWidth.toFloat()
            if (displayedPercent >= 100f) {
                // 满电直接画整条轨道：省掉分段拼接，闭合处也不会留一道接缝。
                ringPaths.forEach { canvas.drawPath(it, paint) }
            } else {
                progress.reset()
                // 双环时进度按各段长度分配：下环绕满才从上环起笔。
                val stops = ringProgressStops(displayedPercent, ringLengths)
                ringMeasures.forEachIndexed { index, measure ->
                    val stop = stops[index]
                    if (stop > 0f) measure.getSegment(0f, stop, progress, true)
                }
                canvas.drawPath(progress, paint)
            }
            if (current.charging && sweepAnimator != null) drawSweep(canvas, color)
        }
        geometry.percentTextAnchor(
            percentPosition,
            layout.percentOffsetXPercent,
            layout.percentOffsetYPercent,
        )?.let { anchor ->
            textPaint.color = color
            val metrics = textPaint.fontMetrics
            val baseline = anchor.y - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText("${current.percent}%", anchor.x, baseline, textPaint)
        }
    }
}
