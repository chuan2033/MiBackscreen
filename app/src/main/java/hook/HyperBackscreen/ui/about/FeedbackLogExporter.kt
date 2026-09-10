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
import hook.HyperBackscreen.ui.util.RearDisplayCompatibility
import hook.HyperBackscreen.ui.util.currentDeviceName
import hook.HyperBackscreen.ui.util.currentHyperOSVersion
import hook.HyperBackscreen.ui.util.currentSystemVersion
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object FeedbackLogExporter {
    private const val COMMAND_TIMEOUT_SECONDS = 20L
    private const val FEEDBACK_SCHEMA_VERSION = 4
    private const val MAX_LSPOSED_OUTPUT_BYTES = 256 * 1024
    private const val MAX_LOGCAT_OUTPUT_BYTES = 1024 * 1024
    private const val MAX_HOST_LOG_OUTPUT_BYTES = 1024 * 1024
    private const val MAX_STATE_OUTPUT_BYTES = 1024 * 1024
    private const val MAX_FILE_REPORT_OUTPUT_BYTES = 512 * 1024
    private const val MAX_DATABASE_REPORT_OUTPUT_BYTES = 512 * 1024
    private const val MAX_SYSTEM_DIAGNOSTICS_OUTPUT_BYTES = 512 * 1024
    private const val MAX_RESOURCE_REPORT_OUTPUT_BYTES = 1024 * 1024
    private const val MAX_THEME_MANAGER_FILES_OUTPUT_BYTES = 512 * 1024

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
            val rearScreenResourceReport = collectRearScreenResourceReport()
            val themeManagerFiles = collectThemeManagerFiles()
            val systemDiagnostics = collectSystemDiagnostics()
            val hostLog = collectSubScreenCenterLog()
            val databaseSnapshot = collectDatabaseSnapshot(databaseDirectory)

            try {
                ZipOutputStream(FileOutputStream(output)).use { zip ->
                    zip.addText("feedback-info.txt", buildFeedbackInfo())
                    zip.addText("device.txt", buildDeviceReport(appContext))
                    zip.addText("config.txt", buildConfigReport(appContext))
                    zip.addText("hook-status.txt", buildHookStatus(lsposedLog))
                    zip.addText("system-diagnostics.txt", systemDiagnostics)
                    zip.addText("rear-screen-state.txt", rearScreenState)
                    zip.addText("rear-screen-files.txt", rearScreenFiles)
                    zip.addText("rear-screen-resource-report.txt", rearScreenResourceReport)
                    zip.addText("theme-manager-files.txt", themeManagerFiles)
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
        appendLine("privacy_hint=This archive contains rear-screen configuration, resource paths, editConfig text, file metadata, filtered system logs, and the Theme Manager rear-screen database. User wallpaper image/video bytes are not copied.")
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
        appendLine("theme_settings_shortcut=${PrefsBridge.readThemeSettingsShortcutForUi(context)}")
        appendLine("floating_nav_bar=${PrefsBridge.readFloatingNavBar(context)}")
        appendLine("liquid_glass=${PrefsBridge.readLiquidGlass(context)}")
        appendLine("launcher_icon_hidden=${LauncherIconController.isHidden(context)}")
        appendLine("${RearDisplayCompatibility.BUILTIN_PRESENTATION_PROPERTY}=${RearDisplayCompatibility.builtinPresentationValue() ?: "unknown"}")
        appendLine("rear_display_hidden_warning=${RearDisplayCompatibility.isBuiltinPresentationDisabled()}")
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
            "echo; echo '=== related global settings ==='; settings list global 2>/dev/null | grep -Eai 'rear|sub.?screen|back.?screen|theme|maml' || echo 'No matching global settings'; " +
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
            "if [ -d \"\$root\" ]; then find \"\$root\" -maxdepth 5 -type d 2>/dev/null | while IFS= read -r d; do stat -c '%n|type=dir|modified=%y|mode=%a|owner=%U:%G' \"\$d\" 2>&1; done; else echo 'MISSING'; fi; " +
            "echo; echo \"=== files: \$root ===\"; " +
            "if [ -d \"\$root\" ]; then find \"\$root\" -maxdepth 5 -type f 2>/dev/null | while IFS= read -r f; do stat -c '%n|size=%s|modified=%y|mode=%a|owner=%U:%G' \"\$f\" 2>&1; done; else echo 'MISSING'; fi; " +
            "done; " +
            "for root in /data/system/theme/rearScreen /data/system/theme/rearScreenWhite /data/system/theme/rights; do " +
            "echo; echo \"=== system theme manifest: \$root ===\"; " +
            "if [ -d \"\$root\" ]; then find \"\$root\" -maxdepth 1 -type f 2>/dev/null | sort | tail -n 500 | while IFS= read -r f; do stat -c '%n|size=%s|modified=%y|mode=%a|owner=%U:%G' \"\$f\" 2>&1; done; else echo 'MISSING'; fi; " +
            "done; " +
            "echo; echo '=== theme rights (newest 300 entries) ==='; ls -ltZ /data/system/theme/rights 2>&1 | head -n 301; " +
            "} | tail -c $MAX_FILE_REPORT_OUTPUT_BYTES",
        MAX_FILE_REPORT_OUTPUT_BYTES,
    )

    private fun collectRearScreenResourceReport(): String = runRootCommand(
        "{ " +
            "user_id=\$(am get-current-user 2>/dev/null); " +
            "[ -n \"\$user_id\" ] || user_id=0; " +
            "base=\"/data/system/theme_magic/users/\$user_id\"; " +
            "tmp=\"/data/local/tmp/mibackscreen-feedback-paths-\$\$\"; " +
            "trap 'rm -f \"\$tmp\" \"\$tmp.paths\"' EXIT; " +
            "echo '=== resource probe ==='; date '+generated_at=%Y-%m-%d %H:%M:%S %z'; echo \"current_user=\$user_id\"; " +
            "print_path() { p=\"\$1\"; if [ -e \"\$p\" ]; then ls -ldZ \"\$p\" 2>&1; stat -c 'path=%n type=%F size=%s modified=%y mode=%a owner=%U:%G' \"\$p\" 2>&1; else echo \"MISSING: \$p\"; fi; }; " +
            "echo; echo '=== parent directories ==='; " +
            "for p in /data /data/system /data/system/theme /data/system/theme/rearScreen /data/system/theme/rearScreenWhite /data/system/theme/rights /data/system/theme_magic /data/system/theme_magic/users \"/data/system/theme_magic/users/\$user_id\" \"\$base\" \"\$base/rearScreen\" \"\$base/subscreencenter\" \"\$base/subscreencenter/config\"; do print_path \"\$p\"; done; " +
            "settings --user \"\$user_id\" get secure theme_rear_widget 2>/dev/null > \"\$tmp\"; " +
            "for f in \"\$base/subscreencenter/config/widget.json\" \"\$base/rearScreen/runtime.json\"; do [ -f \"\$f\" ] && cat \"\$f\" >> \"\$tmp\"; done; " +
            "if [ -d \"\$base/rearScreen\" ]; then find \"\$base/rearScreen\" -maxdepth 2 -type f -name editConfig 2>/dev/null | sort | tail -n 80 | while IFS= read -r f; do echo \"\$f\" >> \"\$tmp\"; cat \"\$f\" >> \"\$tmp\"; done; fi; " +
            "sed 's#\\\\/#/#g' \"\$tmp\" 2>/dev/null | tr '\"' '\\n' | tr ',' '\\n' | tr ' ' '\\n' | grep -E '^/data/system/(theme_magic|theme)/' | sed 's/[}\\]]*$//' | sort -u > \"\$tmp.paths\"; " +
            "echo; echo '=== referenced path count ==='; wc -l \"\$tmp.paths\" 2>&1; " +
            "echo; echo '=== referenced path metadata ==='; while IFS= read -r p; do print_path \"\$p\"; done < \"\$tmp.paths\"; " +
            "echo; echo '=== custom background candidates ==='; " +
            "if [ -d \"\$base/rearScreen\" ]; then find \"\$base/rearScreen\" -maxdepth 3 -type f \\( -name 'rearScreenCustomBg' -o -name '*Custom*' -o -name '*custom*' \\) 2>/dev/null | sort | tail -n 200 | while IFS= read -r f; do print_path \"\$f\"; done; else echo 'MISSING rearScreen directory'; fi; " +
            "echo; echo '=== editConfig contents (latest 80, text only) ==='; " +
            "if [ -d \"\$base/rearScreen\" ]; then find \"\$base/rearScreen\" -maxdepth 2 -type f -name editConfig 2>/dev/null | sort | tail -n 80 | while IFS= read -r f; do echo; echo \"--- \$f ---\"; print_path \"\$f\"; cat \"\$f\" 2>&1; echo; done; else echo 'MISSING rearScreen directory'; fi; " +
            "} | tail -c $MAX_RESOURCE_REPORT_OUTPUT_BYTES",
        MAX_RESOURCE_REPORT_OUTPUT_BYTES,
    )

    private fun collectThemeManagerFiles(): String = runRootCommand(
        "{ " +
            "user_id=\$(am get-current-user 2>/dev/null); " +
            "[ -n \"\$user_id\" ] || user_id=0; " +
            "echo '=== Theme Manager data roots ==='; " +
            "for root in \"/data/user/\$user_id/com.android.thememanager\" \"/data/user_de/\$user_id/com.android.thememanager\" \"/storage/emulated/\$user_id/Android/data/com.android.thememanager/files/MIUI/theme/.data\"; do " +
            "echo; echo \"=== root: \$root ===\"; " +
            "if [ -e \"\$root\" ]; then ls -ldZ \"\$root\" 2>&1; else echo 'MISSING'; fi; " +
            "done; " +
            "for root in \"/data/user/\$user_id/com.android.thememanager/databases\" \"/data/user_de/\$user_id/com.android.thememanager/databases\"; do " +
            "echo; echo \"=== database files: \$root ===\"; " +
            "if [ -d \"\$root\" ]; then ls -lZ \"\$root\" 2>&1; else echo 'MISSING'; fi; " +
            "done; " +
            "theme_root=\"/storage/emulated/\$user_id/Android/data/com.android.thememanager/files/MIUI/theme/.data\"; " +
            "for root in \"\$theme_root/content/rearscreen\" \"\$theme_root/meta/rearscreen\" \"\$theme_root/preview/theme\"; do " +
            "echo; echo \"=== theme cache manifest: \$root ===\"; " +
            "if [ -d \"\$root\" ]; then find \"\$root\" -maxdepth 3 -type f 2>/dev/null | sort | tail -n 500 | while IFS= read -r f; do stat -c '%n|size=%s|modified=%y|mode=%a|owner=%U:%G' \"\$f\" 2>&1; done; else echo 'MISSING'; fi; " +
            "done; " +
            "} | tail -c $MAX_THEME_MANAGER_FILES_OUTPUT_BYTES",
        MAX_THEME_MANAGER_FILES_OUTPUT_BYTES,
    )

    private fun collectSystemDiagnostics(): String = runRootCommand(
        "{ " +
            "echo '=== root/session ==='; id 2>&1; echo \"shell_pid=\$\$\"; ls -l /proc/\$\$/ns/mnt 2>&1; command -v su 2>&1; su -v 2>&1; su -V 2>&1; " +
            "echo; echo '=== selinux ==='; getenforce 2>&1; " +
            "echo; echo '=== compatibility properties ==='; getprop vendor.display.builtin_presentation 2>&1 | sed 's/^/vendor.display.builtin_presentation=/'; " +
            "echo; echo '=== build properties ==='; getprop 2>/dev/null | grep -Eai 'ro\\.product|ro\\.build\\.|ro\\.system\\.|ro\\.vendor\\.|ro\\.miui|ro\\.hyperos|persist\\.sys\\.|persist\\.miui|persist\\.hyperos' | sort; " +
            "echo; echo '=== storage ==='; df -h /data /data/system /data/system/theme /data/system/theme_magic 2>&1; " +
            "echo; echo '=== related packages ==='; " +
            "for pkg in ${Constants.TARGET_PACKAGE} ${Constants.THEME_STORE_PACKAGE} ${Constants.MODULE_PACKAGE}; do " +
            "echo; echo \"--- package: \$pkg ---\"; " +
            "dumpsys package \"\$pkg\" 2>/dev/null | grep -Eai 'Package \\[|versionName|versionCode|codePath|resourcePath|dataDir|userId=|pkgFlags|privateFlags|enabled=|stopped=|firstInstallTime|lastUpdateTime|installerPackageName|targetSdk|uses-permission|granted=true' | head -n 220 || echo 'No package dump'; " +
            "done; " +
            "echo; echo '=== related processes ==='; ps -A -o USER,PID,PPID,NAME,ARGS 2>/dev/null | grep -E 'subscreencenter|thememanager|HyperBackscreen' || ps -A 2>/dev/null | grep -E 'subscreencenter|thememanager|HyperBackscreen' || echo 'No related process'; " +
            "} | tail -c $MAX_SYSTEM_DIAGNOSTICS_OUTPUT_BYTES",
        MAX_SYSTEM_DIAGNOSTICS_OUTPUT_BYTES,
    )

    private fun collectSubScreenCenterLog(): String = runRootCommand(
        "{ " +
            "user_id=\$(am get-current-user 2>/dev/null); " +
            "[ -n \"\$user_id\" ] || user_id=0; " +
            "log_dir=\"/data/system/theme_magic/users/\$user_id/subscreencenter/logs\"; " +
            "echo \"log_directory=\$log_dir\"; ls -ltZ \"\$log_dir\" 2>&1; " +
            "echo; echo '=== recent host log contents ==='; " +
            "if [ -d \"\$log_dir\" ]; then ls -tr \"\$log_dir\"/*.log* 2>/dev/null | tail -n 5 | while IFS= read -r f; do echo; echo \"--- \$f ---\"; tail -c 262144 \"\$f\" 2>&1; done; else echo 'No host log directory found'; fi; " +
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
                process.inputStream.use { input ->
                    val output = TailBuffer(maxOutputBytes)
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        output.write(buffer, count)
                    }
                    output.toByteArray()
                }
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

    private class TailBuffer(private val maxBytes: Int) {
        private val buffer = ByteArrayOutputStream(maxBytes.coerceAtLeast(0))

        fun write(source: ByteArray, count: Int) {
            if (maxBytes <= 0 || count <= 0) return
            if (count >= maxBytes) {
                buffer.reset()
                buffer.write(source, count - maxBytes, maxBytes)
                return
            }
            val overflow = buffer.size() + count - maxBytes
            if (overflow > 0) {
                val current = buffer.toByteArray()
                buffer.reset()
                buffer.write(current, overflow, current.size - overflow)
            }
            buffer.write(source, 0, count)
        }

        fun toByteArray(): ByteArray = buffer.toByteArray()
    }
}
