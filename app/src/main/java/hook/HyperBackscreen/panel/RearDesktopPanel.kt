package hook.HyperBackscreen.panel

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import hook.HyperBackscreen.R
import hook.HyperBackscreen.bridge.DiagnosticLogStore
import hook.HyperBackscreen.bridge.RearCastClient
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.common.RearCastPolicy
import hook.HyperBackscreen.common.RearDesktopSession
import java.lang.ref.WeakReference
import java.util.UUID

/**
 * 背屏中心进程里的背屏桌面客户端：面板点选应用时发启动请求，接收 system_server 的会话状态通知，
 * 显示 / 释放控制条，并在「回背屏首页」后的首页恢复时重新展示面板。
 *
 * 会话是否存在只看 system_server：宿主重建、首页恢复或请求超时时查询一次系统状态。
 * 全部方法在主线程调用。
 */
internal object RearDesktopPanel {
    private const val TAG = Constants.LOG_TAG
    /** 回首页之后等待首页恢复并重开面板的期限。 */
    private const val HOME_REOPEN_WINDOW_MS = 5_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var receiverRegistered = false
    private var launchInFlight = false
    private var resumedLauncher = WeakReference<Activity>(null)
    private var launcherResumeGeneration = 0L
    /** 最近一次「回背屏首页」：会话编号、请求时的首页恢复代次与时间。 */
    private var pendingHome: PendingHome? = null

    private class PendingHome(val sessionId: Int, val resumeGeneration: Long, val requestedAt: Long) {
        var ended = false
    }

    /** 面板等待结果期间置灰应用区。 */
    fun isLaunchInFlight(): Boolean = launchInFlight

    /** 宿主进程启动或首页创建时调用：注册状态通知并查询一次系统状态。重复调用只查询。 */
    @JvmStatic
    fun ensureStarted(context: Context) {
        val app = context.applicationContext ?: context
        if (appContext == null) appContext = app
        if (!receiverRegistered) {
            try {
                app.registerReceiver(StatusReceiver, IntentFilter(Constants.ACTION_REAR_DESKTOP_STATUS),
                    null, mainHandler, Context.RECEIVER_NOT_EXPORTED)
                val packages = IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addAction(Intent.ACTION_PACKAGE_CHANGED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addDataScheme("package")
                }
                app.registerReceiver(PackageReceiver, packages, null, mainHandler, Context.RECEIVER_NOT_EXPORTED)
                receiverRegistered = true
            } catch (t: Throwable) {
                Log.w(TAG, "Rear desktop receivers unavailable", t)
            }
            SwipePanelHost.preloadApps(app)
        }
        query(app)
    }

    @JvmStatic
    fun onLauncherResumed(activity: Activity) {
        resumedLauncher = WeakReference(activity)
        launcherResumeGeneration++
        ensureStarted(activity)
        maybeReopenPanel()
    }

    @JvmStatic
    fun onLauncherPaused(activity: Activity) {
        if (resumedLauncher.get() === activity) resumedLauncher.clear()
    }

    /**
     * 点选应用。[onDone] 在允许再次点选时回调一次，请求被拒绝时带提示文字；受理后为 null，
     * 后续的启动失败经状态通知提示。
     */
    fun launch(activity: Activity, component: ComponentName, onDone: (String?) -> Unit) {
        if (launchInFlight) return
        launchInFlight = true
        val requestId = UUID.randomUUID().toString()
        val extras = Bundle().apply {
            putInt(Constants.EXTRA_REAR_DESKTOP_PROTOCOL, Constants.REAR_DESKTOP_PROTOCOL_VERSION)
            putString(Constants.EXTRA_REAR_DESKTOP_REQUEST_ID, requestId)
            putString(Constants.EXTRA_REAR_DESKTOP_COMPONENT, component.flattenToString())
        }
        val app = activity.applicationContext
        RearCastClient.send(activity, Constants.REAR_CAST_OP_PANEL_LAUNCH, extras) { outcome, _, result ->
            DiagnosticLogStore.recordRemoteAsync(app,
                "rear_desktop launch request=$requestId component=${component.flattenToShortString()} outcome=$outcome")
            if (outcome == RearCastPolicy.Outcome.TIMEOUT) {
                // 系统可能已经受理：先查询状态，查询返回后才允许再次点选。
                query(app) {
                    launchInFlight = false
                    onDone(message(app, outcome))
                }
                return@send
            }
            launchInFlight = false
            if (outcome == RearCastPolicy.Outcome.ACCEPTED) {
                applySnapshot(result)
                onDone(null)
            } else {
                onDone(message(app, outcome))
            }
        }
    }

