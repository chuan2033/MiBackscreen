package hook.HyperBackscreen.ui.util

import java.io.File
import java.util.concurrent.TimeUnit

internal object RootAccess {
    /**
     * 首次检测会弹出 Magisk / KSU 授权框，要给用户留出点「允许」的时间；
     * 5 秒太短，用户还没点完就超时被判成「未授权」。
     */
    private const val GRANT_TIMEOUT_SECONDS = 20L

    fun isGranted(startProcess: () -> Process = {
        ProcessBuilder("su", "-c", "test \"\$(id -u)\" = 0")
            .redirectOutput(File("/dev/null"))
            .redirectError(File("/dev/null"))
            .start()
    }): Boolean {
        var process: Process? = null
        return try {
            process = startProcess()
            process.outputStream.close()
            process.waitFor(GRANT_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0
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
