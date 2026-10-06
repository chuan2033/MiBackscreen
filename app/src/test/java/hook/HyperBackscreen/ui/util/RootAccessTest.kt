package hook.HyperBackscreen.ui.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RootAccessTest {
    @Test fun successfulRootCommandIsGranted() {
        assertTrue(RootAccess.isGranted { ProbeProcess(exitCode = 0) })
    }

    @Test fun deniedCommandIsNotGranted() {
        assertFalse(RootAccess.isGranted { ProbeProcess(exitCode = 1) })
    }

    @Test fun missingSuIsNotGranted() {
        assertFalse(RootAccess.isGranted { throw IOException("su unavailable") })
    }

    @Test fun timedOutCommandIsTerminated() {
        val process = ProbeProcess(exitCode = 0, completed = false)
        assertFalse(RootAccess.isGranted { process })
        assertTrue(process.destroyed)
        assertEquals(5_000L, process.timeoutMillis)
    }

    @Test fun returningAfterAuthorizationUsesFreshResult() {
        assertFalse(RootAccess.isGranted { ProbeProcess(exitCode = 1) })
        assertTrue(RootAccess.isGranted { ProbeProcess(exitCode = 0) })
        assertFalse(RootAccess.isGranted { ProbeProcess(exitCode = 1) })
    }

    private class ProbeProcess(
        private val exitCode: Int,
        private val completed: Boolean = true,
    ) : Process() {
        var destroyed = false
        var timeoutMillis = 0L
        private val stdin = ByteArrayOutputStream()
        private val stdout = ByteArrayInputStream(byteArrayOf())
        private val stderr = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = stdin
        override fun getInputStream() = stdout
        override fun getErrorStream() = stderr
        override fun waitFor() = exitCode
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            timeoutMillis = unit.toMillis(timeout)
            return completed
        }
        override fun exitValue(): Int {
            check(completed) { "Process has not exited" }
            return exitCode
        }
        override fun isAlive() = !completed && !destroyed
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroyed = true; return this }
    }
}
