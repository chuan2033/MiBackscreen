package hook.HyperBackscreen.desktop

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import hook.HyperBackscreen.R
import hook.HyperBackscreen.bridge.RearCastClient
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.common.RearCastPolicy

/** 背屏桌面的入口操作：设置页、磁贴、控制条、通知共用。 */
internal object RearDesktopController {
    private const val TAG = Constants.LOG_TAG + ":RearDesktop"

    /** 请 system_server 在背屏上打开背屏桌面；失败时 Toast 说明原因。 */
    fun open(context: Context, onResult: (RearCastPolicy.Outcome) -> Unit = {}) {
        val appContext = context.applicationContext
        RearCastClient.send(appContext, Constants.REAR_CAST_OP_DESKTOP_OPEN) { outcome, _ ->
            if (outcome != RearCastPolicy.Outcome.DONE) toast(appContext, failureMessage(appContext, outcome))
            onResult(outcome)
        }
    }

    /** 退出：桌面 Activity 结束自己，前台服务结束会话并恢复背屏设置。 */
    fun exit(context: Context) {
        RearDesktopState.requestExit()
        context.applicationContext.stopService(Intent(context, RearDesktopService::class.java))
    }

    /** 会话运行中改了密度：让服务把新密度交给 system_server。 */
    fun refreshDensity(context: Context) {
        if (!RearDesktopState.running.value) return
        try {
            context.startService(Intent(context, RearDesktopService::class.java).setAction(RearDesktopService.ACTION_REFRESH))
        } catch (error: Exception) {
            Log.w(TAG, "Refresh density failed", error)
        }
    }

    fun failureMessage(context: Context, outcome: RearCastPolicy.Outcome): String = when (outcome) {
        RearCastPolicy.Outcome.LOCKED -> context.getString(R.string.rear_desktop_locked)
        RearCastPolicy.Outcome.NOT_ACTIVE -> context.getString(R.string.rear_cast_not_active)
        RearCastPolicy.Outcome.TIMEOUT -> context.getString(R.string.rear_cast_timeout)
        RearCastPolicy.Outcome.DENIED -> context.getString(R.string.rear_cast_denied)
        RearCastPolicy.Outcome.API_UNAVAILABLE -> context.getString(R.string.rear_cast_api_unavailable)
        else -> context.getString(R.string.rear_desktop_failed)
    }

    fun toast(context: Context, text: String) {
        try {
            Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Log.w(TAG, "Toast failed", error)
        }
    }
}
