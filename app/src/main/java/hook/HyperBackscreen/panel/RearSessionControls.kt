package hook.HyperBackscreen.panel

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.Constants

/**
 * 背屏应用显示期间的控制条：贴在背屏右侧（摄像头在左侧），收起时是一个把手，点开后是
 * 返回 / 背屏首页 / 移到主屏 / 退出。背屏没有导航栏，这是离开背屏应用的入口。
 *
 * 由背屏中心进程管理，独立于面板 View 的释放：会话进入显示中时 [show]，会话结束时 [hide]。
 * 窗口挂在背屏的 window Context 上，类型 TYPE_APPLICATION_OVERLAY 并标记为系统应用悬浮窗
 * （背屏中心持有 SYSTEM_APPLICATION_OVERLAY）。窗口加不上时交给 [RearDesktopPanel.onControlsUnavailable]。
 * 尺寸按背屏当前密度换算，密度变化后重建。全部方法在主线程调用。
 */
internal object RearSessionControls {
    private const val TAG = Constants.LOG_TAG
    private const val AUTO_COLLAPSE_MS = 5_000L
    private const val EXPANDED_MARGIN_DP = 6
    private const val HANDLE_TOUCH_WIDTH_DP = 14
    private const val HANDLE_TOUCH_HEIGHT_DP = 52
    private const val HANDLE_BAR_WIDTH_DP = 5
    private const val HANDLE_BAR_HEIGHT_DP = 40

