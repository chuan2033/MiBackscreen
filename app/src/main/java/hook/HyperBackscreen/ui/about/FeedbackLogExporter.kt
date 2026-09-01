package hook.HyperBackscreen.ui.about

import android.annotation.SuppressLint
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
    private const val COMMAND_TIMEOUT_SECONDS = 15L
    private const val FEEDBACK_SCHEMA_VERSION = 2
    private const val MAX_LSPOSED_OUTPUT_BYTES = 256 * 1024
    private const val MAX_LOGCAT_OUTPUT_BYTES = 512 * 1024
    private const val MAX_HOST_LOG_OUTPUT_BYTES = 512 * 1024
    private const val MAX_STATE_OUTPUT_BYTES = 1024 * 1024
    private const val MAX_FILE_REPORT_OUTPUT_BYTES = 256 * 1024
    private const val MAX_DATABASE_REPORT_OUTPUT_BYTES = 256 * 1024

    private data class DatabaseSnapshot(
        val files: List<File>,
        val report: String,
    )

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
            val databaseDirectory = File(directory, "database-$timestamp")
            val lsposedLog = collectLsposedLog()
            val logcat = collectLogcat()
            val rearScreenState = collectRearScreenState()
            val rearScreenFiles = collectRearScreenFiles()
            val hostLog = collectSubScreenCenterLog()
            val databaseSnapshot = collectDatabaseSnapshot(databaseDirectory)

            try {
                ZipOutputStream(FileOutputStream(output)).use { zip ->
                    zip.addText("feedback-info.txt", buildFeedbackInfo())
                    zip.addText("device.txt", buildDeviceReport(appContext))
                    zip.addText("config.txt", buildConfigReport(appContext))
                    zip.addText("hook-status.txt", buildHookStatus(lsposedLog))
                    zip.addText("rear-screen-state.txt", rearScreenState)
                    zip.addText("rear-screen-files.txt", rearScreenFiles)
                    zip.addText("rear-screen-database.txt", databaseSnapshot.report)
                    databaseSnapshot.files.forEach { file ->
                        zip.addFile("database/${file.name}", file)
                    }
                    zip.addText("mibackscreen-local.log", DiagnosticLogStore.readForExport(appContext))
                    zip.addText("mibackscreen-lsposed.log", lsposedLog)
                    zip.addText("subscreencenter-host.log", hostLog)
                    zip.addText("android-logcat.log", logcat)
                    zip.addText("mibackscreen-logcat.log", logcat)
                }
            } finally {
                databaseDirectory.deleteRecursively()
            }
            DiagnosticLogStore.appendLocal(appContext, "feedback archive created: ${output.name}")
            output
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildFeedbackInfo(): String = buildString {
        appendLine("feedback_schema=$FEEDBACK_SCHEMA_VERSION")
        appendLine("capture_time=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        appendLine("capture_hint=Generate this archive immediately after reproducing the issue, before reapplying the wallpaper or restarting related apps.")
        appendLine("privacy_hint=This archive contains rear-screen configuration, resource paths, filtered system logs, and the Theme Manager rear-screen database.")
        appendLine("missing_data_hint=Each root-backed report records collection errors when root access is unavailable.")
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
        appendLine("feedback_schema=$FEEDBACK_SCHEMA_VERSION")
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
        appendLine("disable_rear_screen_cover=${PrefsBridge.readDisableRearScreenCoverForUi(context)}")
        appendLine("disable_double_tap_wake=${PrefsBridge.readDisableDoubleTapWakeForUi(context)}")
        appendLine("double_tap_wake_disabled_packages=${PrefsBridge.readDoubleTapWakeDisabledPackagesForUi(context)}")
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
        "files=\$(ls -tr /data/adb/lspd/log/modules_*.log 2>/dev/null | tail -n 5); " +
            "for f in \$files; do grep 'hook.HyperBackscreen,MiBackscreen' \"\$f\"; done " +
            "| tail -c $MAX_LSPOSED_OUTPUT_BYTES",
        MAX_LSPOSED_OUTPUT_BYTES,
    )

    private fun collectLogcat(): String = runRootCommand(
        // 全量 logcat 在日志较多的设备上可能超过采集超时；限制为各缓冲区最近 20000 行。
        "logcat -b all -d -v threadtime -t 20000 2>/dev/null " +
            "| grep -Eai 'MiBackscreen|subscreencenter|SubScreen|MainPanel|PersistenceManager|RearScreen|rear_screen|theme_rear_widget|theme_magic|MAML|\\.mrc|AndroidRuntime' " +
            "| tail -c $MAX_LOGCAT_OUTPUT_BYTES",
        MAX_LOGCAT_OUTPUT_BYTES,
    )

    private fun collectRearScreenState(): String = runRootCommand(
        "{ " +
            "user_id=\$(am get-current-user 2>/dev/null); " +
            "[ -n \"\$user_id\" ] || user_id=0; " +
            "base=\"/data/system/theme_magic/users/\$user_id\"; " +
            "echo '=== collection ==='; date '+generated_at=%Y-%m-%d %H:%M:%S %z'; echo \"current_user=\$user_id\"; " +
            "echo; echo '=== processes ==='; ps -A 2>/dev/null | grep -E 'subscreencenter|thememanager' || echo 'No related process'; " +
            "echo; echo '=== secure: theme_rear_widget ==='; settings --user \"\$user_id\" get secure theme_rear_widget 2>&1; " +
            "echo; echo '=== related secure settings ==='; settings --user \"\$user_id\" list secure 2>/dev/null | grep -Eai 'rear|sub.?screen|back.?screen' || echo 'No matching secure settings'; " +
            "echo; echo '=== related system settings ==='; settings --user \"\$user_id\" list system 2>/dev/null | grep -Eai 'rear|sub.?screen|back.?screen' || echo 'No matching system settings'; " +
            "for f in \"\$base/subscreencenter/config/user_pref.json\" \"\$base/subscreencenter/config/widget.json\" \"\$base/rearScreen/runtime.json\"; do " +
            "echo; echo \"=== file: \$f ===\"; " +
            "if [ -f \"\$f\" ]; then stat -c 'path=%n size=%s modified=%y mode=%a owner=%U:%G' \"\$f\" 2>&1; sha256sum \"\$f\" 2>&1; cat \"\$f\" 2>&1; echo; else echo 'MISSING'; fi; " +
            "done; " +
            "} | tail -c $MAX_STATE_OUTPUT_BYTES",
        MAX_STATE_OUTPUT_BYTES,
    )

    private fun collectRearScreenFiles(): String = runRootCommand(
        "{ " +
            "user_id=\$(am get-current-user 2>/dev/null); " +
            "[ -n \"\$user_id\" ] || user_id=0; " +
            "base=\"/data/system/theme_magic/users/\$user_id\"; " +
            "echo '=== filesystem ==='; df -h \"\$base\" 2>&1; " +
            "for root in \"\$base/rearScreen\" \"\$base/subscreencenter/config\"; do " +
            "echo; echo \"=== manifest: \$root ===\"; " +
            "if [ -d \"\$root\" ]; then find \"\$root\" -maxdepth 5 -type f 2>/dev/null | while IFS= read -r f; do stat -c '%n|size=%s|modified=%y|mode=%a|owner=%U:%G' \"\$f\" 2>&1; done; else echo 'MISSING'; fi; " +
            "done; " +
            "echo; echo '=== theme rights (newest 300 entries) ==='; ls -ltZ /data/system/theme/rights 2>&1 | head -n 301; " +
            "} | tail -c $MAX_FILE_REPORT_OUTPUT_BYTES",
        MAX_FILE_REPORT_OUTPUT_BYTES,
    )

    private fun collectSubScreenCenterLog(): String = runRootCommand(
        "{ " +
            "user_id=\$(am get-current-user 2>/dev/null); " +
            "[ -n \"\$user_id\" ] || user_id=0; " +
            "log_dir=\"/data/system/theme_magic/users/\$user_id/subscreencenter/logs\"; " +
            "echo \"log_directory=\$log_dir\"; ls -ltZ \"\$log_dir\" 2>&1; " +
            "echo; echo '=== current host log contents ==='; " +
            "current=\"\$log_dir/app.log\"; " +
            "if [ -f \"\$current\" ]; then tail -c $MAX_HOST_LOG_OUTPUT_BYTES \"\$current\" 2>&1; else echo 'No current app.log found'; fi; " +
            "} | tail -c $MAX_HOST_LOG_OUTPUT_BYTES",
        MAX_HOST_LOG_OUTPUT_BYTES,
    )

    @SuppressLint("SdCardPath")
    private fun collectDatabaseSnapshot(destination: File): DatabaseSnapshot {
        if (!destination.exists() && !destination.mkdirs()) {
            return DatabaseSnapshot(emptyList(), "Failed to create database staging directory.\n")
        }
        val databaseNames = listOf("rearScreen.db", "rearScreen.db-wal", "rearScreen.db-shm")
        val stagedNames = databaseNames.flatMap { name ->
            listOf("credential-$name", "device-$name")
        }
        try {
            stagedNames.forEach { name -> File(destination, name).createNewFile() }
        } catch (error: Throwable) {
            return DatabaseSnapshot(
                emptyList(),
                "Failed to prepare database snapshot files: ${error.javaClass.simpleName}: ${error.message.orEmpty()}\n",
            )
        }
        val destinationPath = shellQuote(destination.absolutePath)
        val copyReport = runRootCommand(
            "user_id=\$(am get-current-user 2>/dev/null); " +
                "[ -n \"\$user_id\" ] || user_id=0; " +
                "dest=$destinationPath; " +
                "for name in rearScreen.db rearScreen.db-wal rearScreen.db-shm; do " +
                "src=\"/data/user/\$user_id/com.android.thememanager/databases/\$name\"; " +
                "if [ -f \"\$src\" ]; then cat \"\$src\" > \"\$dest/credential-\$name\"; fi; " +
                "src=\"/data/user_de/\$user_id/com.android.thememanager/databases/\$name\"; " +
                "if [ -f \"\$src\" ]; then cat \"\$src\" > \"\$dest/device-\$name\"; fi; " +
                "done; " +
                "echo '=== copied database files ==='; ls -lZ \"\$dest\" 2>&1; " +
                "echo; echo '=== ordered database rows ==='; " +
                "db=\"/data/user/\$user_id/com.android.thememanager/databases/rearScreen.db\"; " +
                "if [ ! -f \"\$db\" ]; then db=\"/data/user_de/\$user_id/com.android.thememanager/databases/rearScreen.db\"; fi; " +
                "if [ ! -f \"\$db\" ]; then echo 'MISSING: rearScreen.db'; " +
                "elif command -v sqlite3 >/dev/null 2>&1; then " +
                "sqlite3 -header -separator '|' \"\$db\" \"PRAGMA user_version; SELECT resId,applyId,resName,position,applyTime,updateTime,isDownload,isThirdParties,supportAon,resLocalPath,resSnapshotPath,rightPath,metaPath,metaSnapshotPath,mamlEditConfigPath,snapshotPreviewPath FROM rearScreenMine ORDER BY position DESC;\" 2>&1; " +
                "else echo 'sqlite3 unavailable; inspect the included database snapshot.'; fi " +
                "| tail -c $MAX_DATABASE_REPORT_OUTPUT_BYTES",
            MAX_DATABASE_REPORT_OUTPUT_BYTES,
        )
        val files = destination.listFiles()
            ?.filter { it.isFile && it.name.contains("rearScreen.db") && it.length() > 0L }
            ?.sortedBy { it.name }
            .orEmpty()
        val report = buildString {
            appendLine("staging_directory=${destination.absolutePath}")
            appendLine("copied_file_count=${files.size}")
            append(copyReport)
        }
        return DatabaseSnapshot(files, report)
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun runRootCommand(command: String, maxOutputBytes: Int): String {
        val readerExecutor = Executors.newSingleThreadExecutor()
        return try {
            // Android 的应用数据隔离会让普通 su 继承应用挂载命名空间，看不到主题商店数据库。
            // KernelSU/Magisk 的 -M 会切到全局挂载命名空间，同时仍保留 root 权限。
            val process = ProcessBuilder("su", "-M", "-c", command)
                .redirectErrorStream(true)
                .start()
            val outputFuture = readerExecutor.submit<ByteArray> {
                process.inputStream.use { it.readNBytes(maxOutputBytes) }
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

    private fun ZipOutputStream.addFile(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        file.inputStream().use { input -> input.copyTo(this) }
        closeEntry()
    }
}
