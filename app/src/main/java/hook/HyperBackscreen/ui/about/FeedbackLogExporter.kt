package hook.HyperBackscreen.ui.about

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import hook.HyperBackscreen.BuildConfig
import hook.HyperBackscreen.R
import hook.HyperBackscreen.bridge.DiagnosticLogStore
import hook.HyperBackscreen.bridge.PrefsBridge
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.ui.LauncherIconController
import hook.HyperBackscreen.ui.util.currentDeviceName
import hook.HyperBackscreen.ui.util.currentHyperOSVersion
import hook.HyperBackscreen.ui.util.currentSystemVersion
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object FeedbackLogExporter {
    private const val COMMAND_TIMEOUT_SECONDS = 8L
    private const val MAX_COMMAND_OUTPUT_BYTES = 64 * 1024

    fun create(context: Context): File? {
        return try {
            val appContext = context.applicationContext
            val directory = File(appContext.cacheDir, "feedback")
            if (!directory.exists() && !directory.mkdirs()) return null
            directory.listFiles()
                ?.filter { it.isFile && it.name.startsWith("MiBackscreen-feedback-") }
                ?.forEach { it.delete() }

            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val output = File(directory, "MiBackscreen-feedback-$timestamp.zip")
            val lsposedLog = collectLsposedLog()
            val logcat = collectLogcat()

            ZipOutputStream(FileOutputStream(output)).use { zip ->
                zip.addText("device.txt", buildDeviceReport(appContext))
                zip.addText("config.txt", buildConfigReport(appContext))
                zip.addText("hook-status.txt", buildHookStatus(lsposedLog))
                zip.addText("mibackscreen-local.log", DiagnosticLogStore.readForExport(appContext))
                zip.addText("mibackscreen-lsposed.log", lsposedLog)
                zip.addText("mibackscreen-logcat.log", logcat)
            }
            DiagnosticLogStore.appendLocal(appContext, "feedback archive created: ${output.name}")
            output
        } catch (_: Throwable) {
            null
        }
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            file,
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(sendIntent, context.getString(R.string.feedback_log_share_title)),
        )
    }

    private fun buildDeviceReport(context: Context): String = buildString {
        appendLine("generated_at=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        appendLine("module_version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("build_type=${BuildConfig.BUILD_TYPE}")
        appendLine("device=${currentDeviceName()}")
        appendLine("manufacturer=${Build.MANUFACTURER}")
        appendLine("model=${Build.MODEL}")
        appendLine("system=${currentSystemVersion()}")
        appendLine("hyperos=${currentHyperOSVersion()}")
        appendLine("fingerprint=${Build.FINGERPRINT}")
        appendPackageVersion(context, Constants.TARGET_PACKAGE)
        appendPackageVersion(context, Constants.THEME_STORE_PACKAGE)
    }

    private fun StringBuilder.appendPackageVersion(context: Context, packageName: String) {
        val value = try {
            val info = context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
            "${info.versionName ?: "unknown"} (${info.longVersionCode})"
        } catch (_: Throwable) {
            "not installed"
        }
        appendLine("package.$packageName=$value")
    }

    private fun buildConfigReport(context: Context): String = buildString {
        appendLine("disable_long_press_edit=${PrefsBridge.readDisableLongPressForUi(context)}")
        appendLine("remove_wallpaper_limit=${PrefsBridge.readRemoveWallpaperLimitForUi(context)}")
        appendLine("fix_rear_screen_apply=${PrefsBridge.readFixRearScreenApplyForUi(context)}")
        appendLine("enable_swipe_panel=${PrefsBridge.readEnableSwipePanelForUi(context)}")
        appendLine("floating_nav_bar=${PrefsBridge.readFloatingNavBar(context)}")
        appendLine("liquid_glass=${PrefsBridge.readLiquidGlass(context)}")
        appendLine("launcher_icon_hidden=${LauncherIconController.isHidden(context)}")
    }

    private fun buildHookStatus(lsposedLog: String): String {
        val statusLines = lsposedLog.lineSequence().filter { line ->
            line.contains("Hook installed", ignoreCase = true) ||
                line.contains("Hooks installed", ignoreCase = true) ||
                line.contains("Hook target missing", ignoreCase = true) ||
                line.contains("Hook install failed", ignoreCase = true) ||
                line.contains("Failed to install hooks", ignoreCase = true)
        }.toList().takeLast(300)
        return if (statusLines.isEmpty()) {
            "No hook status lines found. See mibackscreen-lsposed.log for collection details.\n"
        } else {
            statusLines.joinToString(separator = "\n", postfix = "\n")
        }
    }

    private fun collectLsposedLog(): String = runRootCommand(
        "files=\$(ls -tr /data/adb/lspd/log/modules_*.log 2>/dev/null | tail -n 3); " +
            "for f in \$files; do grep 'hook.HyperBackscreen,MiBackscreen' \"\$f\"; done " +
            "| tail -c $MAX_COMMAND_OUTPUT_BYTES",
    )

    private fun collectLogcat(): String = runRootCommand(
        "logcat -d -v threadtime -t 1500 2>/dev/null | grep MiBackscreen " +
            "| tail -c $MAX_COMMAND_OUTPUT_BYTES",
    )

    private fun runRootCommand(command: String): String {
        val readerExecutor = Executors.newSingleThreadExecutor()
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val outputFuture = readerExecutor.submit<ByteArray> {
                process.inputStream.use { it.readNBytes(MAX_COMMAND_OUTPUT_BYTES) }
            }
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                outputFuture.cancel(true)
                return "Collection timed out or root access was not granted.\n"
            }
            val bytes = outputFuture.get(1L, TimeUnit.SECONDS)
            val text = bytes.toString(Charsets.UTF_8)
            if (process.exitValue() == 0 && text.isNotBlank()) {
                text
            } else {
                "No matching records, or root access was unavailable.\n$text"
            }
        } catch (error: Throwable) {
            "Collection failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}\n"
        } finally {
            readerExecutor.shutdownNow()
        }
    }

    private fun ZipOutputStream.addText(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
