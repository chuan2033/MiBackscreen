package hook.HyperBackscreen.ui

import android.view.VelocityTracker
import hook.HyperBackscreen.ui.battery.BatteryRingStyle
import hook.HyperBackscreen.hook.PanelAppCard
import hook.HyperBackscreen.hook.AutoWallpaperRotationHost
import android.widget.ImageView
import android.view.accessibility.AccessibilityNodeInfo
import android.view.HapticFeedbackConstants
import android.hardware.display.DisplayManager
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Bitmap
import android.content.Intent
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import hook.HyperBackscreen.BuildConfig
import hook.HyperBackscreen.R
import hook.HyperBackscreen.bridge.PrefsBridge
import hook.HyperBackscreen.bridge.DiagnosticLogStore
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.common.BackgroundTasks
import hook.HyperBackscreen.ui.battery.BatteryRingGeometry
import hook.HyperBackscreen.ui.battery.BatteryRingHost
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 注入背屏 Activity 的原生快捷面板。
 *
 * 目标设备副屏为 976x596，左侧 0..296px 是贯穿全高的摄像头挖孔区。面板背景覆盖整块
 * 背屏，但文字、开关等内容从 DisplayCutout 安全区之后开始；若 ROM 没有及时返回 Insets，
 * 则在该尺寸副屏上强制从 x=298px 开始。
 */
object SwipePanelHost {
    private val mainHandler = Handler(Looper.getMainLooper())
    private const val TAG = Constants.LOG_TAG
    private const val KNOWN_BACKSCREEN_SAFE_LEFT_PX = 298
    private const val DISMISS_ANIMATION_MS = 340L
    private const val EXPAND_ANIMATION_MS = 340L
    private const val CARD_DISMISS_MIN_SCALE = 0.8f
    /** 面板展开后的圆角；展开/缩回时在它与胶囊圆角之间过渡。 */
    /** 底部这条窄区域上的上滑用来关闭面板，并从系统手势区里排除。 */
    private const val BOTTOM_SWIPE_REGION_RATIO = 0.78f
    private const val ROW_TITLE_TEXT_SP = 16f
    private const val HEADER_TEXT_SP = 15f
    private const val TILE_TITLE_TEXT_SP = 14f
    private const val TILE_VALUE_TEXT_SP = 12f
    private const val TILE_PRESSED_SCALE = 0.96f
    private const val STATUS_VISIBLE_MS = 3000L
    /** 卡片开 / 关、换档时颜色与文字的过渡时长。 */
    private const val TILE_TRANSITION_MS = 150L
    /** 上滑关闭：拖动距离折算成缩放进度的分母（相对面板高度）。 */
    private const val DISMISS_DRAG_RANGE = 0.36f
    /** 松手速度超过这个值（向上）时，即使距离不够也直接关闭。 */
    private const val DISMISS_FLING_DP_PER_SECOND = 600
    /** 带速度收尾时动画的最短时长，避免一甩就闪没。 */
    private const val DISMISS_FLING_MIN_MS = 120L
    private const val MODULE_TITLE = "MiBackscreen"

    @Volatile
    private var loadedModule = ModuleUpdateState(null, BuildConfig.VERSION_CODE.toLong())

    @JvmStatic
    fun setLoadedModuleApkPath(apkPath: String?) {
        // Capture the framework's loaded APK before any panel is opened, not the latest installed APK.
        loadedModule = ModuleUpdateState(apkPath, BuildConfig.VERSION_CODE.toLong())
    }

    private var container: FrameLayout? = null
    private var panel: SwipeDismissCard? = null
    private var originRect: FloatArray? = null
    private var hostActivity = WeakReference<Activity>(null)
    private var exclusionView = WeakReference<View>(null)
    private var originalExclusions: List<Rect>? = null
    private val visiblePanelBounds = Rect()
    private var dismissing = false
    private var showGeneration = 0L
    private var launcherGestureFromBottom = false
    private var launcherGestureDraggingUp = false
    private var launcherGestureDownX = 0f
    private var launcherGestureDownY = 0f
    private var launcherGestureDismissDistance = 0f
    private var launcherTouchDownX = 0f
    private var launcherTouchDownY = 0f
    private var launcherSwipeClickSuppressUntil = 0L
    private val cardInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    /**
     * 从背屏卡片入口打开面板：对齐系统卡片的展开方式，从被点击的那张卡片位置放大到整块面板。
     */
    @JvmStatic
    fun show(activity: Activity, origin: View?) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (isShowing()) return

        removePanel(container)
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val generation = ++showGeneration
        hostActivity = WeakReference(activity)

