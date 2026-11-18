package hook.HyperBackscreen.desktop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import hook.HyperBackscreen.R
import hook.HyperBackscreen.bridge.PrefsBridge
import hook.HyperBackscreen.bridge.RearCastClient
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.common.RearCastPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 背屏桌面会话的前台服务：背屏桌面 Activity 启动它，Activity 结束时停止它。
 *
 * - 持有会话令牌 [token]，开始时连同密度交给 system_server；服务所在进程被杀时 system_server
 *   收到令牌死亡通知，自己结束会话并恢复背屏设置。
 * - 背屏桌面被其它应用盖住时在背屏上显示浮动控制条（[RearDesktopOverlay]）。
 * - 通知里提供「退出背屏桌面」。
 */
class RearDesktopService : Service() {
    private val token = Binder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: RearDesktopOverlay? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RearDesktopController.exit(this)
            if (!started) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REFRESH && !started) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!started) {
            try {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } catch (error: Exception) {
                Log.w(TAG, "startForeground failed", error)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            started = true
            RearDesktopState.setRunning(true)
            startOverlay()
        }
        sendStart()
        return START_NOT_STICKY
    }

    private fun sendStart() {
        val extras = Bundle().apply {
            putBinder(Constants.EXTRA_REAR_DESKTOP_TOKEN, token)
            putInt(Constants.EXTRA_REAR_DESKTOP_DENSITY, PrefsBridge.readRearDesktopDensity(this@RearDesktopService))
        }
        RearCastClient.send(this, Constants.REAR_CAST_OP_DESKTOP_START, extras) { outcome, _ ->
            if (outcome != RearCastPolicy.Outcome.DONE) {
                Log.w(TAG, "Session start failed: $outcome")
                RearDesktopController.toast(this, RearDesktopController.failureMessage(this, outcome))
            }
        }
    }

    private fun startOverlay() {
        val controls = RearDesktopOverlay(this, object : RearDesktopOverlay.Actions {
            override fun back() = send(Constants.REAR_CAST_OP_BACK)
            override fun home() = RearDesktopController.open(this@RearDesktopService)
            override fun toMain() = send(Constants.REAR_CAST_OP_TO_MAIN)
            override fun exit() = RearDesktopController.exit(this@RearDesktopService)
        })
        overlay = controls
        scope.launch {
            RearDesktopState.desktopResumed.collect { resumed ->
                if (resumed) controls.hide() else controls.show()
            }
        }
    }

    private fun send(op: String) {
        RearCastClient.send(this, op) { outcome, _ ->
            if (!outcome.isSuccess && outcome != RearCastPolicy.Outcome.DONE) Log.w(TAG, "$op -> $outcome")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        overlay?.release()
        overlay = null
        if (started) {
            RearCastClient.send(applicationContext, Constants.REAR_CAST_OP_DESKTOP_STOP) { outcome, _ ->
                if (outcome != RearCastPolicy.Outcome.DONE) Log.w(TAG, "Session stop failed: $outcome")
            }
        }
        started = false
        RearDesktopState.setRunning(false)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.rear_desktop_title), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, RearDesktopService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_rear_desktop)
            .setContentTitle(getString(R.string.rear_desktop_notification_title))
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, getString(R.string.rear_desktop_exit), stop).build())
            .build()
    }

    companion object {
        private const val TAG = Constants.LOG_TAG + ":RearDesktop"
        private const val CHANNEL_ID = "rear_desktop"
        private const val NOTIFICATION_ID = 0x5244
        const val ACTION_STOP = "hook.HyperBackscreen.action.REAR_DESKTOP_STOP"
        const val ACTION_REFRESH = "hook.HyperBackscreen.action.REAR_DESKTOP_REFRESH"

        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, RearDesktopService::class.java))
            true
        } catch (error: Exception) {
            Log.w(TAG, "Cannot start foreground service", error)
            false
        }
    }
}
