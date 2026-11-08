package hook.HyperBackscreen.ui.config

import kotlinx.coroutines.CancellationException

internal sealed interface AppListLoadResult<out T> {
    data class Loaded<T>(val apps: List<T>) : AppListLoadResult<T>
    data class Failed(val error: Exception) : AppListLoadResult<Nothing>
}

/** Empty visibility is a successful query; failures must leave existing selections intact. */
internal fun <T> loadAppList(query: () -> List<T>): AppListLoadResult<T> = try {
    AppListLoadResult.Loaded(query())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    AppListLoadResult.Failed(error)
}
