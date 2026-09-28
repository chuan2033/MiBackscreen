package hook.HyperBackscreen.ui.pickup

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import hook.HyperBackscreen.bridge.PrefsBridge
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.common.PickupCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PickupGroup(val station: String, val codes: List<String>) {
    val identity: String get() = PickupCodes.displayIdentity(codes, station)
}

internal data class PickupPayload(val groups: List<PickupGroup> = emptyList()) {
    val codeCount: Int get() = groups.sumOf { it.codes.size }
}

internal enum class PickupSaveResult { Synced, Pending, Failed, RefreshFailed }

internal interface PickupSelectionStore {
    fun read(): String
    fun commit(encoded: String): PickupSaveResult
    fun refresh(): Boolean
}

internal fun initialVisibleCodes(group: PickupGroup, stored: String): Set<String> {
    val selection = PickupCodes.selectionForIdentity(stored, group.identity)
    val identity = if (selection.isNotEmpty()) group.identity else PickupCodes.identity(group.codes, group.station)
    return PickupCodes.applyIslandSelection(group.codes, identity,
        PickupCodes.selectionForIdentity(stored, identity)).toSet()
}

/** Serialize UI read/modify/write transactions across Activity instances. No preference format changes. */
private val selectionWriteLock = Any()

internal class PickupSelectionRepository(private val store: PickupSelectionStore) {
    fun load(payload: PickupPayload): Map<String, Set<String>> = synchronized(selectionWriteLock) {
        val stored = store.read()
        payload.groups.associate { it.identity to initialVisibleCodes(it, stored) }
    }

    fun save(payload: PickupPayload, selections: Map<String, Set<String>>): PickupSaveResult =
        synchronized(selectionWriteLock) {
            var encoded = store.read()
            for (group in payload.groups) {
                val selected = selections[group.identity] ?: return@synchronized PickupSaveResult.Failed
                if (!group.codes.containsAll(selected)) return@synchronized PickupSaveResult.Failed
                // Clear legacy records too; otherwise selecting all may revive an old partial selection.
                encoded = PickupCodes.upsertIslandSelection(encoded,
                    PickupCodes.identity(group.codes, group.station), emptyList(), true)
                encoded = PickupCodes.upsertIslandSelection(encoded, group.identity,
                    group.codes.filter(selected::contains), selected.containsAll(group.codes))
            }
            if (payload.groups.any { initialVisibleCodes(it, encoded) != selections[it.identity] }) {
                return@synchronized PickupSaveResult.Failed
            }
            val result = store.commit(encoded)
            // Complete the refresh in this transaction even if the Activity has since closed.
            if (result == PickupSaveResult.Synced && !refresh()) PickupSaveResult.RefreshFailed else result
        }

    fun refresh(): Boolean = try { store.refresh() } catch (_: Exception) { false }

    companion object {
        fun forContext(context: Context): PickupSelectionRepository {
            val app = context.applicationContext
            return PickupSelectionRepository(object : PickupSelectionStore {
                override fun read() = PrefsBridge.readPickupIslandSelectionForUi(app)
                override fun commit(encoded: String) = when (PrefsBridge.savePickupIslandSelectionFromUi(app, encoded)) {
                    PrefsBridge.PickupSaveResult.SYNCED -> PickupSaveResult.Synced
                    PrefsBridge.PickupSaveResult.PENDING -> PickupSaveResult.Pending
                    else -> PickupSaveResult.Failed
                }
                override fun refresh(): Boolean {
                    app.sendBroadcast(Intent(Constants.ACTION_REFRESH_PICKUP_ISLAND)
                        .setPackage(Constants.VOICE_ASSIST_PACKAGE))
                    return true
                }
            })
        }
    }
}

internal data class PickupSelectionState(
    val payload: PickupPayload? = null,
    val selections: Map<String, Set<String>> = emptyMap(),
    val busy: Boolean = true,
    val loadFailed: Boolean = false,
    val result: PickupSaveResult? = null,
)

/** Saved selections and pending feedback survive Activity recreation; stale payloads cannot retry. */
internal class PickupSelectionViewModel(private val repository: PickupSelectionRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(PickupSelectionState())
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var retrySelection: Map<String, Set<String>>? = null
    private var revision = 0L

    fun bind(payload: PickupPayload, reload: Boolean = false) {
        if (!reload && mutableState.value.payload == payload) return
        revision++
        retrySelection = null
        loadJob?.cancel()
        mutableState.value = PickupSelectionState(payload)
        loadJob = viewModelScope.launch {
            saveJob?.join()
            try {
                val selections = withContext(Dispatchers.IO) { repository.load(payload) }
                mutableState.value = PickupSelectionState(payload, selections, busy = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.value = PickupSelectionState(payload, busy = false, loadFailed = true)
            }
        }
    }

    fun save(payload: PickupPayload, selections: Map<String, Set<String>>) {
        val previous = mutableState.value
        if (previous.payload != payload || previous.busy || previous.loadFailed || saveJob?.isActive == true) return
        val snapshot = selections.mapValues { it.value.toSet() }
        val requestRevision = revision
        retrySelection = snapshot
        mutableState.value = previous.copy(busy = true, result = null)
        saveJob = viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { repository.save(payload, snapshot) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PickupSaveResult.Failed }
            if (revision != requestRevision) return@launch
            mutableState.value = previous.copy(busy = false, result = result,
                selections = if (result == PickupSaveResult.Failed) previous.selections else snapshot)
        }
    }

    fun retry(payload: PickupPayload) {
        val current = mutableState.value
        if (current.payload != payload || current.busy) return
        if (current.loadFailed) bind(payload, reload = true)
        else if (current.result == PickupSaveResult.Failed) retrySelection?.let { save(payload, it) }
        else if (current.result == PickupSaveResult.RefreshFailed) {
            val requestRevision = revision
            mutableState.value = current.copy(busy = true, result = null)
            saveJob = viewModelScope.launch {
                val ok = withContext(Dispatchers.IO) { repository.refresh() }
                if (revision == requestRevision) {
                    mutableState.value = current.copy(result = if (ok) PickupSaveResult.Synced else PickupSaveResult.RefreshFailed)
                }
            }
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val repository = PickupSelectionRepository.forContext(context)
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == PickupSelectionViewModel::class.java)
            return PickupSelectionViewModel(repository) as T
        }
    }
}
