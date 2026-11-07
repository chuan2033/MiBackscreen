package hook.HyperBackscreen.ui.recorder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.Constants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** 录屏状态；[Recording.startedAtElapsed] 用 elapsedRealtime，界面据此算计时。 */
internal sealed interface RearRecorderState {
    data object Idle : RearRecorderState
    data object Starting : RearRecorderState
    data class Recording(val startedAtElapsed: Long) : RearRecorderState
    data object Saving : RearRecorderState
}

/** 一次录制的结果，界面收到后弹提示；没有界面在听时改用 Toast。 */
internal sealed interface RearRecorderEvent {
    data class Saved(val displayName: String) : RearRecorderEvent
    data class Failed(@get:StringRes val messageRes: Int) : RearRecorderEvent
}

/**
 * 背屏录屏（root）。
 *
 * 流程：解析背屏 display id → 一个 root shell 里先唤醒背屏，再 `exec screenrecord` 录到
 * /data/local/tmp 下的临时文件，同一个 shell 后台每 [WAKE_INTERVAL_SECONDS] 秒唤醒一次背屏保持亮屏
 * （背屏默认 10 秒熄屏）→ 停止时向 screenrecord 发 SIGINT，让它正常写完 MP4 尾部 →
 * 用 `cat` 把文件流式写进 MediaStore（Movies/MiBackscreen）→ 删除临时文件。
 *
 * 单例：录制跨越页面重组，停止 / 保存在自己的作用域里跑完，页面销毁也不会被打断。
 */
internal object RearScreenRecorder {
    private const val TAG = Constants.LOG_TAG + ":RearRecorder"

    const val MAX_DURATION_SECONDS = RearRecordScripts.MAX_DURATION_SECONDS
    private const val TEMP_FILE = RearRecordScripts.TEMP_FILE
    /** 小于这个字节数视为没录到画面（例如背屏全程没亮），不往相册里存。 */
    private const val MIN_VALID_BYTES = 4096L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val mutableState = MutableStateFlow<RearRecorderState>(RearRecorderState.Idle)
    val state: StateFlow<RearRecorderState> = mutableState

    private val mutableEvents = MutableSharedFlow<RearRecorderEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<RearRecorderEvent> = mutableEvents

    @Volatile private var cachedIds: RearDisplayIds? = null
    @Volatile private var session: Session? = null
    /** 启动过程中收到停止请求（例如刚点开始就切后台）：启动完成后立刻结束。 */
    private val stopRequested = AtomicBoolean(false)

    private class Session(
        val process: Process,
        val pid: Int,
        val stderrTail: StringBuilder,
    ) {
        val finishing = AtomicBoolean(false)
        var autoStopJob: Job? = null
        var exitWatchJob: Job? = null
    }

    fun start(context: Context) {
        val appContext = context.applicationContext
        if (!mutableState.compareAndSet(RearRecorderState.Idle, RearRecorderState.Starting)) return
        stopRequested.set(false)
        scope.launch {
            val failure = runCatching { startSession() }.getOrElse { error ->
                Log.w(TAG, "Start failed", error)
                R.string.rear_record_failed_start
            }
            if (failure != null) {
                mutableState.value = RearRecorderState.Idle
                emit(appContext, RearRecorderEvent.Failed(failure))
                return@launch
            }
            val current = session ?: return@launch
            current.autoStopJob = scope.launch {
                delay(TimeUnit.SECONDS.toMillis(MAX_DURATION_SECONDS))
                finish(appContext, current)
            }
            // screenrecord 自己到时退出、或被系统杀掉时也走同一条收尾流程。
            current.exitWatchJob = scope.launch {
                runInterruptible { current.process.waitFor() }
                finish(appContext, current)
            }
            if (stopRequested.get()) finish(appContext, current)
        }
    }

