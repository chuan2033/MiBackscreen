package hook.HyperBackscreen.ui.util

import android.util.Log
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.ui.RearScreenOffTimeout
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 把背屏息屏档位同步进系统设置 `subscreen_display_time`。
 *
 * 系统侧 PowerManagerServiceImpl 只认这个设置来算背屏（display group 1）的息屏倒计时，
 * Xposed Hook 是权威覆盖；这层是兜底 —— hook 没装上时系统也能按档位走。
 * 写系统设置需要 root，失败静默降级（不影响模块偏好本身）。
 */
internal object RearScreenTimeoutSync {
    private const val TAG = "MiBackscreen"
    private const val COMMAND_TIMEOUT_SECONDS = 10L

    /**
     * 独立的单线程执行器：不和共享的 BackgroundTasks（容量有限、还承载诊断日志）抢队列，
     * su 卡在授权弹窗时也不会把别的后台任务挤爆。
     */
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MiBackscreen-timeout-sync").apply { isDaemon = true }
    }

    /** 只保留最新一次请求：su 阻塞期间连续改档位，排队中的旧值直接被新值覆盖。 */
    private val latest = AtomicReference<RearScreenOffTimeout?>(null)

    fun apply(timeout: RearScreenOffTimeout) {
        // 已有一个排队中的同步任务时只替换它要写的值，不再追加任务。
        if (latest.getAndSet(timeout) != null) return
        // 后台执行：su 唤起可能阻塞，不能在 UI 线程跑。
        try {
            executor.execute(::drain)
        } catch (rejected: RejectedExecutionException) {
            // 执行器拒绝任务时静默降级：系统设置只是兜底，Hook 端仍按模块偏好生效。
            latest.compareAndSet(timeout, null)
            Log.w(TAG, "Rear screen off timeout sync rejected", rejected)
        }
    }

    private fun drain() {
        val timeout = latest.getAndSet(null) ?: return
        val ok = runAsRoot(buildCommand(timeout))
        Log.d(TAG, "Rear screen off timeout setting sync: value=${timeout.timeoutMillis} ok=$ok")
    }

    private fun buildCommand(timeout: RearScreenOffTimeout): String {
        val key = Constants.SYSTEM_SUBSCREEN_DISPLAY_TIME_SETTING
        return if (timeout.overridesSystem) {
            "settings put system $key ${timeout.timeoutMillis}"
        } else {
            // 跟随系统：清掉模块写过的值，让系统回落到自己的默认值。
            "settings delete system $key"
        }
    }

    private fun runAsRoot(command: String): Boolean {
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", command)
                .redirectOutput(File("/dev/null"))
                .redirectError(File("/dev/null"))
                .start()
            process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        } finally {
            process?.let {
                if (it.isAlive) it.destroyForcibly()
                runCatching { it.inputStream.close() }
                runCatching { it.errorStream.close() }
                runCatching { it.outputStream.close() }
            }
        }
    }
}