    private val handler = Handler(Looper.getMainLooper())
    private var sessionId = 0
    private var hostContext: Context? = null
    private var windowContext: Context? = null
    private var windowManager: WindowManager? = null
    private var root: View? = null
    private var expanded = false
    private val collapse = Runnable { setExpanded(false) }
    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            // 会话调整了背屏密度：按新密度重建。
            if (root != null) {
                detach()
                attach()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    @JvmStatic
    fun show(context: Context, session: Int) {
        if (session <= 0) return
        if (root != null && sessionId == session) return
        hide()
        sessionId = session
        hostContext = context.applicationContext ?: context
        val created = try {
            val host = hostContext!!
            host.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Constants.SYSTEM_SECONDARY_DISPLAY_ID)
                ?.let { host.createDisplayContext(it).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) }
        } catch (t: Throwable) {
            Log.w(TAG, "Rear desktop: controls window context unavailable", t)
            null
        }
        windowContext = created
        windowManager = created?.getSystemService(WindowManager::class.java)
        created?.registerComponentCallbacks(configCallbacks)
        expanded = false
        if (!attach()) {
            val ended = session
            release()
            RearDesktopPanel.onControlsUnavailable(context, ended)
            return
        }
        Log.i(TAG, "Rear desktop: controls shown session=$session")
    }

    /** 释放任意会话的控制条。 */
    @JvmStatic
    fun hide() {
        if (root != null) Log.i(TAG, "Rear desktop: controls released session=$sessionId")
        release()
    }

    /** 只释放 [session] 的控制条；旧会话的结束通知不影响新会话。 */
    @JvmStatic
    fun hide(session: Int) {
        if (session == sessionId) hide()
    }

    private fun release() {
        detach()
        try {
            windowContext?.unregisterComponentCallbacks(configCallbacks)
        } catch (_: Throwable) {
        }
        windowContext = null
        windowManager = null
        sessionId = 0
    }

    private fun detach() {
        handler.removeCallbacks(collapse)
        val view = root ?: return
        root = null
        try {
            windowManager?.removeViewImmediate(view)
        } catch (t: Throwable) {
            Log.w(TAG, "Rear desktop: remove controls failed", t)
        }
    }

    private fun attach(): Boolean {
        val context = windowContext ?: return false
        val manager = windowManager ?: return false
        val view = buildView(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = if (expanded) dp(context, EXPANDED_MARGIN_DP) else 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "MiBackscreen rear controls"
        }
        try {
            WindowManager.LayoutParams::class.java
                .getMethod("setSystemApplicationOverlay", Boolean::class.javaPrimitiveType)
                .invoke(params, true)
        } catch (t: Throwable) {
            Log.d(TAG, "Rear desktop: system application overlay flag unavailable: $t")
        }
        return try {
            manager.addView(view, params)
            root = view
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Rear desktop: add controls failed", t)
            false
        }
    }

    private fun buildView(context: Context): View {
        val text = PanelText(context)
        val resources = text.module ?: context
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val session = sessionId
        fun action(op: String) = { RearDesktopPanel.control(context, op, session) }
        val buttons = listOf(
            button(context, resources, R.drawable.ic_rear_desktop_back,
                text.get(R.string.rear_desktop_control_back, "返回", "Back"), action(Constants.REAR_CAST_OP_PANEL_BACK)),
            button(context, resources, R.drawable.ic_tile_rear_desktop,
                text.get(R.string.rear_desktop_control_home, "背屏首页", "Rear home"), action(Constants.REAR_CAST_OP_PANEL_HOME)),
            button(context, resources, R.drawable.ic_rear_desktop_to_main,
                text.get(R.string.rear_desktop_control_to_main, "移到主屏", "Move to main screen"),
                action(Constants.REAR_CAST_OP_PANEL_TO_MAIN)),
            button(context, resources, R.drawable.ic_rear_desktop_exit,
                text.get(R.string.rear_desktop_control_exit, "退出", "Exit"), action(Constants.REAR_CAST_OP_PANEL_EXIT)),
        )
        val handle = edgeHandle(context, text.get(R.string.rear_desktop_control_expand, "展开控制条", "Show controls"))
        container.addView(handle)
        buttons.forEach { container.addView(it) }
        container.tag = Views(handle, buttons)
        applyExpanded(container)
        return container
    }

    private class Views(val handle: View, val buttons: List<View>)

    /**
     * 收起时的把手：贴住背屏右边缘的一根细竖条，触摸区只比竖条略宽，尽量不压住应用自己的按钮。
     * 白色竖条加深色描边，在深浅两种画面上都看得见。
     */
    private fun edgeHandle(context: Context, label: String): View = FrameLayout(context).apply {
        contentDescription = label
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(dp(context, HANDLE_TOUCH_WIDTH_DP), dp(context, HANDLE_TOUCH_HEIGHT_DP))
        addView(View(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                cornerRadius = dp(context, HANDLE_BAR_WIDTH_DP).toFloat()
                setColor(0xE6FFFFFF.toInt())
                setStroke(dp(context, 1).coerceAtLeast(1), 0x66000000)
            }
        }, FrameLayout.LayoutParams(dp(context, HANDLE_BAR_WIDTH_DP), dp(context, HANDLE_BAR_HEIGHT_DP),
            Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(context, 2) })
        setOnClickListener { setExpanded(true) }
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        handler.removeCallbacks(collapse)
        root?.let { applyExpanded(it) }
        if (value) handler.postDelayed(collapse, AUTO_COLLAPSE_MS)
    }

    /** 展开：按钮列带深色底、离边缘一点距离；收起：只剩贴边的把手，没有底色。 */
    private fun applyExpanded(view: View) {
        val views = view.tag as? Views ?: return
        val context = view.context
        views.handle.visibility = if (expanded) View.GONE else View.VISIBLE
        views.buttons.forEach { it.visibility = if (expanded) View.VISIBLE else View.GONE }
        if (expanded) {
            val padding = dp(context, 4)
            view.setPadding(padding, padding, padding, padding)
            view.background = GradientDrawable().apply {
                cornerRadius = dp(context, 22).toFloat()
                setColor(0xCC1E1E1E.toInt())
            }
        } else {
            view.setPadding(0, 0, 0, 0)
            view.background = null
        }
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val x = if (expanded) dp(context, EXPANDED_MARGIN_DP) else 0
        if (params.x != x) {
            params.x = x
            try {
                windowManager?.updateViewLayout(view, params)
            } catch (t: Throwable) {
                Log.w(TAG, "Rear desktop: update controls failed", t)
            }
        }
    }

    private fun button(context: Context, resources: Context, iconRes: Int, label: String, onClick: () -> Unit): View =
        icon(context, resources, iconRes, label).apply {
            setOnClickListener {
                setExpanded(false)
                onClick()
            }
        }

    private fun icon(context: Context, resources: Context, iconRes: Int, label: String): ImageView =
        ImageView(context).apply {
            try {
                setImageDrawable(resources.getDrawable(iconRes))
            } catch (t: Throwable) {
                Log.d(TAG, "Rear desktop: control icon unavailable: $t")
            }
            contentDescription = label
            val size = dp(context, 40)
            val padding = dp(context, 9)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                topMargin = dp(context, 2)
                bottomMargin = dp(context, 2)
            }
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
        }

    private fun dp(context: Context, value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
    ).toInt()
}
