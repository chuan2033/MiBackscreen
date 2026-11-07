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
import android.view.animation.LinearInterpolator
import hook.HyperBackscreen.BuildConfig
import hook.HyperBackscreen.bridge.PrefsBridge
import hook.HyperBackscreen.common.Constants
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference

object BatteryRingHost {
    private var ringRef = WeakReference<BatteryRingView>(null)
    private var ring: BatteryRingView?
        get() = ringRef.get()
        set(value) {
            ringRef = WeakReference(value)
        }
    private var syncReceiver: BroadcastReceiver? = null
    /** 只弱引用：Activity 销毁时 release() 没被调用到，也不会被这里一直攥着。 */
    private var syncReceiverActivityRef = WeakReference<Activity>(null)
    private val syncReceiverActivity: Activity? get() = syncReceiverActivityRef.get()

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
            // 水平偏移的默认值随位置预设变，所以先把位置算出来再交给布局解析，只读一次偏好。
            layout = { position ->
                BatteryRingLayout.of(position) { key ->
                    PrefsBridge.readBatteryLayout(module, key)
                }
            },
        )
    }

    @JvmStatic
    fun sync(activity: Activity) {
        setEnabled(activity, PrefsBridge.readEnableBatteryRingForRemote())
    }

    @JvmStatic
    fun setEnabled(activity: Activity, enabled: Boolean) {
        // 面板保存回调可能在 Activity 已经暂停销毁之后才回来；这时再注册接收器、挂 View 就泄漏了。
        if (activity.isDestroyed || activity.isFinishing) {
            PrefsBridge.setRemoteBooleanOverride(Constants.KEY_ENABLE_BATTERY_RING, enabled)
            return
        }
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
            layout = { position ->
                BatteryRingLayout.of(position) { key ->
                    PrefsBridge.readBatteryLayoutForRemote(key)
                }
            },
        )
    }

    private fun showEnabled(
        activity: Activity,
        colors: () -> BatteryColorOverrides,
        percentPosition: () -> BatteryPercentPosition,
        layout: (BatteryPercentPosition) -> BatteryRingLayout,
    ) {
        val geometry = BatteryRingGeometry.forDevice(Build.DEVICE) ?: return
        // The contour is calibrated for the rear panel, not the front camera cutout.
        if (activity.display?.displayId != Constants.SYSTEM_SECONDARY_DISPLAY_ID) return
        val parent = activity.window.decorView as? ViewGroup ?: return
        ring?.let { (it.parent as? ViewGroup)?.removeView(it) }
        // 覆盖色在挂载时读一次；改颜色后重新进入背屏桌面即可生效，绘制线程不再读偏好。
        val position = percentPosition()
        val view = BatteryRingView(activity, geometry, colors(), position, layout(position))
        ring = view
        parent.addView(view, ViewGroup.LayoutParams(-1, -1))
    }

    @JvmStatic
    fun release(activity: Activity) {
        removeRing(activity)
        if (syncReceiverActivity === activity) {
            syncReceiver?.let { runCatching { activity.unregisterReceiver(it) } }
            syncReceiver = null
            syncReceiverActivityRef = WeakReference(null)
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
            // 只收持有模块签名权限的发送方（模块 App），第三方应用不能借这条广播改长按拦截 / 电量环。
            activity.registerReceiver(
                receiver,
                IntentFilter(Constants.ACTION_SYNC_BATTERY_RING),
                Constants.PERMISSION_SYNC_HOST,
                null,
                Context.RECEIVER_EXPORTED,
            )
            syncReceiver = receiver
            syncReceiverActivityRef = WeakReference(activity)
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
        val shouldRun = state?.charging == true && isAttachedToWindow && windowVisibility == VISIBLE
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
