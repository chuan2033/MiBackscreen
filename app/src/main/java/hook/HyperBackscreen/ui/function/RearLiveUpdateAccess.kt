package hook.HyperBackscreen.ui.function

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import hook.HyperBackscreen.ui.recorder.RootShell

/** 背屏中心已有通知监听服务；授权状态以系统记录为准。 */
internal object RearLiveUpdateAccess {
    private val listener = ComponentName(
        "com.xiaomi.subscreencenter",
        "com.xiaomi.subscreencenter.smartassistant.SmartAssistantNotificationListenerService",
    )

    fun isAvailable(context: Context): Boolean = try {
        context.packageManager.getServiceInfo(listener, PackageManager.ComponentInfoFlags.of(0))
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun isGranted(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == listener }
    }

    fun setGranted(granted: Boolean): Boolean {
        val action = if (granted) "allow_listener" else "disallow_listener"
        val command = "cmd notification $action ${listener.flattenToShortString()}\n"
        return RootShell.run(command, timeoutSeconds = 25, maxStdoutBytes = 8 * 1024)?.exitCode == 0
    }
}