        decor.post {
            if (generation != showGeneration || hostActivity.get() !== activity) {
                return@post
            }
            if (activity.isFinishing || activity.isDestroyed) {
                clearReferences()
                return@post
            }
            val built = attachPanel(activity, decor)
            expandFromOriginatingCard(built, origin)
            Log.d(TAG, "Panel opened from card entry")
            DiagnosticLogStore.recordRemoteAsync(activity, "quick_panel opened source=app_card")
        }
    }

    private fun reattachPanelToVisibleHostLayer(root: FrameLayout, decor: ViewGroup) {
        val hostLayer = findHostLauncherContainer(decor)
        val target = if (hostLayer != null && hostLayer.visibility == View.VISIBLE) hostLayer else decor
        if (root.parent === target) return
        (root.parent as? ViewGroup)?.removeView(root)
        target.addView(root)
        Log.d(TAG, "Panel reattached for fallback expand")
    }

    /** 把整块面板缩到 origin 卡片的屏幕矩形再放大回来；取不到 origin 时退化成上滑入场。 */
    private fun expandFromOriginatingCard(built: BuiltPanel, origin: View?) {
        val card = built.card
        val root = built.root
        val metrics = root.resources.displayMetrics
        val width = card.width.takeIf { it > 0 } ?: metrics.widthPixels
        val height = card.height.takeIf { it > 0 } ?: metrics.heightPixels
        if (width <= 0 || height <= 0) return

        val rect = originRect(root, origin)
        if (rect == null) {
            Log.d(TAG, "Panel expand fallback: origin unavailable")
            originRect = null
            slideInFromBottom(card)
            return
        }

        Log.d(TAG, "Panel expand from " + rect[0] + "," + rect[1]
                + " " + rect[2] + "x" + rect[3] + " into " + width + "x" + height)
        originRect = rect
        card.pivotX = 0f
        card.pivotY = 0f
        card.translationX = rect[0]
        card.translationY = rect[1]
        card.scaleX = (rect[2] / width).coerceIn(0.05f, 1f)
        card.scaleY = (rect[3] / height).coerceIn(0.05f, 1f)
        card.alpha = 0.86f
        card.animate()
            .translationX(0f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(EXPAND_ANIMATION_MS)
            .setInterpolator(cardInterpolator)
            .start()
        animateCornerRadius(card, capsuleCornerRadius(card, rect), panelCornerRadius(card),
                EXPAND_ANIMATION_MS)
    }

    /** 胶囊是圆头的，所以展开起点用卡片高度的一半做圆角。 */
    private fun capsuleCornerRadius(card: View, rect: FloatArray): Float =
        min(rect[3] / 2f, card.height.takeIf { it > 0 }?.toFloat() ?: rect[3] / 2f)

    private fun panelCornerRadius(card: View): Float = dp(card.context, PanelPalette.PANEL_CORNER_DP)
    private fun animateCornerRadius(
        card: View,
        from: Float,
        to: Float,
        duration: Long,
    ) {
        val background = card.background as? GradientDrawable ?: return
        ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            // 与面板放大用同一条曲线，圆角和尺寸同时到位，不会圆角先变好、面板还在长。
            interpolator = cardInterpolator
            addUpdateListener {
                background.cornerRadius = it.animatedValue as Float
                background.invalidateSelf()
            }
            start()
        }
    }

    /**
     * origin 在面板根布局坐标系里的矩形（左、上、宽、高），取不到返回 null。
     *
     * 调用时面板刚 addView 还没布局，所以越界判断只能用屏幕尺寸，不能用 root.height。
     */
    private fun originRect(root: View, origin: View?): FloatArray? {
        if (origin == null || origin.width <= 0 || origin.height <= 0) return null
        val rootLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        val originLocation = IntArray(2)
        origin.getLocationOnScreen(originLocation)
        val left = (originLocation[0] - rootLocation[0]).toFloat()
        val top = (originLocation[1] - rootLocation[1]).toFloat()
        val screenHeight = root.resources.displayMetrics.heightPixels
        // 卡片可能被回收过，位置明显越界时不用它做起点。
        if (top + origin.height < 0 || top > screenHeight) return null
        return floatArrayOf(left, top, origin.width.toFloat(), origin.height.toFloat())
    }

    private fun attachPanel(
        activity: Activity,
        decor: ViewGroup,
        preferHostCardExpand: Boolean = false,
    ): BuiltPanel {
        val built = buildPanel(activity)
        container = built.root
        panel = built.card
        hostActivity = WeakReference(activity)
        dismissing = false

        built.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                if (container === v) {
                    clearGestureExclusion(exclusionView.get())
                    clearReferences()
                }
            }
        })

        val hostCardExpand = if (preferHostCardExpand) findHostCardExpandContainer(decor) else null
        val hostLayer = findHostLauncherContainer(decor)
        if (hostCardExpand != null && hostLayer?.visibility == View.VISIBLE) {
            hostCardExpand.addView(built.root)
            Log.d(TAG, "Panel attached inside host card expand container")
        } else if (hostLayer != null && hostLayer.visibility == View.VISIBLE) {
            hostLayer.addView(built.root)
            Log.d(TAG, "Panel attached inside host launcher container")
        } else {
            decor.addView(built.root)
            Log.d(TAG, "Panel attached to decor")
        }
        built.root.requestFocus()
        applyGestureExclusion(decor)
        Log.d(TAG, "Panel drag started: safeLeft=${built.safeLeft}px")
        return built
    }

    private fun hostLauncherContainerId(view: View): Int =
        view.resources.getIdentifier(
            "launcher_container",
            "id",
            Constants.TARGET_PACKAGE,
        )

    private fun hostCardExpandContainerId(view: View): Int =
        view.resources.getIdentifier(
            "card_expand_container",
            "id",
            Constants.TARGET_PACKAGE,
        )

    private fun findHostLauncherContainer(view: View): ViewGroup? {
        val id = hostLauncherContainerId(view)
        if (id == 0) return null
        return view.rootView?.findViewById<View>(id) as? ViewGroup
    }

    private fun findHostCardExpandContainer(view: View): ViewGroup? {
        val id = hostCardExpandContainerId(view)
        if (id == 0) return null
        return view.rootView?.findViewById<View>(id) as? ViewGroup
    }

    /**
     * 面板在屏幕上时把底部关闭区域从系统手势里排除。
     *
     * 背屏底部是系统导航手势区，不排除的话"底部上滑关面板"会被系统抢走，面板收不到。
     */
    private fun applyGestureExclusion(decor: View) {
        try {
            if (decor.width <= 0 || decor.height <= 0) return
            if (exclusionView.get() !== decor) {
                clearGestureExclusion(exclusionView.get())
                exclusionView = WeakReference(decor)
                originalExclusions = decor.systemGestureExclusionRects.map { Rect(it) }
            }
            val top = (decor.height * BOTTOM_SWIPE_REGION_RATIO).toInt()
            decor.setSystemGestureExclusionRects(
                originalExclusions.orEmpty() + Rect(0, top, decor.width, decor.height),
            )
        } catch (ignored: Throwable) {
            // 部分 ROM 不支持，忽略
        }
    }

    private fun clearGestureExclusion(view: View?) {
        try {
            if (view != null && exclusionView.get() === view) {
                view.systemGestureExclusionRects = originalExclusions.orEmpty()
            }
        } catch (ignored: Throwable) {
            // 部分 ROM 不支持，忽略
        } finally {
            exclusionView.clear()
            originalExclusions = null
        }
    }

    /** 拿不到来源卡片位置时的退化入场：整块面板从底部滑上来。 */
    private fun slideInFromBottom(card: View) {
        val height = card.height.takeIf { it > 0 }?.toFloat()
            ?: card.resources.displayMetrics.heightPixels.toFloat()
        card.translationY = height
        card.animate()
            .translationY(0f)
            .setDuration(EXPAND_ANIMATION_MS)
            .setInterpolator(cardInterpolator)
            .start()
    }

    @JvmStatic
    fun dismiss() {
        dismiss(false)
    }

    /** 关闭面板。底部上滑按宿主 upDragToClose 的整层缩放淡出处理。 */
    @JvmStatic
    fun dismiss(upward: Boolean) {
        dismiss(upward, 0f)
    }

    /**
     * [releaseVelocity] 是松手时手指向上的速度（px/s，正数）。大于 0 时收尾动画接着这个速度走完，
     * 而不是每次都从静止开始跑满 340ms —— 快速一甩就快速收掉，慢慢拖就慢慢收。
     */
    private fun dismiss(upward: Boolean, releaseVelocity: Float) {
        val root = container ?: return
        if (dismissing) return
        dismissing = true

        DiagnosticLogStore.recordRemoteAsync(root.context, "quick_panel dismiss_requested direction=${if (upward) "up" else "back"}")

        val card = panel
        if (card == null || card.height <= 0) {
            removePanel(root)
            return
        }
        card.animate().cancel()

        animateLauncherStyleDismiss(card, root, releaseVelocity)
    }

    private fun animateLauncherStyleDismiss(card: View, root: FrameLayout, releaseVelocity: Float = 0f) {
        val target = launcherAnimationTarget(root, card)
        target.animate().cancel()
        target.pivotX = target.width / 2f
        target.pivotY = target.height / 2f
        // 剩余进度按「再拖多少像素到底」折算，和拖动时 applyLauncherDismissProgress 同一把尺子。
        val height = target.height.takeIf { it > 0 }?.toFloat()
            ?: target.resources.displayMetrics.heightPixels.toFloat()
        val progress = ((1f - target.scaleX) / (1f - CARD_DISMISS_MIN_SCALE)).coerceIn(0f, 1f)
        val remaining = (1f - progress) * height * DISMISS_DRAG_RANGE
        val flung = releaseVelocity > 0f && remaining > 0f
        // DecelerateInterpolator 起点斜率是 2：时长 = 2 × 剩余距离 / 速度，动画起速正好等于手指速度。
        val duration = if (flung) {
            (2f * remaining / releaseVelocity * 1000f).toLong()
                .coerceIn(DISMISS_FLING_MIN_MS, DISMISS_ANIMATION_MS)
        } else {
            DISMISS_ANIMATION_MS
        }
        target.animate()
            .translationX(0f)
            .translationY(0f)
            .scaleX(CARD_DISMISS_MIN_SCALE)
            .scaleY(CARD_DISMISS_MIN_SCALE)
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(if (flung) DecelerateInterpolator() else cardInterpolator)
            .withEndAction {
                removePanel(root)
                target.post { resetDismissTarget(target) }
            }
            .start()
    }

    /**
     * 上滑关闭松手：拖够距离，或者往上甩得够快，都算关闭，并把松手速度交给收尾动画；
     * 否则回弹。两个入口（面板自身 / 宿主手势分发）共用这一套判断。
     */
    private fun releaseDismissDrag(card: View, distance: Float, threshold: Float, upwardVelocity: Float) {
        val flingVelocity = dp(card.context, DISMISS_FLING_DP_PER_SECOND)
        if (distance >= threshold || upwardVelocity >= flingVelocity) {
            dismiss(true, upwardVelocity)
        } else {
            resetLauncherDismissProgress(card)
        }
    }

    /** 按屏幕坐标（rawX / rawY）算速度：面板在拖动时会缩放，局部坐标会跟着变形。 */
    private class RawVelocity {
        /** 用到时才取，[recycle] 后归还给系统池；下次再用会重新取一个。 */
        private var tracker: VelocityTracker? = null

        private fun tracker(): VelocityTracker = tracker ?: VelocityTracker.obtain().also { tracker = it }

        fun clear() = tracker?.clear() ?: Unit

        fun add(event: MotionEvent) {
            val copy = MotionEvent.obtain(event)
            copy.setLocation(event.rawX, event.rawY)
            tracker().addMovement(copy)
            copy.recycle()
        }

        /** 向上的速度（px/s），向下或静止时为 0。 */
        fun upwardVelocity(): Float {
            val t = tracker ?: return 0f
            t.computeCurrentVelocity(1000)
            return max(0f, -t.yVelocity)
        }

        fun recycle() {
            tracker?.recycle()
            tracker = null
        }
    }

    private val launcherVelocity = RawVelocity()

    private fun launcherAnimationTarget(root: FrameLayout, card: View): View {
        val hostId = hostLauncherContainerId(root)
        var current = root.parent
        while (current is View) {
            if (hostId != 0 && current.id == hostId) return current
            current = current.parent
        }
        return card
    }

    private fun applyLauncherDismissProgress(card: View, distance: Float) {
        val root = container
        val target = if (root != null) launcherAnimationTarget(root, card) else card
        val height = target.height.takeIf { it > 0 }?.toFloat()
            ?: target.resources.displayMetrics.heightPixels.toFloat()
        val progress = (distance / (height * DISMISS_DRAG_RANGE)).coerceIn(0f, 1f)
        val scale = 1f - (1f - CARD_DISMISS_MIN_SCALE) * progress
        target.pivotX = target.width / 2f
        target.pivotY = target.height / 2f
        target.translationX = 0f
        target.translationY = 0f
        target.scaleX = scale
        target.scaleY = scale
        target.alpha = 1f
    }

    private fun resetLauncherDismissProgress(card: View) {
        val root = container
        val target = if (root != null) launcherAnimationTarget(root, card) else card
        target.animate()
            .translationX(0f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(150L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun resetDismissTarget(target: View) {
        target.animate().cancel()
        target.translationX = 0f
        target.translationY = 0f
        target.scaleX = 1f
        target.scaleY = 1f
        target.alpha = 1f
    }

    @JvmStatic
    fun isShowing(): Boolean {
        val root = container ?: return false
        return !dismissing && root.isShown && root.isAttachedToWindow &&
            root.getGlobalVisibleRect(visiblePanelBounds)
    }

    @JvmStatic
    fun release(activity: Activity) {
        if (hostActivity.get() === activity) removePanel(container, "host_lifecycle")
    }

    @JvmStatic
    fun trackLauncherTouch(event: MotionEvent?, context: Context?) {
        if (event == null) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                launcherTouchDownX = event.rawX
                launcherTouchDownY = event.rawY
                launcherSwipeClickSuppressUntil = 0L
            }

            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val dx = event.rawX - launcherTouchDownX
                val dy = event.rawY - launcherTouchDownY
                val slop = ViewConfiguration.get(
                    context ?: panel?.context ?: hostActivity.get() ?: return,
                ).scaledTouchSlop
                if (dy < -slop && abs(dy) > abs(dx) * 1.15f) {
                    launcherSwipeClickSuppressUntil = SystemClock.uptimeMillis() + 120L
                }
            }
        }
    }

    @JvmStatic
    fun shouldSuppressModuleCardClick(): Boolean =
        SystemClock.uptimeMillis() < launcherSwipeClickSuppressUntil

    @JvmStatic
    fun handleLauncherGesture(event: MotionEvent?): Boolean {
        val card = panel ?: return false
        if (event == null || !isShowing()) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                launcherGestureDownX = event.rawX
                launcherGestureDownY = event.rawY
                launcherGestureDraggingUp = false
                launcherGestureDismissDistance = 0f
                launcherVelocity.clear()
                launcherVelocity.add(event)
                launcherGestureFromBottom = event.y > card.height * BOTTOM_SWIPE_REGION_RATIO
                if (launcherGestureFromBottom) {
                    Log.d(TAG, "launcher gesture armed for panel dismissal")
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!launcherGestureFromBottom) return false
                launcherVelocity.add(event)
                val dx = event.rawX - launcherGestureDownX
                val dy = event.rawY - launcherGestureDownY
                val slop = ViewConfiguration.get(card.context).scaledTouchSlop
                if (!launcherGestureDraggingUp
                    && dy < -slop && abs(dy) > abs(dx) * 1.15f) {
                    launcherGestureDraggingUp = true
                    card.animate().cancel()
                    // 接下来的 MOVE / UP 都由这里消费，View 树收不到；先补发一个 CANCEL，
                    // 让按下的卡片退出 pressed / 缩放状态，也不会在松手时误触发点击。
                    cancelViewTreeTouch(event)
                    Log.d(TAG, "launcher gesture upward drag captured")
                }
                if (launcherGestureDraggingUp) {
                    launcherGestureDismissDistance = max(0f, -dy)
                    applyLauncherDismissProgress(card, launcherGestureDismissDistance)
                    return true
                }
                return false
            }

            MotionEvent.ACTION_UP -> {
                if (!launcherGestureFromBottom) return false
                val wasDragging = launcherGestureDraggingUp
                val threshold = max(
                    dp(card.context, 8),
                    min(card.height * 0.04f, dp(card.context, 18)),
                )
                launcherVelocity.add(event)
                Log.d(TAG, "launcher gesture up: dragging=" + wasDragging
                        + " distance=" + launcherGestureDismissDistance + " threshold=" + threshold)
                if (wasDragging) {
                    releaseDismissDrag(card, launcherGestureDismissDistance, threshold,
                        launcherVelocity.upwardVelocity())
                } else {
                    resetLauncherDismissProgress(card)
                }
                launcherGestureFromBottom = false
                launcherGestureDraggingUp = false
                launcherGestureDismissDistance = 0f
                return wasDragging
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!launcherGestureFromBottom) return false
                if (launcherGestureDraggingUp) {
                    resetLauncherDismissProgress(card)
                }
                launcherGestureFromBottom = false
                launcherGestureDraggingUp = false
                launcherGestureDismissDistance = 0f
                // CANCEL 必须继续往下分发，子 View 才能复位。
                return false
            }
        }
        // 多指等其它事件：拖动中才吞，否则交给宿主照常处理。
        return launcherGestureDraggingUp
    }

    private fun cancelViewTreeTouch(source: MotionEvent) {
        val target: View = hostActivity.get()?.window?.decorView ?: container ?: return
        val cancel = MotionEvent.obtain(source)
        cancel.action = MotionEvent.ACTION_CANCEL
        try {
            target.dispatchTouchEvent(cancel)
        } catch (t: Throwable) {
            Log.w(TAG, "cancel dispatch failed", t)
        } finally {
            cancel.recycle()
        }
    }

    private data class BuiltPanel(
        val root: FrameLayout,
        val card: SwipeDismissCard,
        val safeLeft: Int,
    )

    private fun buildPanel(activity: Activity): BuiltPanel {
        val dark = isDark(activity)
        val restartRequired = needsScopeRestart(activity)
        val strings = PanelStrings(activity, allowModuleResources = !restartRequired)
        val safeLeft = resolveSafeLeft(activity)
        val palette = PanelPalette.of(dark)

        val root = object : FrameLayout(activity) {
            override fun onVisibilityChanged(changedView: View, visibility: Int) {
                super.onVisibilityChanged(changedView, visibility)
                if (visibility == View.VISIBLE || container !== this) return

                // 宿主收起应用卡列表时只隐藏父容器；在可见性分发结束后释放面板。
                post {
                    if (container === this) {
                        Log.d(TAG, "Panel released after host became hidden")
                        removePanel(this, "host_hidden")
                    }
                }
            }
        }.apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            fitsSystemWindows = false
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismiss()
                    true
                } else {
                    false
                }
            }
        }

        val card = SwipeDismissCard(activity) { upward -> dismiss(upward) }.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM,
            )
            background = panelBackground(palette, activity)
            isClickable = true
        }

        if (restartRequired) {
            card.addView(
                TextView(activity).apply {
                    text = strings.restartRequired
                    textSize = ROW_TITLE_TEXT_SP
                    gravity = Gravity.CENTER
                    setTextColor(palette.title)
                    setPadding(
                        safeLeft + dp(activity, 12).toInt(),
                        dp(activity, 16).toInt(),
                        dp(activity, 18).toInt(),
                        dp(activity, 16).toInt(),
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    )
                },
            )
            root.addView(card)
            root.requestFocus()
            Log.i(TAG, "Panel requires rear screen scope restart after module replacement")
            return BuiltPanel(root, card, safeLeft)
        }

        card.addView(buildPanelContent(activity, palette, strings, safeLeft, host = activity))
        root.addView(card)
        root.requestFocus()

        return BuiltPanel(root, card, safeLeft)
    }

    /**
     * 按真实面板的样子画一张应用卡预览图（宿主「我的应用卡」列表里的缩略图）。
     *
     * 与面板共用 [buildPanelContent]：语言跟随系统、颜色按 [dark]、各卡片显示当前真实取值，
     * 不再是一张写死的中文浅色截图。在背屏的显示密度下排版，字号与真实面板一致。
     */
    @JvmStatic
    fun renderPreview(context: Context, dark: Boolean, width: Int, height: Int): Bitmap? = try {
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Constants.SYSTEM_SECONDARY_DISPLAY_ID)
        val displayContext = display?.let { context.createDisplayContext(it) } ?: context
        val palette = PanelPalette.of(dark)
        val strings = PanelStrings(displayContext, allowModuleResources = true)
        val frame = LinearLayout(displayContext).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground(palette, displayContext)
            addView(buildPanelContent(displayContext, palette, strings, KNOWN_BACKSCREEN_SAFE_LEFT_PX, host = null))
        }
        frame.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        frame.layout(0, 0, width, height)
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { frame.draw(Canvas(it)) }
    } catch (t: Throwable) {
        Log.w(TAG, "Panel preview render failed", t)
        null
    }

    /**
     * 预览图的内容签名：语言 + 每张卡片的标题与当前取值 + 模块版本。
     * 签名不变说明预览图画出来也一样，应用卡导出时据此跳过重画（那条路径挂在宿主高频调用上）。
     */
    @JvmStatic
    fun previewSignature(context: Context): String = try {
        val strings = PanelStrings(context, allowModuleResources = true)
        buildString {
            append(BuildConfig.VERSION_CODE).append('|')
            append(context.resources.configuration.locales.toLanguageTags()).append('|')
            for (spec in panelTileSpecs(strings)) {
                append(spec.title).append('=')
                when (spec) {
                    is ToggleTileSpec -> append(spec.read())
                    is CycleTileSpec -> append(spec.read())
                }
                append(';')
            }
        }
    } catch (t: Throwable) {
        // 读不出来就当作每次都变，退回到原来的「总是重画」。
        "error:" + System.nanoTime()
    }

    /**
     * 面板内容：顶部模块标识 + 两列大号开关卡片。
     *
     * 背屏离眼睛远，整张卡片都是点击区；行数按卡片数量平分剩余高度，校准机型 6 张（3 行），
     * 其它机型 4 张（2 行，卡片更高）。[host] 为 null 时用于离屏预览，卡片不响应点击。
     */
    private fun buildPanelContent(
        context: Context,
        palette: PanelPalette,
        strings: PanelStrings,
        safeLeft: Int,
        host: Activity?,
    ): View {
        val gap = dp(context, 8).toInt()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                safeLeft + dp(context, 12).toInt(),
                dp(context, 10).toInt(),
                dp(context, 12).toInt(),
                dp(context, 12).toInt(),
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val status = TextView(context).apply {
            textSize = 12f
            setTextColor(palette.error)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        column.addView(headerRow(context, palette, status))

        val tiles = panelTileSpecs(strings).map { spec -> tileView(context, palette, strings, spec, host, status) }
        tiles.chunked(2).forEachIndexed { index, pair ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    if (index > 0) topMargin = gap
                }
            }
            pair.forEachIndexed { i, tile ->
                tile.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i > 0) marginStart = gap
                }
                row.addView(tile)
            }
            if (pair.size == 1) {
                row.addView(Space(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        marginStart = gap
                    }
                })
            }
            column.addView(row)
        }
        return column
    }

    /** 左上角（安全区之后）：模块图标 + 名称；右侧留给保存失败之类的提示。 */
    private fun headerRow(context: Context, palette: PanelPalette, status: TextView): LinearLayout {
        val iconSize = dp(context, 20).toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 28).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(context, 8).toInt() }
            val icon = runCatching { context.packageManager.getApplicationIcon(Constants.MODULE_PACKAGE) }.getOrNull()
            if (icon != null) {
                addView(ImageView(context).apply {
                    setImageDrawable(icon)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        marginEnd = dp(context, 8).toInt()
                    }
                })
            }
            addView(TextView(context).apply {
                text = MODULE_TITLE
                textSize = HEADER_TEXT_SP
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.title)
                maxLines = 1
            })
            addView(status)
        }
    }

    /** 面板里的一张卡片：开关（点一下切换开 / 关）或循环项（点一下换到下一档）。 */
    private sealed interface PanelTileSpec {
        val title: String
    }

    private class ToggleTileSpec(
        override val title: String,
        val onText: String,
        val offText: String,
        val read: () -> Boolean,
        val write: (Context, Boolean) -> Boolean,
        val onSaved: (Activity, Boolean) -> Unit = { _, _ -> },
    ) : PanelTileSpec

    private class CycleTileSpec(
        override val title: String,
        /** 取值 to 显示文字，按点击循环的顺序排列。 */
        val options: List<Pair<String, String>>,
        val read: () -> String,
        val write: (Context, String) -> Boolean,
        val onSaved: (Activity, String) -> Unit = { _, _ -> },
    ) : PanelTileSpec

    private fun panelTileSpecs(strings: PanelStrings): List<PanelTileSpec> {
        val calibrated = BatteryRingGeometry.isCalibratedDevice(Build.DEVICE)
        val specs = mutableListOf<PanelTileSpec>(
            ToggleTileSpec(
                title = strings.longPressTitle,
                onText = strings.longPressOn,
                offText = strings.longPressOff,
                read = { PrefsBridge.readDisableLongPressForRemote() },
                write = { context, value -> PrefsBridge.requestDisableLongPressWrite(context, value) },
            ),
            ToggleTileSpec(
                title = strings.wallpaperLimitTitle,
                onText = strings.wallpaperLimitOn,
                offText = strings.wallpaperLimitOff,
                read = { PrefsBridge.readRemoveWallpaperLimitForRemote() },
                write = { context, value -> PrefsBridge.requestRemoveWallpaperLimitWrite(context, value) },
            ),
        )
        if (calibrated) {
            specs += ToggleTileSpec(
                title = strings.batteryRingTitle,
                onText = strings.batteryRingOn,
                offText = strings.batteryRingOff,
                read = { PrefsBridge.readEnableBatteryRingForRemote() },
                write = { context, value -> PrefsBridge.requestEnableBatteryRingWrite(context, value) },
                onSaved = { activity, value -> BatteryRingHost.setEnabled(activity, value) },
            )
            specs += CycleTileSpec(
                title = strings.ringStyleTitle,
                options = BatteryRingStyle.entries.map { it.prefValue to strings.label(it.labelRes, it.prefValue) },
                read = { BatteryRingStyle.fromPref(PrefsBridge.readBatteryLayoutForRemote(Constants.KEY_BATTERY_RING_STYLE)).prefValue },
                write = { context, value ->
                    PrefsBridge.requestPanelStringWrite(context, Constants.KEY_BATTERY_RING_STYLE, value)
                },
                onSaved = { activity, _ -> BatteryRingHost.sync(activity) },
            )
        }
        specs += CycleTileSpec(
            title = strings.offTimeoutTitle,
            options = RearScreenOffTimeout.entries.map { it.prefValue to strings.label(it.labelRes, it.prefValue) },
            read = { RearScreenOffTimeout.fromPref(PrefsBridge.readRearScreenOffTimeoutForRemote()).prefValue },
            write = { context, value ->
                PrefsBridge.requestPanelStringWrite(context, Constants.KEY_REAR_SCREEN_OFF_TIMEOUT, value)
            },
        )
        specs += CycleTileSpec(
            title = strings.autoWallpaperTitle,
            options = AutoWallpaperInterval.entries.map { it.prefValue to strings.label(it.labelRes, it.prefValue) },
            read = { AutoWallpaperInterval.fromPref(PrefsBridge.readAutoWallpaperIntervalForRemote()).prefValue },
            write = { context, value ->
                PrefsBridge.requestPanelStringWrite(context, Constants.KEY_AUTO_WALLPAPER_INTERVAL_MINUTES, value)
            },
            // 定时器就在本进程里，直接通知它按新间隔重排（同步广播要求模块签名权限，宿主发不出）。
            onSaved = { _, value ->
                AutoWallpaperRotationHost.updateIntervalForAll(AutoWallpaperInterval.fromPref(value).minutes)
            },
        )
        return specs
    }

    private fun tileView(
        context: Context,
        palette: PanelPalette,
        strings: PanelStrings,
        spec: PanelTileSpec,
        host: Activity?,
        status: TextView,
    ): View {
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, PanelPalette.TILE_CORNER_DP)
        }
        val title = TextView(context).apply {
            text = spec.title
            textSize = TILE_TITLE_TEXT_SP
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(10, TILE_TITLE_TEXT_SP.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
        }
        val value = TextView(context).apply {
            textSize = TILE_VALUE_TEXT_SP
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(9, TILE_VALUE_TEXT_SP.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
            setPadding(0, dp(context, 2).toInt(), 0, 0)
        }
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12).toInt(), dp(context, 6).toInt(), dp(context, 12).toInt(), dp(context, 6).toInt())
            this.background = background
            addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val look = TileLook(background, title, value)
        when (spec) {
            is ToggleTileSpec -> {
                var checked = spec.read()
                fun render(animate: Boolean = true) {
                    look.apply(
                        background = if (checked) palette.tileOn else palette.tile,
                        titleColor = if (checked) palette.onTileOn else palette.onTile,
                        valueColor = if (checked) palette.summaryOn else palette.summary,
                        text = if (checked) spec.onText else spec.offText,
                        animate = animate,
                    )
                    tile.contentDescription = spec.title + ", " + (if (checked) spec.onText else spec.offText)
                }
                render(animate = false)
                tile.accessibilityDelegate = checkableDelegate { checked }
                if (host != null) {
                    bindTileClick(tile, host) { done ->
                        val next = !checked
                        checked = next
                        render()
                        persist(host, spec.title, { spec.write(host, next) }) { saved ->
                            if (saved) {
                                if (!host.isDestroyed && !host.isFinishing) spec.onSaved(host, next)
                            } else {
                                checked = !next
                                render()
                                showStatus(status, strings.saveFailed)
                            }
                            done()
                        }
                    }
                }
            }

            is CycleTileSpec -> {
                var current = spec.read()
                fun render(animate: Boolean = true) {
                    val label = spec.options.firstOrNull { it.first == current }?.second ?: current
                    look.apply(
                        background = palette.tile,
                        titleColor = palette.onTile,
                        valueColor = palette.accent,
                        text = label,
                        animate = animate,
                    )
                    tile.contentDescription = spec.title + ", " + label
                }
                render(animate = false)
                tile.accessibilityDelegate = buttonDelegate()
                if (host != null) {
                    bindTileClick(tile, host) { done ->
                        val previous = current
                        val index = spec.options.indexOfFirst { it.first == current }
                        val next = spec.options[(index + 1).mod(spec.options.size)].first
                        current = next
                        render()
                        persist(host, spec.title, { spec.write(host, next) }) { saved ->
                            if (saved) {
                                if (!host.isDestroyed && !host.isFinishing) spec.onSaved(host, next)
                            } else {
                                current = previous
                                render()
                                showStatus(status, strings.saveFailed)
                            }
                            done()
                        }
                    }
                }
            }
        }
        return tile
    }

    /**
     * 卡片外观的过渡：底色和文字色在 [TILE_TRANSITION_MS] 内渐变，状态文字先淡出、换字、再淡入，
     * 不再是开 / 关瞬间跳色。首次渲染不做动画。
     */
    private class TileLook(
        private val background: GradientDrawable,
        private val title: TextView,
        private val value: TextView,
    ) {
        private var backgroundColor = 0
        private var titleColor = 0
        private var valueColor = 0
        private var colorAnimator: ValueAnimator? = null
        private val evaluator = ArgbEvaluator()

        fun apply(background: Int, titleColor: Int, valueColor: Int, text: CharSequence, animate: Boolean) {
            colorAnimator?.cancel()
            if (!animate) {
                set(background, titleColor, valueColor)
                value.animate().cancel()
                value.alpha = 1f
                value.text = text
                return
            }
            val fromBackground = backgroundColor
            val fromTitle = this.titleColor
            val fromValue = valueColor
            colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = TILE_TRANSITION_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val t = it.animatedFraction
                    set(
                        evaluator.evaluate(t, fromBackground, background) as Int,
                        evaluator.evaluate(t, fromTitle, titleColor) as Int,
                        evaluator.evaluate(t, fromValue, valueColor) as Int,
                    )
                }
                start()
            }
            if (value.text.toString() != text.toString()) {
                value.animate().cancel()
                value.animate().alpha(0f).setDuration(TILE_TRANSITION_MS / 2).withEndAction {
                    value.text = text
                    value.animate().alpha(1f).setDuration(TILE_TRANSITION_MS / 2).start()
                }.start()
            }
        }

        private fun set(background: Int, titleColor: Int, valueColor: Int) {
            backgroundColor = background
            this.titleColor = titleColor
            this.valueColor = valueColor
            this.background.setColor(background)
            title.setTextColor(titleColor)
            value.setTextColor(valueColor)
        }
    }

    /** 点击：按压缩放反馈 + 震动；保存期间整张卡片置灰、不接受重复点击。 */
    private fun bindTileClick(tile: View, host: Activity, onTap: (done: () -> Unit) -> Unit) {
        var saving = false
        tile.isClickable = true
        tile.isFocusable = true
        tile.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(TILE_PRESSED_SCALE).scaleY(TILE_PRESSED_SCALE)
                    .setDuration(90L).setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f)
                    .setDuration(160L).setInterpolator(cardInterpolator).start()
            }
            false
        }
        tile.setOnClickListener {
            if (saving) return@setOnClickListener
            saving = true
            tile.alpha = 0.6f
            tile.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onTap {
                saving = false
                tile.alpha = 1f
            }
        }
    }

    /** 在后台线程写偏好（会走 Binder 到模块进程），结果回到主线程。 */
    private fun persist(host: Activity, label: String, write: () -> Boolean, complete: (Boolean) -> Unit) {
        try {
            BackgroundTasks.execute {
                val saved = try {
                    write()
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to persist panel tile: $label", t)
                    false
                }
                mainHandler.post { complete(saved) }
                DiagnosticLogStore.recordRemote(host.applicationContext, "panel tile applied=$saved: $label")
                // 应用卡缩略图要跟着真实状态走：保存成功后在后台重画一次。
                if (saved) PanelAppCard.refreshPreviews()
            }
        } catch (t: java.util.concurrent.RejectedExecutionException) {
            complete(false)
        }
    }

    /** 保存失败时在标题行右侧提示几秒，而不是只把开关默默弹回去。 */
    private fun showStatus(status: TextView, message: String) {
        status.text = message
        status.visibility = View.VISIBLE
        status.removeCallbacks(status.tag as? Runnable)
        val hide = Runnable { status.visibility = View.GONE }
        status.tag = hide
        status.postDelayed(hide, STATUS_VISIBLE_MS)
    }

    /** 循环项卡片：读屏按按钮处理，点一下换到下一档。 */
    private fun buttonDelegate() = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = "android.widget.Button"
        }
    }

    private fun checkableDelegate(isChecked: () -> Boolean) = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = "android.widget.Switch"
            info.isCheckable = true
            info.isChecked = isChecked()
        }
    }

    /**
     * 系统当前报告 safeInsetLeft=296px；额外留 2px 缝隙，最终从 x=298px 开始。
     * 若首次取 Insets 失败，仅在 900..1050 x 500..700 的已知背屏尺寸上使用 298px 兜底。
     */
    private fun resolveSafeLeft(activity: Activity): Int {
        val decor = activity.window?.decorView
        val cutoutLeft = decor?.rootWindowInsets?.displayCutout?.safeInsetLeft ?: 0
        val metrics = activity.resources.displayMetrics
        val knownBackscreen = metrics.widthPixels in 900..1050 && metrics.heightPixels in 500..700
        val desired = when {
            cutoutLeft > 0 -> max(cutoutLeft + 2, if (knownBackscreen) KNOWN_BACKSCREEN_SAFE_LEFT_PX else 0)
            knownBackscreen -> KNOWN_BACKSCREEN_SAFE_LEFT_PX
            else -> 0
        }
        val minimumPanelWidth = dp(activity, 180).toInt()
        return desired.coerceIn(0, max(0, metrics.widthPixels - minimumPanelWidth))
    }

    private fun panelBackground(palette: PanelPalette, context: Context): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(palette.background)
            cornerRadius = dp(context, PanelPalette.PANEL_CORNER_DP)
        }
    }

    private fun removePanel(expectedRoot: FrameLayout?, reason: String = "removed") {
        if (expectedRoot == null) {
            clearReferences()
            return
        }
        expectedRoot.animate().cancel()
        if (container === expectedRoot) {
            DiagnosticLogStore.recordRemoteAsync(expectedRoot.context, "quick_panel released reason=$reason")
        }
        panel?.animate()?.cancel()
        val parent = expectedRoot.parent as? ViewGroup
        // 关闭手势由 Activity 级 dispatchTouchEvent hook 整体消费，父 ViewGroup 收不到 UP，
        // 无法自行复位 FLAG_DISALLOW_INTERCEPT（SwipeDismissCard 上滑时置位）。该标记残留会让
        // 宿主手势层持续收不到触摸，表现为关闭面板后要再滑一次长按才恢复。移除前显式清掉。
        panel?.parent?.requestDisallowInterceptTouchEvent(false)
        // 拖到一半被移除（宿主收起列表、Activity 暂停）时，宿主容器还停在拖动中的缩放 / 透明度上；
        // 动画对象是宿主自己的 launcher_container，必须在脱离之前复位。
        if (container === expectedRoot) {
            panel?.let { card ->
                val target = launcherAnimationTarget(expectedRoot, card)
                if (target !== card) resetDismissTarget(target)
            }
        }
        clearGestureExclusion(expectedRoot.rootView)
        parent?.removeView(expectedRoot)
        if (container === expectedRoot) clearReferences()
    }

    private fun clearReferences() {
        showGeneration++
        container = null
        panel = null
        originRect = null
        hostActivity.clear()
        dismissing = false
        launcherGestureFromBottom = false
        launcherGestureDraggingUp = false
        launcherGestureDownX = 0f
        launcherGestureDownY = 0f
        launcherGestureDismissDistance = 0f
        launcherTouchDownX = 0f
        launcherTouchDownY = 0f
        launcherSwipeClickSuppressUntil = 0L
        launcherVelocity.recycle()
    }

    private fun dp(context: Context, value: Int): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        )

    private fun isDark(activity: Activity): Boolean =
        (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun needsScopeRestart(activity: Activity): Boolean = try {
        val installed = activity.packageManager.getPackageInfo(
            Constants.MODULE_PACKAGE,
            PackageManager.PackageInfoFlags.of(0),
        )
        loadedModule.requiresRestart(installed.applicationInfo?.sourceDir, installed.longVersionCode)
    } catch (e: Exception) {
        Log.w(TAG, "Unable to check whether the panel module was replaced", e)
        false
    }

    private class PanelStrings(context: Context, allowModuleResources: Boolean) {
        private val chinese = context.resources.configuration.locales[0]?.language == "zh"
        private val moduleContext = if (allowModuleResources) try {
            context.createPackageContext(Constants.MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
                .createConfigurationContext(context.resources.configuration)
        } catch (_: Throwable) {
            null
        } else null

        private fun get(id: Int, zh: String, en: String): String = try {
            moduleContext?.getString(id) ?: if (chinese) zh else en
        } catch (_: Throwable) {
            if (chinese) zh else en
        }

        /** 枚举取值的显示文字（与主 App 同一份字符串资源）。 */
        fun label(id: Int, fallback: String): String = try {
            moduleContext?.getString(id) ?: fallback
        } catch (_: Throwable) {
            fallback
        }

        // An outdated process must be able to show this without reading the new APK's resource IDs.
        val restartRequired = get(R.string.panel_restart_required, "请重启背屏作用域", "Please restart the rear screen scope")
        val saveFailed = get(R.string.panel_save_failed, "保存失败，请在主应用中修改", "Save failed, change it in the app")
        val longPressTitle = get(R.string.panel_tile_long_press, "长按换壁纸", "Long-press switch")
        val longPressOn = get(R.string.panel_tile_long_press_on, "已禁用", "Disabled")
        val longPressOff = get(R.string.panel_tile_long_press_off, "原厂行为", "Stock")
        val wallpaperLimitTitle = get(R.string.panel_tile_wallpaper_limit, "壁纸数量上限", "Wallpaper limit")
        val wallpaperLimitOn = get(R.string.panel_tile_wallpaper_limit_on, "已移除", "Removed")
        val wallpaperLimitOff = get(R.string.panel_tile_wallpaper_limit_off, "最多 15 张", "Up to 15")
        val batteryRingTitle = get(R.string.panel_tile_battery, "背屏电量", "Battery ring")
        val batteryRingOn = get(R.string.panel_tile_battery_on, "显示", "Shown")
        val batteryRingOff = get(R.string.panel_tile_battery_off, "隐藏", "Hidden")
        val ringStyleTitle = get(R.string.panel_tile_ring_style, "电量环样式", "Ring style")
        val offTimeoutTitle = get(R.string.panel_tile_off_timeout, "息屏时间", "Screen timeout")
        val autoWallpaperTitle = get(R.string.panel_tile_auto_wallpaper, "定时换壁纸", "Auto wallpaper")
    }

    private class SwipeDismissCard(
        context: Context,
        private val onDismiss: (upward: Boolean) -> Unit,
    ) : LinearLayout(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var fromBottom = false
        private var draggingUp = false
        private var dismissDistance = 0f
        private val velocity = RawVelocity()

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            velocity.recycle()
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    draggingUp = false
                    dismissDistance = 0f
                    fromBottom = event.y > height * BOTTOM_SWIPE_REGION_RATIO
                    velocity.clear()
                    velocity.add(event)
                    Log.d(TAG, "dismiss card down: y=" + event.y + " height=" + height
                            + " fromBottom=" + fromBottom)
                }

                MotionEvent.ACTION_MOVE -> {
                    velocity.add(event)
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!draggingUp && fromBottom
                        && dy < -touchSlop && abs(dy) > abs(dx) * 1.15f) {
                        draggingUp = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        Log.d(TAG, "dismiss card intercept upward drag")
                        return true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingUp = false
                }
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    draggingUp = false
                    dismissDistance = 0f
                    fromBottom = event.y > height * BOTTOM_SWIPE_REGION_RATIO
                    velocity.clear()
                    velocity.add(event)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    velocity.add(event)
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!draggingUp && fromBottom
                        && dy < -touchSlop && abs(dy) > abs(dx) * 1.15f) {
                        draggingUp = true
                    }
                    if (draggingUp) {
                        dismissDistance = max(0f, -dy)
                        applyLauncherDismissProgress(this, dismissDistance)
                        return true
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (draggingUp) {
                        val threshold = max(
                            dp(context, 8),
                            min(height * 0.04f, dp(context, 18)),
                        )
                        velocity.add(event)
                        Log.d(TAG, "dismiss card up released: distance=" + dismissDistance
                                + " threshold=" + threshold)
                        releaseDismissDrag(this, dismissDistance, threshold, velocity.upwardVelocity())
                        draggingUp = false
                        dismissDistance = 0f
                        return true
                    }
                    return performClick()
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (draggingUp) {
                        resetLauncherDismissProgress(this)
                    }
                    draggingUp = false
                    dismissDistance = 0f
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean = super.performClick()
    }
}
