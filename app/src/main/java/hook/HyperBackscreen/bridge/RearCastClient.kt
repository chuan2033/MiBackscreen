package hook.HyperBackscreen.bridge

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.common.RearCastPolicy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「应用投放到背屏」的发送方：背屏快捷面板（背屏中心进程）与控制中心磁贴（模块 App 进程）共用。
 *
 * 发一条只投递给 system_server（setPackage("android")）的有序广播，并用
 * [BroadcastOptions.setShareIdentityEnabled] 公开自己的身份供接收方鉴权；结果码从 resultCode
 * 取回，初始值 [Constants.REAR_CAST_RESULT_NOT_HANDLED] 原样回来说明系统侧没有接收器。
 * 回调总在主线程、且只回调一次（超时兜底）。
 */
object RearCastClient {
    private const val TAG = Constants.LOG_TAG
    private const val TIMEOUT_MS = 8_000L

    fun interface Callback {
        /** [outcome] 为结果；[packageName] 是被移动 / 查到的应用包名，可能为 null。 */
        fun onResult(outcome: RearCastPolicy.Outcome, packageName: String?)
    }

    @JvmStatic
    fun send(context: Context, op: String, callback: Callback) {
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        lateinit var timeout: Runnable
        fun finish(code: Int, data: String?) {
            if (!finished.compareAndSet(false, true)) return
            handler.removeCallbacks(timeout)
            val outcome = RearCastPolicy.outcomeOf(code)
            try {
                callback.onResult(outcome, data)
            } catch (t: Throwable) {
                Log.w(TAG, "Rear cast callback failed", t)
            }
        }
        timeout = Runnable { finish(Constants.REAR_CAST_RESULT_TIMEOUT, null) }
        try {
            val intent = Intent(Constants.ACTION_REAR_CAST)
                .setPackage(Constants.SYSTEM_FRAMEWORK_PACKAGE)
                .putExtra(Constants.EXTRA_REAR_CAST_OP, op)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            val options = BroadcastOptions.makeBasic()
                .setShareIdentityEnabled(true)
                .toBundle()
            handler.postDelayed(timeout, TIMEOUT_MS)
            context.sendOrderedBroadcast(
                intent,
                null,
                options,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        finish(resultCode, resultData)
                    }
                },
                handler,
                Constants.REAR_CAST_RESULT_NOT_HANDLED,
                null,
                null,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Rear cast request failed: $op", t)
            handler.post { finish(Constants.REAR_CAST_RESULT_NOT_HANDLED, null) }
        }
    }
}
