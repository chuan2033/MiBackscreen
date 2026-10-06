package hook.HyperBackscreen.ui.pickup

import hook.HyperBackscreen.common.PickupCodes
import org.junit.Assert.*
import org.junit.Test

class PickupSelectionRepositoryTest {
    private class Store : PickupSelectionStore {
        var stored = ""
        var result = PickupSaveResult.Synced
        var commits = 0
        var refreshes = 0
        var refreshOk = true
        override fun read() = stored
        override fun commit(encoded: String): PickupSaveResult {
            commits++
            if (result != PickupSaveResult.Failed) stored = encoded
            return result
        }
        override fun refresh(): Boolean { refreshes++; return refreshOk }
    }
    private val group = PickupGroup("test station", listOf("10-11", "20-22"))
    private val payload = PickupPayload(listOf(group))

    @Test fun successfulCommitPrecedesRefreshAndPreservesOtherStations() {
        val store = Store()
        val otherIdentity = PickupCodes.displayIdentity(listOf("90-99"), "other station")
        store.stored = PickupCodes.encodeHiddenIslandSelection(otherIdentity)
        val repository = PickupSelectionRepository(store)
        assertEquals(PickupSaveResult.Synced, repository.save(payload, mapOf(group.identity to setOf("10-11"))))
        assertEquals(setOf("10-11"), repository.load(payload)[group.identity])
        assertFalse(PickupCodes.selectionForIdentity(store.stored, otherIdentity).isEmpty())
        assertEquals(1, store.refreshes)
    }

    @Test fun commitFailureDoesNotRequestRefresh() {
        val store = Store().apply { result = PickupSaveResult.Failed }
        assertEquals(PickupSaveResult.Failed, PickupSelectionRepository(store).save(payload, mapOf(group.identity to emptySet())))
        assertEquals(0, store.refreshes)
        assertEquals("", store.stored)
    }

    @Test fun pendingWriteDoesNotPretendToBeSynced() {
        val store = Store().apply { result = PickupSaveResult.Pending }
        assertEquals(PickupSaveResult.Pending, PickupSelectionRepository(store).save(payload, mapOf(group.identity to emptySet())))
        assertEquals(0, store.refreshes)
        assertEquals(emptySet<String>(), PickupSelectionRepository(store).load(payload)[group.identity])
    }

    @Test fun refreshFailureIsDistinctFromCommitFailure() {
        val store = Store().apply { refreshOk = false }
        val repository = PickupSelectionRepository(store)
        assertEquals(PickupSaveResult.RefreshFailed, repository.save(payload, mapOf(group.identity to emptySet())))
        store.refreshOk = true
        assertTrue(repository.refresh())
        assertEquals(1, store.commits)
    }

    @Test fun selectAllClearsLegacyPartialSelection() {
        val store = Store().apply {
            stored = PickupCodes.encodeIslandSelection(PickupCodes.identity(group.codes, group.station), listOf("10-11"))
        }
        val repository = PickupSelectionRepository(store)
        assertEquals(setOf("10-11"), repository.load(payload)[group.identity])
        assertEquals(PickupSaveResult.Synced, repository.save(payload, mapOf(group.identity to group.codes.toSet())))
        assertEquals(group.codes.toSet(), repository.load(payload)[group.identity])
    }

    @Test fun invalidSelectionDoesNotWrite() {
        val store = Store()
        assertEquals(PickupSaveResult.Failed,
            PickupSelectionRepository(store).save(payload, mapOf(group.identity to setOf("wrong-99"))))
        assertEquals(0, store.commits)
    }

    @Test fun boundedEncodingFailureDoesNotCommitPartialBatch() {
        val groups = (1..6).map { n -> PickupGroup("station-$n", (1..64).map { "$n-${it.toString().padStart(30, '0')}" }) }
        val store = Store()
        val selections = groups.associate { it.identity to it.codes.dropLast(1).toSet() }
        assertEquals(PickupSaveResult.Failed, PickupSelectionRepository(store).save(PickupPayload(groups), selections))
        assertEquals(0, store.commits)
        assertEquals(0, store.refreshes)
    }

    @Test fun recordsPendingAndRefreshFailureWithoutPickupContents() {
        val events = mutableListOf<String>()
        val store = Store().apply { result = PickupSaveResult.Pending }
        val repository = PickupSelectionRepository(store) { events.add(it) }
        repository.save(payload, mapOf(group.identity to setOf("10-11")))
        assertTrue(events.any { it.contains("save result=Pending") })
        assertFalse(events.any { it.contains("refresh") })
        store.result = PickupSaveResult.Synced
        store.refreshOk = false
        repository.save(payload, mapOf(group.identity to setOf("10-11")))
        assertTrue(events.any { it.contains("refresh result=failed") })
        assertTrue(events.any { it.contains("save result=RefreshFailed") })
        assertFalse(events.any { it.contains("10-11") || it.contains("test station") })
    }

    @Test fun failedDiagnosticSinkDoesNotChangeSaveOrRefresh() {
        val store = Store()
        val repository = PickupSelectionRepository(store) { throw IllegalStateException("disk full") }
        assertEquals(PickupSaveResult.Synced, repository.save(payload, mapOf(group.identity to emptySet())))
        assertEquals(1, store.commits)
        assertEquals(1, store.refreshes)
    }

    @Test fun rejectedSelectionIsRecordedAndNeverCommitted() {
        val events = mutableListOf<String>()
        val store = Store()
        val repository = PickupSelectionRepository(store) { events.add(it) }
        assertEquals(PickupSaveResult.Failed, repository.save(payload, emptyMap()))
        assertTrue(events.any { it.contains("save result=Failed") })
        assertEquals(0, store.commits)
    }
}
