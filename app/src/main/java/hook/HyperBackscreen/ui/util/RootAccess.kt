package hook.HyperBackscreen.ui.util

import java.io.File
import java.util.concurrent.TimeUnit

internal object RootAccess {
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
            process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
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
