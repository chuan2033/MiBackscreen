package hook.HyperBackscreen.desktop

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.Constants

/**
 * 背屏上的浮动控制条：其它应用盖住背屏桌面时贴在背屏右侧，收起时是一个把手，点开后是
 * 返回 / 桌面 / 移到主屏 / 退出四个按钮。背屏没有导航栏，这是离开背屏应用的唯一入口。
 *
 * 用悬浮窗（TYPE_APPLICATION_OVERLAY）挂在背屏的窗口 Context 上，需要用户授予悬浮窗权限；
 * 没有权限时 [show] 什么也不做。尺寸按背屏当前密度换算，会话调整密度后重建。
 */
internal class RearDesktopOverlay(context: Context, private val actions: Actions) {
    interface Actions {
        fun back()
        fun home()
        fun toMain()
        fun exit()
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val windowContext: Context? = try {
        appContext.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Constants.SYSTEM_SECONDARY_DISPLAY_ID)
            ?.let { appContext.createDisplayContext(it).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) }
    } catch (error: Exception) {
        Log.w(TAG, "Rear window context unavailable", error)
        null
    }
    private val windowManager = windowContext?.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private var expanded = false
    private val collapse = Runnable { setExpanded(false) }
    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            // 会话调整了背屏密度：按新密度重建。
            if (root != null) rebuild()
        }

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    init {
        windowContext?.registerComponentCallbacks(configCallbacks)
    }

    val canShow: Boolean
        get() = windowManager != null && Settings.canDrawOverlays(appContext)

    fun show() {
        if (root != null || !canShow) return
        expanded = false
        attach()
    }

    fun hide() {
        handler.removeCallbacks(collapse)
        val view = root ?: return
        root = null
        try {
            windowManager?.removeViewImmediate(view)
        } catch (error: Exception) {
            Log.w(TAG, "Remove rear controls failed", error)
        }
    }

    fun release() {
        hide()
        try {
            windowContext?.unregisterComponentCallbacks(configCallbacks)
        } catch (_: Exception) {
        }
    }

    private fun rebuild() {
        hide()
        attach()
    }

    private fun attach() {
        val context = windowContext ?: return
        val manager = windowManager ?: return
        val view = buildView(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dp(context, 6)
            title = "MiBackscreen rear controls"
        }
        try {
            manager.addView(view, params)
            root = view
        } catch (error: Exception) {
            Log.w(TAG, "Add rear controls failed", error)
        }
    }

    private fun buildView(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val padding = dp(context, 4)
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 22).toFloat()
                setColor(0xCC1E1E1E.toInt())
            }
        }
        val buttons = listOf(
            button(context, R.drawable.ic_rear_desktop_back, R.string.rear_desktop_control_back) { actions.back() },
            button(context, R.drawable.ic_tile_rear_desktop, R.string.rear_desktop_control_home) { actions.home() },
            button(context, R.drawable.ic_rear_desktop_to_main, R.string.rear_desktop_control_to_main) { actions.toMain() },
            button(context, R.drawable.ic_rear_desktop_exit, R.string.rear_desktop_control_exit) { actions.exit() },
        )
        val handle = icon(context, R.drawable.ic_rear_desktop_handle, R.string.rear_desktop_control_expand).apply {
            setOnClickListener { setExpanded(true) }
        }
        container.addView(handle)
        buttons.forEach { container.addView(it) }
        container.tag = Views(handle, buttons)
        applyExpanded(container)
        return container
    }

    private class Views(val handle: View, val buttons: List<View>)

    private fun setExpanded(value: Boolean) {
        expanded = value
        handler.removeCallbacks(collapse)
        root?.let { applyExpanded(it) }
        if (value) handler.postDelayed(collapse, AUTO_COLLAPSE_MS)
    }

    private fun applyExpanded(view: View) {
        val views = view.tag as? Views ?: return
        views.handle.visibility = if (expanded) View.GONE else View.VISIBLE
        views.buttons.forEach { it.visibility = if (expanded) View.VISIBLE else View.GONE }
    }

    private fun button(context: Context, @DrawableRes iconRes: Int, @StringRes labelRes: Int, onClick: () -> Unit): View =
        icon(context, iconRes, labelRes).apply {
            setOnClickListener {
                setExpanded(false)
                onClick()
            }
        }

    private fun icon(context: Context, @DrawableRes iconRes: Int, @StringRes labelRes: Int): ImageView =
        ImageView(context).apply {
            setImageResource(iconRes)
            contentDescription = context.getString(labelRes)
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

    private companion object {
        const val TAG = Constants.LOG_TAG + ":RearDesktop"
        const val AUTO_COLLAPSE_MS = 5_000L
    }
}