    /** 控制条的四个动作。 */
    fun control(context: Context, op: String, sessionId: Int) {
        if (op == Constants.REAR_CAST_OP_PANEL_HOME) {
            pendingHome = PendingHome(sessionId, launcherResumeGeneration, SystemClock.uptimeMillis())
        } else if (op == Constants.REAR_CAST_OP_PANEL_EXIT) {
            pendingHome = null
        }
        val extras = Bundle().apply {
            putInt(Constants.EXTRA_REAR_DESKTOP_PROTOCOL, Constants.REAR_DESKTOP_PROTOCOL_VERSION)
            putInt(Constants.EXTRA_REAR_DESKTOP_SESSION_ID, sessionId)
        }
        val app = context.applicationContext
        RearCastClient.send(context, op, extras) { outcome, _, result ->
            DiagnosticLogStore.recordRemoteAsync(app, "rear_desktop control op=$op session=$sessionId outcome=$outcome")
            if (outcome != RearCastPolicy.Outcome.DONE) {
                Log.w(TAG, "Rear desktop: $op -> $outcome")
                if (op == Constants.REAR_CAST_OP_PANEL_HOME) pendingHome = null
                if (outcome == RearCastPolicy.Outcome.TIMEOUT) query(app) else applySnapshot(result)
            }
        }
    }

    /** 控制条窗口加不上：通过系统允许的导航回到背屏首页，会话随之回收。 */
    fun onControlsUnavailable(context: Context, sessionId: Int) {
        Log.w(TAG, "Rear desktop: controls unavailable, returning home session=$sessionId")
        control(context, Constants.REAR_CAST_OP_PANEL_HOME, sessionId)
    }

    private fun query(context: Context, done: () -> Unit = {}) {
        val extras = Bundle().apply {
            putInt(Constants.EXTRA_REAR_DESKTOP_PROTOCOL, Constants.REAR_DESKTOP_PROTOCOL_VERSION)
        }
        RearCastClient.send(context, Constants.REAR_CAST_OP_PANEL_QUERY, extras) { outcome, _, result ->
            if (outcome == RearCastPolicy.Outcome.DONE) applySnapshot(result)
            done()
        }
    }

    /** 按 system_server 的会话快照对齐控制条。 */
    private fun applySnapshot(extras: Bundle?) {
        if (extras == null) return
        val state = RearDesktopSession.State.fromWire(extras.getString(Constants.EXTRA_REAR_DESKTOP_STATE))
        val sessionId = extras.getInt(Constants.EXTRA_REAR_DESKTOP_SESSION_ID, 0)
        when (state) {
            RearDesktopSession.State.SHOWING -> showControls(sessionId)
            RearDesktopSession.State.IDLE -> {
                RearSessionControls.hide()
                if (extras.getString(Constants.EXTRA_REAR_DESKTOP_REASON) == RearDesktopSession.EndReason.HOME.wire) {
                    markHomeEnded(sessionId)
                }
            }
            else -> Unit
        }
    }

    private fun showControls(sessionId: Int) {
        val context = appContext ?: return
        RearSessionControls.show(context, sessionId)
    }

