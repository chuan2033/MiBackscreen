package hook.HyperBackscreen.ui.function

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.Constants

/** 用户主动发起的两分钟实时通知，用于核对背屏中心的显示通路。 */
internal object RearLiveUpdateDemo {
    private const val CHANNEL_ID = "rear_live_update_demo"
    private const val NOTIFICATION_ID = 0x4c55
    const val ACTION_STOP = "hook.HyperBackscreen.action.STOP_REAR_LIVE_UPDATE_DEMO"
    private const val DURATION_MS = 2 * 60 * 1000L

    enum class Result { POSTED, NOTIFICATIONS_DISABLED, PROMOTION_DISABLED, INVALID, FAILED }

    fun post(context: Context): Result {
        return try {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return Result.NOTIFICATIONS_DISABLED
            if (!manager.canPostPromotedNotifications()) return Result.PROMOTION_DISABLED
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.rear_live_update_demo_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
            val stopIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, RearLiveUpdateDemoReceiver::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tile_rear_cast)
                .setContentTitle(context.getString(R.string.rear_live_update_demo_title))
                .setContentText(context.getString(R.string.rear_live_update_demo_text))
                .setStyle(
                    Notification.BigTextStyle().bigText(context.getString(R.string.rear_live_update_demo_text))
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setRequestPromotedOngoing(true)
                .setWhen(System.currentTimeMillis() + DURATION_MS)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setTimeoutAfter(DURATION_MS)
                .addAction(
                    Notification.Action.Builder(
                        null,
                        context.getString(R.string.rear_live_update_demo_stop),
                        stopIntent,
                    ).build()
                )
                .build()
            if (!notification.hasPromotableCharacteristics()) return Result.INVALID
            manager.notify(NOTIFICATION_ID, notification)
            Result.POSTED
        } catch (error: Exception) {
            Log.w(Constants.LOG_TAG, "Rear Live Update demo failed", error)
            Result.FAILED
        }
    }

    fun stop(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

/** 通知上的结束操作只处理模块自己发布的演示通知。 */
class RearLiveUpdateDemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == RearLiveUpdateDemo.ACTION_STOP) RearLiveUpdateDemo.stop(context)
    }
}
