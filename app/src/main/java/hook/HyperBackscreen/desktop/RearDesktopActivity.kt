package hook.HyperBackscreen.desktop

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.Constants
import kotlinx.coroutines.launch

/**
 * 背屏桌面：运行在背屏（display 1）上的应用列表，点哪个应用就在背屏上打开它。
 *
 * 清单里声明了 meta-data `miui.rear.policy = 1`，系统据此允许它显示在背屏；其它应用显示在背屏
 * 靠 system_server 里的 hook，只在会话期间（[RearDesktopService] 运行时）放行。
 * 在主屏上被打开时（例如从最近任务恢复）交给 system_server 改到背屏打开，自己结束。
 */
class RearDesktopActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (display?.displayId == Display.DEFAULT_DISPLAY) {
            RearDesktopController.open(this)
            finish()
            return
        }
        RearDesktopService.start(this)
        lifecycleScope.launch {
            RearDesktopState.exitRequests.collect { finishAndRemoveTask() }
        }
        setContent {
            RearDesktopScreen(
                onLaunch = ::launch,
                onExit = { RearDesktopController.exit(this) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        RearDesktopState.setDesktopResumed(true)
    }

    override fun onPause() {
        RearDesktopState.setDesktopResumed(false)
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing && display?.displayId != Display.DEFAULT_DISPLAY) {
            RearDesktopState.setDesktopResumed(false)
            stopService(Intent(this, RearDesktopService::class.java))
        }
        super.onDestroy()
    }

    private fun launch(component: ComponentName) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val displayId = display?.displayId ?: Constants.SYSTEM_SECONDARY_DISPLAY_ID
        try {
            startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        } catch (error: Exception) {
            Log.w(TAG, "Launch $component failed", error)
            RearDesktopController.toast(this, getString(R.string.rear_desktop_launch_failed))
        }
    }

    private companion object {
        const val TAG = Constants.LOG_TAG + ":RearDesktop"
    }
}