    private object StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // RECEIVER_NOT_EXPORTED 只收本应用与系统的广播；公开了身份时再核对是 system。
            val sender = try {
                sentFromUid
            } catch (_: Throwable) {
                -1
            }
            if (sender != -1 && sender != Process.SYSTEM_UID) {
                Log.w(TAG, "Rear desktop status from uid $sender ignored")
                return
            }
            val sessionId = intent.getIntExtra(Constants.EXTRA_REAR_DESKTOP_SESSION_ID, 0)
            val state = RearDesktopSession.State.fromWire(intent.getStringExtra(Constants.EXTRA_REAR_DESKTOP_STATE))
            val reason = intent.getStringExtra(Constants.EXTRA_REAR_DESKTOP_REASON)
            val code = intent.getIntExtra(Constants.EXTRA_REAR_DESKTOP_RESULT, Constants.REAR_CAST_RESULT_DONE)
            DiagnosticLogStore.recordRemoteAsync(context,
                "rear_desktop status session=$sessionId state=${state?.wire} reason=$reason")
            when (state) {
                RearDesktopSession.State.SHOWING -> showControls(sessionId)
                RearDesktopSession.State.IDLE -> {
                    RearSessionControls.hide(sessionId)
                    when {
                        reason == RearDesktopSession.EndReason.HOME.wire -> markHomeEnded(sessionId)
                        isLaunchFailure(reason) -> showFailure(context, RearCastPolicy.outcomeOf(code))
                    }
                }
                else -> Unit
            }
        }
    }

    /** 应用安装、卸载、变更后让面板的应用列表重新加载。 */
    private object PackageReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            SwipePanelHost.invalidateApps(context.applicationContext ?: context)
        }
    }

    private fun isLaunchFailure(reason: String?): Boolean =
        RearDesktopSession.EndReason.entries.firstOrNull { it.wire == reason }?.isLaunchFailure == true

    private fun markHomeEnded(sessionId: Int) {
        val pending = pendingHome ?: return
        if (pending.sessionId != sessionId) return
        pending.ended = true
        maybeReopenPanel()
    }

    /** 回首页的会话已结束、首页在本次请求之后恢复过：重新展示面板。 */
    private fun maybeReopenPanel() {
        val pending = pendingHome ?: return
        if (SystemClock.uptimeMillis() - pending.requestedAt > HOME_REOPEN_WINDOW_MS) {
            pendingHome = null
            return
        }
        if (!pending.ended || launcherResumeGeneration <= pending.resumeGeneration) return
        val launcher = resumedLauncher.get() ?: return
        if (launcher.isFinishing || launcher.isDestroyed) return
        pendingHome = null
        if (!SwipePanelHost.isShowing()) SwipePanelHost.show(launcher, null)
    }

    /** 启动失败：面板还在时显示在面板上，否则在背屏上提示。 */
    private fun showFailure(context: Context, outcome: RearCastPolicy.Outcome) {
        val text = message(context, outcome)
        if (SwipePanelHost.showStatusIfShowing(text)) return
        try {
            val display = context.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Constants.SYSTEM_SECONDARY_DISPLAY_ID)
            val target = display?.let { context.createDisplayContext(it) } ?: context
            Toast.makeText(target, text, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Log.w(TAG, "Rear desktop failure toast skipped", t)
        }
    }

    fun message(context: Context, outcome: RearCastPolicy.Outcome): String {
        val text = PanelText(context)
        return when (outcome) {
            RearCastPolicy.Outcome.LOCKED ->
                text.get(R.string.rear_desktop_launch_locked, "请先解锁主屏", "Unlock the main screen first")
            RearCastPolicy.Outcome.ROM_DENIED ->
                text.get(R.string.rear_desktop_launch_rom_denied, "系统不允许在背屏显示", "Not allowed on the rear screen")
            RearCastPolicy.Outcome.BAD_COMPONENT ->
                text.get(R.string.rear_desktop_launch_bad_component, "无法打开这个应用", "Cannot open this app")
            RearCastPolicy.Outcome.BUSY ->
                text.get(R.string.rear_desktop_launch_busy, "背屏正忙，请稍后再试", "Busy, try again")
            RearCastPolicy.Outcome.NOT_ACTIVE ->
                text.get(R.string.rear_cast_not_active, "未生效，请重启手机", "Not active, reboot the phone")
            RearCastPolicy.Outcome.TIMEOUT ->
                text.get(R.string.rear_cast_timeout, "系统无响应，请稍后再试", "System did not respond, try again")
            RearCastPolicy.Outcome.DENIED ->
                text.get(R.string.rear_cast_denied, "请求被系统拒绝", "Request rejected by the system")
            RearCastPolicy.Outcome.API_UNAVAILABLE ->
                text.get(R.string.rear_cast_api_unavailable, "系统接口不可用", "System API unavailable")
            else -> text.get(R.string.rear_desktop_launch_failed, "无法在背屏打开", "Could not open on the rear screen")
        }
    }
}

/** 宿主进程里读模块文案；模块资源不可用时用内置的中英文。 */
internal class PanelText(context: Context) {
    private val chinese = context.resources.configuration.locales[0]?.language == "zh"
    private val moduleContext: Context? = try {
        context.createPackageContext(Constants.MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
            .createConfigurationContext(context.resources.configuration)
    } catch (_: Throwable) {
        null
    }

    val module: Context?
        get() = moduleContext

    fun get(id: Int, zh: String, en: String): String = try {
        moduleContext?.getString(id) ?: if (chinese) zh else en
    } catch (_: Throwable) {
        if (chinese) zh else en
    }
}
