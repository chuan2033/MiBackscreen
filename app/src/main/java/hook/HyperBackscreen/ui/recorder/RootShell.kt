package hook.HyperBackscreen.ui.recorder

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 以 root 执行 shell 脚本：脚本经 stdin 交给 `su`，不拼进 `su -c '...'`，引号与换行不会被二次解析。
 */
internal object RootShell {
    class Result(val exitCode: Int, val stdout: ByteArray, val stderr: String) {
        val text: String get() = stdout.toString(Charsets.UTF_8)
    }

    /** 启动一个长期运行的 root shell 并写入脚本；stdin 写完即关闭。调用方负责结束进程。 */
    fun start(script: String): Process {
        val process = ProcessBuilder("su").start()
        try {
            process.outputStream.use { it.write(script.toByteArray(Charsets.UTF_8)) }
        } catch (error: Exception) {
            process.destroyForcibly()
            throw error
        }
        return process
    }

    /**
     * 执行脚本并等它结束。su 不可用、启动失败或超时返回 null。
     * stdout 与 stderr 各开一个线程读，输出再大也不会把管道写满卡住。
     */
    fun run(script: String, timeoutSeconds: Long, maxStdoutBytes: Int = 4 shl 20): Result? {
        val readers = Executors.newFixedThreadPool(2)
        var process: Process? = null
        return try {
            val started = start(script)
            process = started
            val stdout = readers.submit<ByteArray> { readBounded(started.inputStream, maxStdoutBytes) }
            val stderr = readers.submit<ByteArray> { readBounded(started.errorStream, 64 * 1024) }
            if (!started.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                started.destroyForcibly()
                return null
            }
            Result(
                exitCode = started.exitValue(),
                stdout = stdout.get(2, TimeUnit.SECONDS),
                stderr = stderr.get(2, TimeUnit.SECONDS).toString(Charsets.UTF_8),
            )
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        } finally {
            readers.shutdownNow()
            process?.let { if (it.isAlive) it.destroyForcibly() }
        }
    }

    /** 读到流结束；超过上限的部分丢弃但继续读，保证对端不会因管道写满而阻塞。 */
    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray = input.use { stream ->
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = stream.read(chunk)
            if (count < 0) break
            val room = maxBytes - output.size()
            if (room > 0) output.write(chunk, 0, minOf(count, room))
        }
        output.toByteArray()
    }
}
