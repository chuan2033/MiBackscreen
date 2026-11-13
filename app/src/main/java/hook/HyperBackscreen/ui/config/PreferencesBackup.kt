package hook.HyperBackscreen.ui.config

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import hook.HyperBackscreen.BuildConfig
import hook.HyperBackscreen.bridge.PrefsBridge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 配置备份 / 恢复：把模块偏好导出成一份 JSON 放在手机存储根目录，换机或重装后读回来。
 *
 * 备份走 root 写根目录：JSON 不是媒体文件，分区存储下应用写不进 /storage/emulated/0；
 * 走 MediaStore 只能落到 Download。模块本身有 root，借 su 直接 cp 最省事，固定文件名也好找。
 *
 * 恢复让用户用系统文件选择器自己挑文件（[inspect] / [restore] 收 Uri）：备份常常是从别的手机、
 * 微信或网盘拿来的，放在 Download、名字也可能变成「…(1).json」，固定去根目录找就找不到。
 * 选择器给的 Uri 应用直接可读，恢复不需要 root。
 *
 * 只备份偏好，不动 data-root 资源与 appInfo 索引 —— 那些由 KSU 模块安装时重建。
 */
internal object PreferencesBackup {
    const val FILE_NAME = "MiBackscreen-config.json"

    private const val FORMAT = "mibackscreen-preferences"
    private const val VERSION = 1
    private const val MAX_READ_BYTES = 256 * 1024
    private const val COMMAND_TIMEOUT_SECONDS = 20L
    private const val WRITE_MARKER = "__MIBS_WRITTEN__"
    private val compactJson = Json { prettyPrint = false }
    private val prettyJson = Json { prettyPrint = true }

    data class BackupResult(
        val keyCount: Int,
        val path: String,
        val previousExportedAt: String?,
    )

    data class RestoreResult(
        val keyCount: Int,
        val exportedAt: String?,
        val appVersion: String?,
    )

    /** 备份到根目录；覆盖已有文件。返回 null 表示写入失败（通常是 root 未授权）。 */
    fun backup(context: Context): BackupResult? {
        val values = PrefsBridge.exportPreferencesForBackup(context)
        val previous = runCatching { readPayload()?.exportedAt }.getOrNull()
        val payload = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", timestamp())
            put("appVersion", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            put("device", Build.DEVICE)
            putJsonObject("values") {
                values.forEach { (key, value) -> put(key, value) }
            }
        }
        val text = prettyJson.encodeToString(JsonObject.serializer(), payload)
        if (!writePayload(context, text)) return null
        return BackupResult(
            keyCount = values.size,
            path = targetFile().absolutePath,
            previousExportedAt = previous,
        )
    }

    /** 备份文件的概要，用于恢复前的确认弹窗。 */
    data class BackupInfo(
        val keyCount: Int,
        val exportedAt: String?,
        val appVersion: String?,
    )

    /** 只读取所选备份文件的概要、不写任何偏好；返回 null 表示读不到或不是本模块的备份（与 [restore] 同一判据）。 */
    fun inspect(context: Context, uri: Uri): BackupInfo? {
        val payload = readPayload(context, uri) ?: return null
        if (payload.format != FORMAT || payload.values.isEmpty()) return null
        return BackupInfo(
            keyCount = payload.values.size,
            exportedAt = payload.exportedAt,
            appVersion = payload.appVersion,
        )
    }

    /** 从所选备份文件读回并覆盖当前偏好；返回 null 表示读不到或无法识别。 */
    fun restore(context: Context, uri: Uri): RestoreResult? {
        val payload = readPayload(context, uri) ?: return null
        if (payload.format != FORMAT) return null
        val applied = PrefsBridge.applyPreferencesFromBackup(context, payload.values)
        if (applied == 0) return null
        return RestoreResult(
            keyCount = applied,
            exportedAt = payload.exportedAt,
            appVersion = payload.appVersion,
        )
    }

    private data class Payload(
        val format: String?,
        val exportedAt: String?,
        val appVersion: String?,
        val values: Map<String, String>,
    )

    /** 根目录那份备份（只用于备份时提示「覆盖了哪次的备份」）。 */
    private fun readPayload(): Payload? {
        val text = runRoot("cat ${shellQuote(targetFile().absolutePath)}", MAX_READ_BYTES)
            ?: return null
        return parsePayload(text)
    }

    /** 用户在文件选择器里选的备份；超过上限的文件直接当作无法识别，不整个读进内存。 */
    private fun readPayload(context: Context, uri: Uri): Payload? {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytes(MAX_READ_BYTES + 1)
                if (bytes.size > MAX_READ_BYTES) null else String(bytes, Charsets.UTF_8)
            }
        }.getOrNull() ?: return null
        return parsePayload(text)
    }

    private fun parsePayload(text: String): Payload? {
        return runCatching {
            val root = compactJson.parseToJsonElement(text).jsonObject
            val values = root["values"]?.jsonObject?.mapValues { (_, value) ->
                value.jsonPrimitive.content
            } ?: return null
            Payload(
                format = root["format"]?.jsonPrimitive?.content,
                exportedAt = root["exportedAt"]?.jsonPrimitive?.content,
                appVersion = root["appVersion"]?.jsonPrimitive?.content,
                values = values,
            )
        }.getOrNull()
    }

    private fun writePayload(context: Context, text: String): Boolean {
        // 先落到应用缓存（普通文件 API 可写），再借 root 复制到公共根目录。
        val staging = File(File(context.cacheDir, "config-backup").apply { mkdirs() }, FILE_NAME)
        if (!runCatching { staging.writeText(text) }.isSuccess) return false
        val target = targetFile().absolutePath
        // 原子替换：先复制到同目录的临时文件，成功后再 mv 覆盖。直接 cp -f 会先截断唯一那份备份，
        // 中途超时被杀或存储写满时旧备份就坏了；同一分区内的 mv 是 rename，要么全新要么全旧。
        val temp = "$target.tmp"
        val command = "if cp -f ${shellQuote(staging.absolutePath)} ${shellQuote(temp)} && " +
            "[ -s ${shellQuote(temp)} ] && mv -f ${shellQuote(temp)} ${shellQuote(target)}; then " +
            "chmod 0644 ${shellQuote(target)} 2>/dev/null; echo $WRITE_MARKER; " +
            "else rm -f ${shellQuote(temp)}; fi"
        val output = runRoot(command, MAX_READ_BYTES)
        runCatching { staging.delete() }
        return output?.contains(WRITE_MARKER) == true
    }

    private fun targetFile(): File {
        val directory = Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
        return File(directory, FILE_NAME)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    /**
     * su -M：切到全局挂载命名空间（应用数据隔离下的普通 su 看不到公共目录）。
     * 读线程单独跑，避免大输出把管道写满导致 waitFor 死等。
     */
    private fun runRoot(command: String, maxBytes: Int): String? {
        val reader = Executors.newSingleThreadExecutor()
        var process: Process? = null
        return try {
            val started = ProcessBuilder("su", "-M", "-c", command)
                .redirectErrorStream(true)
                .start()
            process = started
            val future = reader.submit<ByteArray> {
                started.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        val room = maxBytes - output.size()
                        if (room > 0) output.write(chunk, 0, minOf(count, room))
                    }
                    output.toByteArray()
                }
            }
            if (!started.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                started.destroyForcibly()
                future.cancel(true)
                return null
            }
            val bytes = future.get(1, TimeUnit.SECONDS)
            if (started.exitValue() != 0) return null
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        } finally {
            reader.shutdownNow()
            process?.let { if (it.isAlive) it.destroyForcibly() }
        }
    }
}
