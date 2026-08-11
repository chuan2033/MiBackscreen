package hook.HyperBackscreen.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.roundToInt

/**
 * Native View counterpart of the Miuix Compose switch used by the main app.
 *
 * Keeping this view framework-only is important because it is instantiated inside the hooked
 * rear-screen process, where a Compose owner and the module's Miuix theme are not available.
 */
internal class MiuixStyleSwitch(
    context: Context,
    private val dark: Boolean,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackBounds = RectF()
    private val trackWidth = dp(49f)
    private val trackHeight = dp(28f)
    private val thumbRadius = dp(10f)
    private val thumbStartCenter = dp(14f)
    private val thumbTravel = dp(21f)

    private val checkedTrackColor = Color.parseColor(if (dark) "#277AF7" else "#3482FF")
    private val uncheckedTrackColor = Color.parseColor(if (dark) "#505050" else "#E6E6E6")

    private var stateProgress = 0f
    private var thumbScale = 1f
    private var stateAnimator: ValueAnimator? = null
    private var pressAnimator: ValueAnimator? = null
    private var checkedChangeListener: ((Boolean) -> Unit)? = null

    var isChecked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            animateState(if (value) 1f else 0f)
            refreshDrawableState()
            checkedChangeListener?.invoke(value)
        }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setOnCheckedChangeListener(listener: ((Boolean) -> Unit)?) {
        checkedChangeListener = listener
    }

    fun toggle() {
        isChecked = !isChecked
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(trackWidth.roundToInt(), widthMeasureSpec),
            resolveSize(trackHeight.roundToInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = (width - trackWidth) / 2f
        val top = (height - trackHeight) / 2f
        trackBounds.set(left, top, left + trackWidth, top + trackHeight)

        paint.color = blendColor(uncheckedTrackColor, checkedTrackColor, stateProgress)
        canvas.drawRoundRect(trackBounds, trackHeight / 2f, trackHeight / 2f, paint)

        paint.color = Color.WHITE
        val centerX = left + thumbStartCenter + thumbTravel * stateProgress
        canvas.drawCircle(centerX, top + trackHeight / 2f, thumbRadius * thumbScale, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                animateThumbScale(1.127f, pressed = true)
                true
            }

            MotionEvent.ACTION_UP -> {
                val inside = event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()
                isPressed = false
                animateThumbScale(1f, pressed = false)
                if (inside) performClick()
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                animateThumbScale(1f, pressed = false)
                true
            }

            else -> true
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        toggle()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = isChecked
    }

    override fun onDetachedFromWindow() {
        stateAnimator?.cancel()
        pressAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun animateState(target: Float) {
        stateAnimator?.cancel()
        stateAnimator = ValueAnimator.ofFloat(stateProgress, target).apply {
            duration = 220L
            interpolator = OvershootInterpolator(0.55f)
            addUpdateListener {
                stateProgress = (it.animatedValue as Float).coerceIn(0f, 1f)
                invalidate()
            }
            start()
        }
    }

    private fun animateThumbScale(target: Float, pressed: Boolean) {
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(thumbScale, target).apply {
            duration = if (pressed) 90L else 180L
            interpolator = if (pressed) DecelerateInterpolator() else OvershootInterpolator(0.8f)
            addUpdateListener {
                thumbScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun blendColor(from: Int, to: Int, fraction: Float): Int {
        val amount = fraction.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * amount).roundToInt(),
            (Color.red(from) + (Color.red(to) - Color.red(from)) * amount).roundToInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * amount).roundToInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount).roundToInt(),
        )
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
