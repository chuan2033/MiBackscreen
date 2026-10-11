package hook.HyperBackscreen.ui.battery

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
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
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import hook.HyperBackscreen.BuildConfig
import hook.HyperBackscreen.bridge.PrefsBridge
import hook.HyperBackscreen.common.Constants
import io.github.libxposed.api.XposedModule

object BatteryRingHost {
    private var ring: BatteryRingView? = null
    private var syncReceiver: BroadcastReceiver? = null
    private var syncReceiverActivity: Activity? = null

    @JvmStatic
    fun show(activity: Activity, module: XposedModule) {
        ensureSyncReceiver(activity)
        removeRing(activity)
        if (!PrefsBridge.shouldEnableBatteryRing(module)) return
        showEnabled(
            activity = activity,
            colors = {
                BatteryColorOverrides(
                    idle = PrefsBridge.readBatteryColor(module, Constants.KEY_BATTERY_COLOR_IDLE),
                    charging = PrefsBridge.readBatteryColor(module, Constants.KEY_BATTERY_COLOR_CHARGING),
                    low = PrefsBridge.readBatteryColor(module, Constants.KEY_BATTERY_COLOR_LOW),
                )
            },
            percentPosition = {
                BatteryPercentPosition.fromPref(PrefsBridge.readBatteryPercentPosition(module))
            },
        )
    }

    @JvmStatic
    fun sync(activity: Activity) {
        setEnabled(activity, PrefsBridge.readEnableBatteryRingForRemote())
    }

    @JvmStatic
    fun setEnabled(activity: Activity, enabled: Boolean) {
        ensureSyncReceiver(activity)
        removeRing(activity)
        PrefsBridge.setRemoteBooleanOverride(Constants.KEY_ENABLE_BATTERY_RING, enabled)
        if (!enabled) return
        showEnabled(
            activity = activity,
            colors = {
                BatteryColorOverrides(
                    idle = PrefsBridge.readBatteryColorForRemote(Constants.KEY_BATTERY_COLOR_IDLE),
                    charging = PrefsBridge.readBatteryColorForRemote(Constants.KEY_BATTERY_COLOR_CHARGING),
                    low = PrefsBridge.readBatteryColorForRemote(Constants.KEY_BATTERY_COLOR_LOW),
                )
            },
            percentPosition = {
                BatteryPercentPosition.fromPref(PrefsBridge.readBatteryPercentPositionForRemote())
            },
        )
    }

    private fun showEnabled(
        activity: Activity,
        colors: () -> BatteryColorOverrides,
        percentPosition: () -> BatteryPercentPosition,
    ) {
        val geometry = BatteryRingGeometry.forDevice(Build.DEVICE) ?: return
        // The contour is calibrated for the rear panel, not the front camera cutout.
        if (activity.display?.displayId != 1) return
        val parent = activity.window.decorView as? ViewGroup ?: return
        ring?.let { (it.parent as? ViewGroup)?.removeView(it) }
        // 覆盖色在挂载时读一次；改颜色后重新进入背屏桌面即可生效，绘制线程不再读偏好。
        val view = BatteryRingView(activity, geometry, colors(), percentPosition())
        ring = view
        parent.addView(view, ViewGroup.LayoutParams(-1, -1))
    }

    @JvmStatic
    fun release(activity: Activity) {
        removeRing(activity)
        if (syncReceiverActivity === activity) {
            syncReceiver?.let { runCatching { activity.unregisterReceiver(it) } }
            syncReceiver = null
            syncReceiverActivity = null
        }
    }

    private fun removeRing(activity: Activity) {
        val view = ring ?: return
        if (view.context !== activity) return
        (view.parent as? ViewGroup)?.removeView(view)
        ring = null
    }

    private fun ensureSyncReceiver(activity: Activity) {
        if (syncReceiverActivity === activity && syncReceiver != null) return
        syncReceiverActivity?.let { old ->
            syncReceiver?.let { receiver -> runCatching { old.unregisterReceiver(receiver) } }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Constants.ACTION_SYNC_BATTERY_RING) {
                    val key = intent.getStringExtra(Constants.EXTRA_PREFERENCE_KEY)
                    val value = intent.getBooleanExtra(Constants.EXTRA_PREFERENCE_VALUE, false)
                    if (key == Constants.KEY_ENABLE_BATTERY_RING) {
                        setEnabled(activity, value)
                    } else if (key == Constants.KEY_DISABLE_LONG_PRESS_EDIT) {
                        PrefsBridge.setRemoteBooleanOverride(key, value)
                    } else {
                        sync(activity)
                    }
                }
            }
        }
        runCatching {
            activity.registerReceiver(
                receiver,
                IntentFilter(Constants.ACTION_SYNC_BATTERY_RING),
                Context.RECEIVER_EXPORTED,
            )
            syncReceiver = receiver
            syncReceiverActivity = activity
        }
    }

    @JvmStatic
    fun previewForDebug(percent: Int, charging: Boolean): Boolean {
        if (!BuildConfig.DEBUG) return false
        val view = ring ?: return false
        view.preview = if (percent in 0..100) BatteryRingState(percent, charging) else null
        view.refresh()
        return true
    }
}

@SuppressLint("ViewConstructor", "DiscouragedApi")
private class BatteryRingView(
    context: Context,
    private val geometry: BatteryRingGeometry.Profile,
    private val overrides: BatteryColorOverrides,
    private val percentPosition: BatteryPercentPosition,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val contour = geometry.contourPath()
    private val measure = PathMeasure(contour, true)
    private val progress = Path()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 38f
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
        )
        if (next == state) return
        val previous = state
        state = next
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
        if (displayedPercent > 0f) {
            paint.color = color
            paint.strokeWidth = BatteryRingGeometry.STROKE_WIDTH
            if (displayedPercent >= 100f) {
                canvas.drawPath(contour, paint)
            } else {
                progress.reset()
                measure.getSegment(0f, measure.length * displayedPercent / 100f, progress, true)
                canvas.drawPath(progress, paint)
            }
        }
        geometry.percentTextAnchor(percentPosition)?.let { anchor ->
            textPaint.color = color
            val metrics = textPaint.fontMetrics
            val baseline = anchor.y - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText("${current.percent}%", anchor.x, baseline, textPaint)
        }
    }
}
