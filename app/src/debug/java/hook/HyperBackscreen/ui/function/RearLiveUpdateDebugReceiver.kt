package hook.HyperBackscreen.ui.function

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import hook.HyperBackscreen.common.Constants

/** 通过 adb 发布演示通知，供设备日志验证使用。 */
class RearLiveUpdateDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_POST) return
        Log.i(Constants.LOG_TAG, "Rear Live Update demo: ${RearLiveUpdateDemo.post(context)}")
    }

    companion object {
        private const val ACTION_POST = "hook.HyperBackscreen.action.POST_REAR_LIVE_UPDATE_DEMO"
    }
}
