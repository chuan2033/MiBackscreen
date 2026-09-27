package hook.HyperBackscreen.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
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
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import hook.HyperBackscreen.BuildConfig
import hook.HyperBackscreen.R
import hook.HyperBackscreen.bridge.PrefsBridge
import hook.HyperBackscreen.bridge.DiagnosticLogStore
import hook.HyperBackscreen.common.Constants
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
    private const val TAG = Constants.LOG_TAG
    private const val KNOWN_BACKSCREEN_SAFE_LEFT_PX = 298
    private const val DISMISS_ANIMATION_MS = 340L
    private const val EXPAND_ANIMATION_MS = 340L
    private const val CARD_DISMISS_MIN_SCALE = 0.8f
    /** 面板展开后的圆角；展开/缩回时在它与胶囊圆角之间过渡。 */
    private const val PANEL_CORNER_RADIUS_DP = 24
    /** 底部这条窄区域上的上滑用来关闭面板，并从系统手势区里排除。 */
    private const val BOTTOM_SWIPE_REGION_RATIO = 0.78f
    private const val SETTLE_MIN_MS = 100L
    private const val SETTLE_MAX_MS = 320L
    private const val ROW_TITLE_TEXT_SP = 16f
    private const val ROW_SUMMARY_TEXT_SP = 13f

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
    private var dismissing = false
    private var openingDrag = false
    private var dragAttachPending = false
    private var pendingDragTop = 0f
    private var pendingSettleOpen: Boolean? = null
    private var pendingVelocityY = 0f
    private var dragGeneration = 0L
    private var launcherGestureFromBottom = false
    private var launcherGestureDraggingUp = false
    private var launcherGestureDownX = 0f
    private var launcherGestureDownY = 0f
    private var launcherGestureDismissDistance = 0f
    private var launcherTouchDownX = 0f
    private var launcherTouchDownY = 0f
    private var launcherSwipeClickSuppressUntil = 0L
    private val cardInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    @JvmStatic
    fun beginOpeningDrag(activity: Activity, fingerY: Float) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (openingDrag && hostActivity.get() === activity) {
            updateOpeningDrag(fingerY)
            return
        }

        removePanel(container)
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val generation = ++dragGeneration
        openingDrag = true
        dragAttachPending = true
        pendingDragTop = fingerY
        pendingSettleOpen = null
        pendingVelocityY = 0f
        hostActivity = WeakReference(activity)

        // dispatchTouchEvent 正在遍历宿主 View 树，下一帧再挂载；期间保存最新手指位置。
        decor.post {
            if (generation != dragGeneration || !dragAttachPending || hostActivity.get() !== activity) {
                return@post
            }
            dragAttachPending = false
            if (activity.isFinishing || activity.isDestroyed) {
                clearReferences()
                return@post
            }
            val built = attachPanel(activity, decor)
            applyOpeningDragTop(built.card, built.root, pendingDragTop)
            pendingSettleOpen?.let { settleOpeningDrag(it, pendingVelocityY) }
        }
    }

    @JvmStatic
    fun updateOpeningDrag(fingerY: Float) {
        if (!openingDrag) return
        pendingDragTop = fingerY
        val card = panel ?: return
        val root = container ?: return
        card.animate().cancel()
        applyOpeningDragTop(card, root, fingerY)
    }

    @JvmStatic
    fun finishOpeningDrag(open: Boolean, velocityY: Float) {
        if (!openingDrag) return
        openingDrag = false
        pendingSettleOpen = open
        pendingVelocityY = velocityY
        if (!dragAttachPending) settleOpeningDrag(open, velocityY)
    }

    @JvmStatic
    fun isOpeningDrag(): Boolean = openingDrag

    /**
     * 从背屏卡片入口打开面板：对齐系统卡片的展开方式，从被点击的那张卡片位置放大到整块面板。
     */
    @JvmStatic
    fun show(activity: Activity, origin: View?) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (isShowing()) return

        removePanel(container)
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val generation = ++dragGeneration
        openingDrag = false
        dragAttachPending = false
        pendingSettleOpen = null
        pendingVelocityY = 0f
        hostActivity = WeakReference(activity)

        decor.post {
            if (generation != dragGeneration || hostActivity.get() !== activity) {
                return@post
            }
            if (activity.isFinishing || activity.isDestroyed) {
                clearReferences()
                return@post
            }
            val built = attachPanel(activity, decor)
            expandFromOriginatingCard(built, origin)
            Log.d(TAG, "Panel opened from card entry")
        }
    }

    private fun expandWithHostCardView(built: BuiltPanel, origin: View?): Boolean {
        if (origin == null) return false
        val root = built.root
        val cardExpand = root.parent as? View ?: return false
        val launcherPanel = findHostLauncherPanel(root) ?: return false
        if (cardExpand.id != hostCardExpandContainerId(root)) return false
        return try {
            val callbackClass = Class.forName(
                "U1.C0110j",
                false,
                launcherPanel.javaClass.classLoader,
            )
            val callbackConstructor = callbackClass.declaredConstructors.firstOrNull {
                it.parameterTypes.size == 2
                    && it.parameterTypes[1] == Integer.TYPE
            } ?: run {
                Log.w(TAG, "Host LauncherCardView callback constructor missing")
                return false
            }
            callbackConstructor.isAccessible = true
            val callback = callbackConstructor.newInstance(launcherPanel, 1)

            callHostAnimationStatus(launcherPanel, "showAppCard", true)
            callHostRecyclerScale(launcherPanel, 1f, CARD_DISMISS_MIN_SCALE)

            val expand = cardExpand.javaClass.declaredMethods.firstOrNull {
                it.name == "c"
                    && it.parameterTypes.size == 2
                    && it.parameterTypes[0] == View::class.java
                    && it.parameterTypes[1].isAssignableFrom(callbackClass)
            } ?: run {
                Log.w(TAG, "Host LauncherCardView expand method missing")
                return false
            }
            expand.isAccessible = true
            expand.invoke(cardExpand, origin, callback)
            originRect = originRect(root, origin)
            Log.d(TAG, "Panel expanded with host LauncherCardView")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Host LauncherCardView expand failed", t)
            callHostAnimationStatus(launcherPanel, "showAppCard", false)
            false
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
            card.translationY = height.toFloat()
            settleOpeningDrag(true, 0f)
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

    private fun panelCornerRadius(card: View): Float = dp(card.context, PANEL_CORNER_RADIUS_DP)
    private fun animateCornerRadius(
        card: View,
        from: Float,
        to: Float,
        duration: Long,
    ) {
        val background = card.background as? GradientDrawable ?: return
        ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
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
                if (container === v) clearReferences()
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

    private fun findHostLauncherPanel(view: View): View? {
        val launcherContainer = findHostLauncherContainer(view) ?: return null
        var current = launcherContainer.parent
        while (current is View) {
            if (current.javaClass.name == "U1.C0118n") return current
            current = current.parent
        }
        return null
    }

    private fun callHostAnimationStatus(launcherPanel: View, source: String, animating: Boolean) {
        try {
            val method = launcherPanel.javaClass.getDeclaredMethod(
                "y",
                String::class.java,
                Boolean::class.javaPrimitiveType,
            )
            method.isAccessible = true
            method.invoke(launcherPanel, source, animating)
        } catch (t: Throwable) {
            Log.w(TAG, "Host animation status update failed", t)
        }
    }

    private fun callHostRecyclerScale(launcherPanel: View, from: Float, to: Float) {
        try {
            val method = launcherPanel.javaClass.getDeclaredMethod(
                "A",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            )
            method.isAccessible = true
            method.invoke(launcherPanel, from, to)
        } catch (t: Throwable) {
            Log.w(TAG, "Host recycler scale animation failed", t)
        }
    }

    /**
     * 面板在屏幕上时把底部关闭区域从系统手势里排除。
     *
     * 背屏底部是系统导航手势区，不排除的话"底部上滑关面板"会被系统抢走，面板收不到。
     */
    private fun applyGestureExclusion(decor: View) {
        try {
            if (decor.width <= 0 || decor.height <= 0) return
            val top = (decor.height * BOTTOM_SWIPE_REGION_RATIO).toInt()
            decor.setSystemGestureExclusionRects(
                listOf(Rect(0, top, decor.width, decor.height)),
            )
        } catch (ignored: Throwable) {
            // 部分 ROM 不支持，忽略
        }
    }

    private fun clearGestureExclusion(view: View?) {
        try {
            view?.setSystemGestureExclusionRects(emptyList())
        } catch (ignored: Throwable) {
            // 部分 ROM 不支持，忽略
        }
    }

    private fun applyOpeningDragTop(card: View, root: View, fingerY: Float) {
        val height = root.height.takeIf { it > 0 }
            ?: root.resources.displayMetrics.heightPixels
        card.translationY = fingerY.coerceIn(0f, height.toFloat())
    }

    private fun settleOpeningDrag(open: Boolean, velocityY: Float) {
        val root = container ?: return
        val card = panel ?: return
        pendingSettleOpen = null
        val height = (root.height.takeIf { it > 0 }
            ?: root.resources.displayMetrics.heightPixels).toFloat()
        val target = if (open) 0f else height
        val distance = abs(card.translationY - target)
        val fraction = if (height > 0f) (distance / height).coerceIn(0f, 1f) else 1f
        var duration = (SETTLE_MIN_MS + (SETTLE_MAX_MS - SETTLE_MIN_MS) * fraction).toLong()
        val speed = abs(velocityY)
        if (speed > height && distance > 0f) {
            duration = min(duration, (distance / speed * 1000f).toLong().coerceAtLeast(SETTLE_MIN_MS))
        }

        dismissing = !open
        card.animate().cancel()
        if (distance < 1f) {
            card.translationY = target
            if (!open) removePanel(root)
            return
        }
        card.animate()
            .translationY(target)
            .setDuration(duration.coerceIn(SETTLE_MIN_MS, SETTLE_MAX_MS))
            .withEndAction {
                if (open) {
                    dismissing = false
                    Log.d(TAG, "Panel opened after drag")
                } else {
                    removePanel(root)
                }
            }
            .start()
    }

    @JvmStatic
    fun dismiss() {
        dismiss(false)
    }

    /** 关闭面板。底部上滑按宿主 upDragToClose 的整层缩放淡出处理。 */
    @JvmStatic
    fun dismiss(upward: Boolean) {
        val root = container ?: return
        if (dismissing) return
        dismissing = true

        val card = panel
        if (card == null || card.height <= 0) {
            removePanel(root)
            return
        }
        card.animate().cancel()

        animateLauncherStyleDismiss(card, root)
    }

    private fun animateLauncherStyleDismiss(card: View, root: FrameLayout) {
        val target = launcherAnimationTarget(root, card)
        target.animate().cancel()
        target.pivotX = target.width / 2f
        target.pivotY = target.height / 2f
        target.animate()
            .translationX(0f)
            .translationY(0f)
            .scaleX(CARD_DISMISS_MIN_SCALE)
            .scaleY(CARD_DISMISS_MIN_SCALE)
            .alpha(0f)
            .setDuration(DISMISS_ANIMATION_MS)
            .setInterpolator(cardInterpolator)
            .withEndAction {
                removePanel(root)
                target.post { resetDismissTarget(target) }
            }
            .start()
    }

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
        val progress = (distance / (height * 0.36f)).coerceIn(0f, 1f)
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
    fun isShowing(): Boolean =
        container?.visibility == View.VISIBLE && container?.parent != null && !dismissing

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
                launcherGestureFromBottom = event.y > card.height * BOTTOM_SWIPE_REGION_RATIO
                if (launcherGestureFromBottom) {
                    Log.d(TAG, "launcher gesture captured for panel dismissal")
                }
                return launcherGestureFromBottom
            }

            MotionEvent.ACTION_MOVE -> {
                if (!launcherGestureFromBottom) return false
                val dx = event.rawX - launcherGestureDownX
                val dy = event.rawY - launcherGestureDownY
                val slop = ViewConfiguration.get(card.context).scaledTouchSlop
                if (!launcherGestureDraggingUp
                    && dy < -slop && abs(dy) > abs(dx) * 1.15f) {
                    launcherGestureDraggingUp = true
                    card.animate().cancel()
                    Log.d(TAG, "launcher gesture upward drag captured")
                }
                if (launcherGestureDraggingUp) {
                    launcherGestureDismissDistance = max(0f, -dy)
                    applyLauncherDismissProgress(card, launcherGestureDismissDistance)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!launcherGestureFromBottom) return false
                val wasDragging = launcherGestureDraggingUp
                val threshold = max(
                    dp(card.context, 8),
                    min(card.height * 0.04f, dp(card.context, 18)),
                )
                Log.d(TAG, "launcher gesture up: dragging=" + wasDragging
                        + " distance=" + launcherGestureDismissDistance + " threshold=" + threshold)
                if (wasDragging && launcherGestureDismissDistance >= threshold) {
                    dismiss(true)
                } else {
                    resetLauncherDismissProgress(card)
                }
                launcherGestureFromBottom = false
                launcherGestureDraggingUp = false
                launcherGestureDismissDistance = 0f
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!launcherGestureFromBottom) return false
                if (launcherGestureDraggingUp) {
                    resetLauncherDismissProgress(card)
                }
                launcherGestureFromBottom = false
                launcherGestureDraggingUp = false
                launcherGestureDismissDistance = 0f
                return true
            }
        }
        return launcherGestureFromBottom
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

        val root = FrameLayout(activity).apply {
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
            background = fullPanelBackground(dark, activity)
            isClickable = true
        }

        if (restartRequired) {
            card.addView(
                TextView(activity).apply {
                    text = strings.restartRequired
                    textSize = ROW_TITLE_TEXT_SP
                    gravity = Gravity.CENTER
                    setTextColor(if (dark) Color.WHITE else Color.BLACK)
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

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(safeLeft, dp(activity, 14).toInt(), 0, 0)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val disableInitial = PrefsBridge.readDisableLongPressForRemote()
        val removeInitial = PrefsBridge.readRemoveWallpaperLimitForRemote()
        val removeAppCardInitial = PrefsBridge.readRemoveAppCardLimitForRemote()
        content.addView(
            switchRow(
                activity = activity,
                dark = dark,
                title = strings.disableTitle,
                summaryOn = strings.disableOn,
                summaryOff = strings.disableOff,
                saveFailed = strings.saveFailed,
                initial = disableInitial,
                onChange = { PrefsBridge.requestDisableLongPressWrite(activity, it) },
            ),
        )
        content.addView(
            switchRow(
                activity = activity,
                dark = dark,
                title = strings.removeTitle,
                summaryOn = strings.removeOn,
                summaryOff = strings.removeOff,
                saveFailed = strings.saveFailed,
                initial = removeInitial,
                onChange = { PrefsBridge.requestRemoveWallpaperLimitWrite(activity, it) },
            ),
        )
        content.addView(
            switchRow(
                activity = activity,
                dark = dark,
                title = strings.removeAppCardTitle,
                summaryOn = strings.removeAppCardOn,
                summaryOff = strings.removeAppCardOff,
                saveFailed = strings.saveFailed,
                initial = removeAppCardInitial,
                onChange = { PrefsBridge.requestRemoveAppCardLimitWrite(activity, it) },
            ),
        )
        content.addView(
            Space(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(activity, 8).toInt(),
                )
            },
        )

        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content)
        }
        card.addView(scroll)
        root.addView(card)
        root.requestFocus()

        return BuiltPanel(root, card, safeLeft)
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

    private fun switchRow(
        activity: Activity,
        dark: Boolean,
        title: String,
        summaryOn: String,
        summaryOff: String,
        saveFailed: String,
        initial: Boolean,
        onChange: (Boolean) -> Boolean,
    ): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, 64).toInt()
            setPadding(
                dp(activity, 12).toInt(),
                dp(activity, 5).toInt(),
                dp(activity, 18).toInt(),
                dp(activity, 5).toInt(),
            )
            isClickable = true
            isFocusable = true
        }

        val summary = TextView(activity).apply {
            text = if (initial) summaryOn else summaryOff
            textSize = ROW_SUMMARY_TEXT_SP
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(
                if (dark) Color.argb(0x99, 255, 255, 255)
                else Color.argb(0x99, 0, 0, 0),
            )
            setPadding(0, dp(activity, 1).toInt(), 0, 0)
        }
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(activity, 4).toInt()
            }
            addView(
                TextView(activity).apply {
                    text = title
                    textSize = ROW_TITLE_TEXT_SP
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(if (dark) Color.WHITE else Color.BLACK)
                },
            )
            addView(summary)
        }

        val toggle = MiuixStyleSwitch(activity, dark).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(activity, 49).toInt(),
                dp(activity, 28).toInt(),
            )
            contentDescription = title
        }

        var reverting = false
        toggle.isChecked = initial
        toggle.setOnCheckedChangeListener { checked ->
            if (reverting) return@setOnCheckedChangeListener
            val saved = try {
                onChange(checked)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to persist panel switch: $title", t)
                false
            }
            if (saved) {
                summary.text = if (checked) summaryOn else summaryOff
                Log.d(TAG, "Panel switch saved: $title=$checked")
                DiagnosticLogStore.recordRemote(activity, "panel switch saved: $title=$checked")
            } else {
                reverting = true
                toggle.isChecked = !checked
                reverting = false
                summary.text = saveFailed
                Log.w(TAG, "Panel switch write rejected: $title")
                DiagnosticLogStore.recordRemote(activity, "panel switch rejected: $title")
            }
        }

        row.setOnClickListener { toggle.toggle() }
        row.addView(texts)
        row.addView(toggle)
        return row
    }

    private fun fullPanelBackground(dark: Boolean, context: Context): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor(if (dark) "#1e1e20" else "#f6f6f8"))
            cornerRadius = dp(context, PANEL_CORNER_RADIUS_DP)
        }
    }

    private fun removePanel(expectedRoot: FrameLayout?) {
        if (expectedRoot == null) {
            clearReferences()
            return
        }
        expectedRoot.animate().cancel()
        panel?.animate()?.cancel()
        val parent = expectedRoot.parent as? ViewGroup
        // 关闭手势由 Activity 级 dispatchTouchEvent hook 整体消费，父 ViewGroup 收不到 UP，
        // 无法自行复位 FLAG_DISALLOW_INTERCEPT（SwipeDismissCard 上滑时置位）。该标记残留会让
        // 宿主手势层持续收不到触摸，表现为关闭面板后要再滑一次长按才恢复。移除前显式清掉。
        panel?.parent?.requestDisallowInterceptTouchEvent(false)
        clearGestureExclusion(expectedRoot.rootView)
        parent?.removeView(expectedRoot)
        if (container === expectedRoot) clearReferences()
    }

    private fun clearReferences() {
        dragGeneration++
        container = null
        panel = null
        originRect = null
        hostActivity.clear()
        dismissing = false
        openingDrag = false
        dragAttachPending = false
        pendingDragTop = 0f
        pendingSettleOpen = null
        pendingVelocityY = 0f
        launcherGestureFromBottom = false
        launcherGestureDraggingUp = false
        launcherGestureDownX = 0f
        launcherGestureDownY = 0f
        launcherGestureDismissDistance = 0f
        launcherTouchDownX = 0f
        launcherTouchDownY = 0f
        launcherSwipeClickSuppressUntil = 0L
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

    private class PanelStrings(activity: Activity, allowModuleResources: Boolean) {
        private val chinese = activity.resources.configuration.locales[0]?.language == "zh"
        private val moduleContext = if (allowModuleResources) try {
            activity.createPackageContext(Constants.MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        } catch (_: Throwable) {
            null
        } else null

        private fun get(id: Int, fallback: String): String = try {
            moduleContext?.getString(id) ?: fallback
        } catch (_: Throwable) {
            fallback
        }

        val title = get(R.string.panel_title, if (chinese) "背屏快捷面板" else "Rear Screen Quick Panel")
        // An outdated process must be able to show this without reading the new APK's resource IDs.
        val restartRequired = get(
            R.string.panel_restart_required,
            if (chinese) "请重启背屏作用域" else "Please restart the rear screen scope",
        )
        val disableTitle = get(R.string.panel_disable_long_press_title, "禁用背屏长按切换壁纸")
        val disableOn = get(R.string.panel_disable_long_press_on, "当前已禁用原厂长按切换壁纸")
        val disableOff = get(R.string.panel_disable_long_press_off, "当前恢复原厂长按行为")
        val removeTitle = get(R.string.panel_remove_limit_title, "去除背屏壁纸数量限制")
        val removeOn = get(R.string.panel_remove_limit_on, "当前已去除15张上限")
        val removeOff = get(R.string.panel_remove_limit_off, "当前保持默认15张上限")
        val removeAppCardTitle = get(R.string.panel_remove_app_card_limit_title, "移除背屏应用卡数量限制")
        val removeAppCardOn = get(R.string.panel_remove_app_card_limit_on, "当前已去除15个应用卡上限")
        val removeAppCardOff = get(R.string.panel_remove_app_card_limit_off, "当前保持默认15个应用卡上限")
        val saveFailed = get(R.string.panel_save_failed, "保存失败，请在主应用中修改")
    }

    /**
     * 在不抢走 Switch 点击的前提下识别底部上滑：只有起点落在底部区域并且纵向位移
     * 超过 touchSlop 后才拦截，此时系统会向原子控件发送 ACTION_CANCEL。
     */
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

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    draggingUp = false
                    dismissDistance = 0f
                    fromBottom = event.y > height * BOTTOM_SWIPE_REGION_RATIO
                    Log.d(TAG, "dismiss card down: y=" + event.y + " height=" + height
                            + " fromBottom=" + fromBottom)
                }

                MotionEvent.ACTION_MOVE -> {
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
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
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
                        Log.d(TAG, "dismiss card up released: distance=" + dismissDistance
                                + " threshold=" + threshold)
                        if (dismissDistance >= threshold) {
                            onDismiss(true)
                        } else {
                            resetLauncherDismissProgress(this)
                        }
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
