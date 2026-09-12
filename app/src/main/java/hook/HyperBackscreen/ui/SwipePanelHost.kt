package hook.HyperBackscreen.ui

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
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
    private const val DISMISS_ANIMATION_MS = 180L
    private const val SETTLE_MIN_MS = 100L
    private const val SETTLE_MAX_MS = 320L
    private const val HEADER_TEXT_SP = 19f
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
    private var hostActivity = WeakReference<Activity>(null)
    private var dismissing = false
    private var openingDrag = false
    private var dragAttachPending = false
    private var pendingDragTop = 0f
    private var pendingSettleOpen: Boolean? = null
    private var pendingVelocityY = 0f
    private var dragGeneration = 0L

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

    private fun attachPanel(activity: Activity, decor: ViewGroup): BuiltPanel {
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

        decor.addView(built.root)
        built.root.requestFocus()
        Log.d(TAG, "Panel drag started: safeLeft=${built.safeLeft}px")
        return built
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
        val root = container ?: return
        if (dismissing) return
        dismissing = true

        val card = panel
        if (card == null || card.height <= 0) {
            removePanel(root)
            return
        }
        card.animate().cancel()
        card.animate()
            .translationY(root.height.toFloat())
            .setDuration(DISMISS_ANIMATION_MS)
            .withEndAction { removePanel(root) }
            .start()
    }

    @JvmStatic
    fun isShowing(): Boolean =
        container?.visibility == View.VISIBLE && container?.parent != null && !dismissing

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

        val card = SwipeDismissCard(activity) { dismiss() }.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM,
            )
            background = fullPanelBackground(dark)
            isClickable = true
        }

        card.addView(handleBar(activity, dark))
        card.addView(headerRow(activity, dark, strings.title, safeLeft))

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
            setPadding(safeLeft, 0, 0, 0)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val disableInitial = PrefsBridge.readDisableLongPressForRemote()
        val removeInitial = PrefsBridge.readRemoveWallpaperLimitForRemote()

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

    private fun handleBar(activity: Activity, dark: Boolean): View {
        val width = dp(activity, 36).toInt()
        val height = dp(activity, 4).toInt()
        return View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(width, height).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(activity, 7).toInt()
                bottomMargin = dp(activity, 3).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = height / 2f
                setColor(if (dark) Color.argb(0x66, 255, 255, 255) else Color.argb(0x33, 0, 0, 0))
            }
        }
    }

    private fun headerRow(
        activity: Activity,
        dark: Boolean,
        titleText: String,
        safeLeft: Int,
    ): LinearLayout {
        val horizontal = dp(activity, 12).toInt()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, 44).toInt()
            setPadding(safeLeft + horizontal, 0, dp(activity, 14).toInt(), 0)

            addView(
                TextView(activity).apply {
                    text = titleText
                    textSize = HEADER_TEXT_SP
                    setTextColor(if (dark) Color.WHITE else Color.BLACK)
                    setTypeface(null, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            addView(
                TextView(activity).apply {
                    text = "✕"
                    textSize = 22f
                    gravity = Gravity.CENTER
                    contentDescription = titleText
                    setTextColor(
                        if (dark) Color.argb(0xCC, 255, 255, 255)
                        else Color.argb(0x99, 0, 0, 0),
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        dp(activity, 44).toInt(),
                        dp(activity, 44).toInt(),
                    )
                    isClickable = true
                    setOnClickListener { dismiss() }
                },
            )
        }
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

    private fun fullPanelBackground(dark: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor(if (dark) "#1e1e20" else "#f6f6f8"))
        }
    }

    private fun removePanel(expectedRoot: FrameLayout?) {
        if (expectedRoot == null) {
            clearReferences()
            return
        }
        expectedRoot.animate().cancel()
        panel?.animate()?.cancel()
        (expectedRoot.parent as? ViewGroup)?.removeView(expectedRoot)
        if (container === expectedRoot) clearReferences()
    }

    private fun clearReferences() {
        dragGeneration++
        container = null
        panel = null
        hostActivity.clear()
        dismissing = false
        openingDrag = false
        dragAttachPending = false
        pendingDragTop = 0f
        pendingSettleOpen = null
        pendingVelocityY = 0f
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
        val saveFailed = get(R.string.panel_save_failed, "保存失败，请在主应用中修改")
    }

    /**
     * 在不抢走 Switch 点击的前提下识别向下拖动：只有纵向位移超过 touchSlop 后才拦截，
     * 此时系统会向原子控件发送 ACTION_CANCEL，后续事件由面板完成关闭动画。
     */
    private class SwipeDismissCard(
        context: Context,
        private val onDismiss: () -> Unit,
    ) : LinearLayout(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var dragging = false

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (dy > touchSlop && dy > abs(dx) * 1.15f) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && dy > touchSlop && dy > abs(dx) * 1.15f) dragging = true
                    if (dragging) {
                        translationY = max(0f, dy)
                        return true
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val threshold = max(
                            dp(context, 24),
                            min(height * 0.22f, dp(context, 48)),
                        )
                        if (translationY >= threshold) {
                            onDismiss()
                        } else {
                            animate().translationY(0f).setDuration(150L).start()
                        }
                        dragging = false
                        return true
                    }
                    return performClick()
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) animate().translationY(0f).setDuration(150L).start()
                    dragging = false
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean = super.performClick()
    }
}
