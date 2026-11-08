package hook.HyperBackscreen.ui.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 背屏录屏的前台服务：录制期间托住模块 App 进程（录制、停止、保存都在 [RearScreenRecorder] 里跑），
 * 这样离开录屏页、App 退到后台、从控制中心磁贴开始都不会把录制带走。
 *
 * 通知显示录制计时和「停止并保存」；录制回到 Idle（停止保存完、启动失败）就撤掉通知并结束服务。
 */
class RearRecordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RearScreenRecorder.stop(this)
            // 通知里的停止按钮用普通 startService 发来：服务本来没在跑（进程被重建过）时直接结束。
            if (watcher == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        // startForegroundService 发来的：5 秒内必须进前台，先上通知再启动录制。
        try {
            startForeground(NOTIFICATION_ID, build(RearScreenRecorder.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (error: Exception) {
            Log.w(TAG, "startForeground failed", error)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        RearScreenRecorder.start(this)
        if (watcher == null) {
            watcher = scope.launch {
                RearScreenRecorder.state.collect { state ->
                    if (state == RearRecorderState.Idle) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        notificationManager().notify(NOTIFICATION_ID, build(state))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        watcher = null
        super.onDestroy()
    }

    private fun build(state: RearRecorderState): Notification {
        ensureChannel()
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_rear_record)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        when (state) {
            is RearRecorderState.Recording -> {
                // 计时由系统按 when 走，不用每秒更新通知。
                builder.setContentTitle(getString(R.string.rear_record_notification_title))
                    .setWhen(System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - state.startedAtElapsed))
                    .setUsesChronometer(true)
                    .setShowWhen(true)
                    .addAction(
                        Notification.Action.Builder(
                            null,
                            getString(R.string.rear_record_stop),
                            PendingIntent.getService(
                                this, 0,
                                Intent(this, RearRecordService::class.java).setAction(ACTION_STOP),
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                            ),
                        ).build()
                    )
            }
            RearRecorderState.Saving -> builder.setContentTitle(getString(R.string.rear_record_saving)).setShowWhen(false)
            else -> builder.setContentTitle(getString(R.string.rear_record_starting)).setShowWhen(false)
        }
        return builder.build()
    }

    private fun ensureChannel() {
        val manager = notificationManager()
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.rear_record_title), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val TAG = Constants.LOG_TAG + ":RearRecordService"
        private const val CHANNEL_ID = "rear_record"
        private const val NOTIFICATION_ID = 0x5245
        private const val ACTION_START = "hook.HyperBackscreen.action.REAR_RECORD_START"
        private const val ACTION_STOP = "hook.HyperBackscreen.action.REAR_RECORD_STOP"

        /** 开始录制（经前台服务）。返回 false 表示系统不允许此刻从后台启动前台服务。 */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, RearRecordService::class.java).setAction(ACTION_START))
            true
        } catch (error: Exception) {
            Log.w(TAG, "Cannot start foreground service", error)
            false
        }
    }
}