    /** 停止并保存；没在录就什么也不做。页面销毁、切后台、点停止都调它。 */
    fun stop(context: Context) {
        val appContext = context.applicationContext
        val current = session
        if (current == null) {
            if (mutableState.value == RearRecorderState.Starting) stopRequested.set(true)
            return
        }
        scope.launch { finish(appContext, current) }
    }

    /** 启动录制；成功返回 null 并设置 [session]，失败返回提示文案。 */
    private fun startSession(): Int? {
        val ids = cachedIds ?: when (val resolved = resolveDisplayIds()) {
            is Resolution.Resolved -> resolved.ids.also { cachedIds = it }
            Resolution.NoRoot -> return R.string.rear_record_failed_no_root
            Resolution.NotFound -> return R.string.rear_record_failed_display
        }
        val process = try {
            RootShell.start(RearRecordScripts.record(ids))
        } catch (error: Exception) {
            Log.w(TAG, "su unavailable", error)
            return R.string.rear_record_failed_no_root
        }
        val stderrTail = StringBuilder()
        // stdout 第一行是 shell 的 pid（exec 之后就是 screenrecord 的 pid），其余输出一律丢弃。
        val pidReader = Executors.newSingleThreadExecutor()
        val pid = try {
            pidReader.submit<Int?> {
                val reader = process.inputStream.bufferedReader()
                val first = reader.readLine()?.trim()?.toIntOrNull()
                Thread({ runCatching { reader.forEachLine { } } }, "RearRecorderStdout").apply {
                    isDaemon = true
                    start()
                }
                first
            }.get(15, TimeUnit.SECONDS)
        } catch (_: Exception) {
            null
        } finally {
            pidReader.shutdownNow()
        }
        Thread({
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(stderrTail) {
                        stderrTail.append(line).append('\n')
                        if (stderrTail.length > 4096) stderrTail.delete(0, stderrTail.length - 4096)
                    }
                }
            }
        }, "RearRecorderStderr").apply {
            isDaemon = true
            start()
        }
        if (pid == null || pid <= 1) {
            process.destroyForcibly()
            cachedIds = null
            return R.string.rear_record_failed_start
        }
        // screenrecord 参数不对（例如 display id 失效）会立刻退出：等一下再确认还活着。
        if (process.waitFor(1500, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "screenrecord exited early: ${synchronized(stderrTail) { stderrTail.toString() }}")
            cachedIds = null
            return R.string.rear_record_failed_start
        }
        session = Session(process, pid, stderrTail)
        mutableState.value = RearRecorderState.Recording(SystemClock.elapsedRealtime())
        return null
    }

    private suspend fun finish(context: Context, current: Session) {
        if (!current.finishing.compareAndSet(false, true)) return
        // 收尾可能由自动停止 / 退出监听协程自己发起，下面会取消它们：整段放进 NonCancellable，
        // 保证 SIGINT、保存、清理一定跑完。
        withContext(NonCancellable) { finishLocked(context, current) }
    }

    private suspend fun finishLocked(context: Context, current: Session) {
        mutableState.value = RearRecorderState.Saving
        current.autoStopJob?.cancel()
        try {
            stopProcess(current)
            emit(context, saveToGallery(context))
        } catch (error: Throwable) {
            Log.w(TAG, "Finish failed", error)
            emit(context, RearRecorderEvent.Failed(R.string.rear_record_failed_save))
        } finally {
            current.exitWatchJob?.cancel()
            if (current.process.isAlive) current.process.destroyForcibly()
            RootShell.run("rm -f $TEMP_FILE\n", 10)
            if (session === current) session = null
            mutableState.value = RearRecorderState.Idle
        }
    }

    /**
     * 先发 SIGINT（screenrecord 收到后会写完 moov 再退出，文件才能播放）；
     * 超时才依次升级到 SIGTERM / SIGKILL。kill 前确认 pid 仍是 screenrecord，防止 pid 被复用误杀。
     */
    private suspend fun stopProcess(current: Session) {
        val process = current.process
        if (!process.isAlive) return
        for ((signal, waitSeconds) in listOf("INT" to 15L, "TERM" to 3L, "KILL" to 2L)) {
            RootShell.run(RearRecordScripts.signal(current.pid, signal), 10)
            val exited = runInterruptible { process.waitFor(waitSeconds, TimeUnit.SECONDS) }
            if (exited) return
            Log.w(TAG, "screenrecord did not exit after SIG$signal")
        }
        process.destroyForcibly()
    }

    private fun saveToGallery(context: Context): RearRecorderEvent {
        val size = RootShell.run("stat -c %s $TEMP_FILE 2>/dev/null || echo 0\n", 10)
            ?.text?.trim()?.toLongOrNull() ?: 0L
        if (size < MIN_VALID_BYTES) return RearRecorderEvent.Failed(R.string.rear_record_failed_empty)

        val displayName = "Backscreen_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val resolver = context.contentResolver
        var uri: Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MiBackscreen")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val inserted = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return RearRecorderEvent.Failed(R.string.rear_record_failed_save)
            uri = inserted
            val copied = copyTempFileTo(resolver.openOutputStream(inserted))
            if (copied < size) {
                Log.w(TAG, "Copied $copied of $size bytes")
                resolver.delete(inserted, null, null)
                return RearRecorderEvent.Failed(R.string.rear_record_failed_save)
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(inserted, values, null, null)
            return RearRecorderEvent.Saved(displayName)
        } catch (error: Exception) {
            Log.w(TAG, "Save failed", error)
            // 写入失败：删掉 pending 记录，不在相册里留空占位。
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            return RearRecorderEvent.Failed(R.string.rear_record_failed_save)
        }
    }

    /** root `cat` 临时文件，流式写进相册输出流；返回写入字节数，失败返回 -1。 */
    private fun copyTempFileTo(output: java.io.OutputStream?): Long {
        if (output == null) return -1
        val process = RootShell.start("exec cat $TEMP_FILE\n")
        return try {
            Thread({ runCatching { process.errorStream.use { it.readBytes() } } }, "RearRecorderCatErr").apply {
                isDaemon = true
                start()
            }
            val copied = output.use { out -> process.inputStream.use { it.copyTo(out) } }
            if (process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) copied else -1
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private sealed interface Resolution {
        data class Resolved(val ids: RearDisplayIds) : Resolution
        data object NoRoot : Resolution
        data object NotFound : Resolution
    }

    private fun resolveDisplayIds(): Resolution {
        val result = RootShell.run(RearRecordScripts.RESOLVE, 30) ?: return Resolution.NoRoot
        val text = result.text
        if (!text.contains("${RearRecordScripts.UID_MARKER}=0")) return Resolution.NoRoot
        val displayPart = text.substringAfter(RearRecordScripts.DISPLAY_MARKER, "")
            .substringBefore(RearRecordScripts.SURFACE_FLINGER_MARKER)
        val surfaceFlingerPart = text.substringAfter(RearRecordScripts.SURFACE_FLINGER_MARKER, "")
        val ids = RearDisplayIdParser.resolve(displayPart, surfaceFlingerPart)
        if (ids == null) {
            Log.w(TAG, "Rear display not found in dumpsys output (${text.length} chars)")
            return Resolution.NotFound
        }
        Log.i(TAG, "Rear display resolved: logical=${ids.logicalId} physical=${ids.physicalId}")
        return Resolution.Resolved(ids)
    }

    private fun emit(context: Context, event: RearRecorderEvent) {
        if (mutableEvents.subscriptionCount.value > 0 && mutableEvents.tryEmit(event)) return
        // 页面已经不在（切后台 / 已销毁）：用 Toast 告知结果。
        val message = when (event) {
            is RearRecorderEvent.Saved -> context.getString(R.string.rear_record_saved, event.displayName)
            is RearRecorderEvent.Failed -> context.getString(event.messageRes)
        }
        mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }
}
