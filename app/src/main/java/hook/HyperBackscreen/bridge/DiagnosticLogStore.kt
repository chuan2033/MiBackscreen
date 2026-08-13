package hook.HyperBackscreen.bridge

import android.content.Context
import android.net.Uri
import android.os.Bundle
import hook.HyperBackscreen.common.Constants
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLogStore {
    private const val DIRECTORY = "diagnostics"
    private const val CURRENT_FILE = "mibackscreen.log"
    private const val PREVIOUS_FILE = "mibackscreen.log.1"
    private const val MAX_FILE_BYTES = 256 * 1024L
    private const val MAX_MESSAGE_CHARS = 600

    @JvmStatic
    fun recordRemote(context: Context, message: String) {
        try {
            val extras = Bundle().apply {
                putString(Constants.EXTRA_DIAGNOSTIC_MESSAGE, sanitize(message))
            }
            context.contentResolver.call(
                Uri.parse("content://${Constants.PANEL_PREFERENCE_AUTHORITY}"),
                Constants.PANEL_DIAGNOSTIC_METHOD_APPEND,
                null,
                extras,
            )
        } catch (_: Throwable) {
            // 诊断日志不能影响宿主进程。
        }
    }

    @JvmStatic
    fun appendLocal(context: Context, message: String): Boolean {
        return synchronized(this) {
            try {
                val directory = File(context.filesDir, DIRECTORY)
                if (!directory.exists() && !directory.mkdirs()) return@synchronized false
                val current = File(directory, CURRENT_FILE)
                if (current.length() >= MAX_FILE_BYTES) {
                    val previous = File(directory, PREVIOUS_FILE)
                    if (previous.exists()) previous.delete()
                    current.renameTo(previous)
                }
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                current.appendText("$timestamp ${sanitize(message)}\n", Charsets.UTF_8)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun readForExport(context: Context): String {
        return synchronized(this) {
            val directory = File(context.filesDir, DIRECTORY)
            val previous = File(directory, PREVIOUS_FILE)
            val current = File(directory, CURRENT_FILE)
            buildString {
                if (previous.isFile) appendCapped(previous)
                if (current.isFile) appendCapped(current)
            }.ifBlank { "No local diagnostic events recorded.\n" }
        }
    }

    private fun StringBuilder.appendCapped(file: File) {
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (_: Throwable) {
            return
        }
        append(text.takeLast(MAX_FILE_BYTES.toInt()))
        if (isNotEmpty() && last() != '\n') append('\n')
    }

    private fun sanitize(message: String): String =
        message.replace('\r', ' ').replace('\n', ' ').take(MAX_MESSAGE_CHARS)
}
