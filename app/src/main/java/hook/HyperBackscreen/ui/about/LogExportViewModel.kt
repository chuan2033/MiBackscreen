package hook.HyperBackscreen.ui.about

import android.content.Context
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

internal fun interface LogExportOperation {
    fun export(context: Context, progress: (Int, Int) -> Unit): File?
}

internal val LocalLogExportOperation = staticCompositionLocalOf {
    LogExportOperation { context, progress -> FeedbackLogExporter.create(context, progress) }
}

internal fun interface LogShareOperation {
    fun share(context: Context, file: File)
}

internal val LocalLogShareOperation = staticCompositionLocalOf {
    LogShareOperation { context, file -> FeedbackLogExporter.share(context, file) }
}

internal sealed interface LogExportState {
    data object Idle : LogExportState
    data class Running(val completed: Int, val total: Int) : LogExportState
    data class Ready(val file: File) : LogExportState
    data object Failed : LogExportState
}

/** Entry-scoped work survives Activity recreation, without retaining an Activity. */
internal class LogExportViewModel(
    application: Application,
    private val operation: LogExportOperation
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow<LogExportState>(LogExportState.Idle)
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        mutableState.value = LogExportState.Running(0, FeedbackLogExporter.TOTAL_STEPS)
        job = viewModelScope.launch {
            val file = try {
                withContext(Dispatchers.IO) {
                    operation.export(getApplication()) { completed, total ->
                        ensureActive()
                        mutableState.value = LogExportState.Running(completed.coerceIn(0, total.coerceAtLeast(1)), total.coerceAtLeast(1))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            mutableState.value = if (file != null && file.isFile && file.length() > 0L) {
                LogExportState.Ready(file)
            } else LogExportState.Failed
        }
    }

    class Factory(context: Context, private val operation: LogExportOperation) : ViewModelProvider.Factory {
        private val application = context.applicationContext as Application
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == LogExportViewModel::class.java)
            return LogExportViewModel(application, operation) as T
        }
    }
}
