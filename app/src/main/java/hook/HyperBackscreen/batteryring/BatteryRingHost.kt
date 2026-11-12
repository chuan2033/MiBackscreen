package hook.HyperBackscreen.batteryring

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.ViewGroup
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
            percentColors = {
                BatteryColorOverrides(
                    idle = PrefsBridge.readBatteryColor(module, Constants.KEY_BATTERY_PERCENT_COLOR_IDLE),
                    charging = PrefsBridge.readBatteryColor(module, Constants.KEY_BATTERY_PERCENT_COLOR_CHARGING),
                    low = PrefsBridge.readBatteryColor(module, Constants.KEY_BATTERY_PERCENT_COLOR_LOW),
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
            percentColors = {
                BatteryColorOverrides(
                    idle = PrefsBridge.readBatteryColorForRemote(Constants.KEY_BATTERY_PERCENT_COLOR_IDLE),
                    charging = PrefsBridge.readBatteryColorForRemote(Constants.KEY_BATTERY_PERCENT_COLOR_CHARGING),
                    low = PrefsBridge.readBatteryColorForRemote(Constants.KEY_BATTERY_PERCENT_COLOR_LOW),
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
        percentColors: () -> BatteryColorOverrides,
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
        val view = BatteryRingView(
            activity, geometry, colors(), percentColors(), position, layout(position),
        )
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
