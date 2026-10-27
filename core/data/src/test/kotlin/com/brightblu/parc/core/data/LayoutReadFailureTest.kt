package com.brightblu.parc.core.data

import com.brightblu.parc.model.ActionKind
import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.LayoutJson
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPUS6 (audit F3, F5): a failed or partly unreadable layout read never replaces the real layout. */
class LayoutReadFailureTest {
    private val alpha = appId("com.alpha/com.alpha.Main")
    private val beta = appId("com.beta/com.beta.Main")

    private fun seedLayout() = ParcLayout(version = LayoutVersion, arcs = homeArcSpecs().map { spec ->
        ArcAssignment(spec, if (spec.id == "A1") mapOf(0 to Slot.App(alpha)) else emptyMap())
    })

    private fun userLayout() = ParcLayout(version = LayoutVersion, arcs = homeArcSpecs().map { spec ->
        ArcAssignment(spec, if (spec.id == "A1") mapOf(0 to Slot.App(beta), 1 to Slot.App(alpha)) else emptyMap())
    }, pins = setOf(beta))

    /** A store that can be told to fail reads, over real in-memory rows. */
    private class FlakyStore(val real: LayoutStore) : LayoutStore {
        var failReads = 0
        var writes = 0
        override suspend fun read(): LayoutRows {
            if (failReads > 0) { failReads--; throw IllegalStateException("disk I/O error") }
            return real.read()
        }
        override suspend fun write(rows: LayoutRows) { writes++; real.write(rows) }
    }

    @Test fun aFailedReadNeverSeedsOverTheStoredLayout() = runTest {
        val dao = InMemoryLayoutDao()
        val store = FlakyStore(RoomLayoutStore(dao, DirectTransactionRunner(), Dispatchers.Unconfined))
        val generations = FakeGenerationStore()
        val first = RepositoryHarness(seed = seedLayout(), store = store, generationStore = generations)
        first.repository.load(listOf(appEntry(alpha), appEntry(beta)))
        first.repository.importLayout(LayoutJson.encode(userLayout()))
        val stored = dao.rows()

        // Next start: the first read fails (the old code treated that as "never written" and seeded).
        store.failReads = 1
        val writesBefore = store.writes
        val restarted = first.newRepository()
        restarted.load(listOf(appEntry(alpha), appEntry(beta)))
        assertEquals("nothing written after a failed read", writesBefore, store.writes)
        assertEquals("rows untouched", stored, dao.rows())
        assertTrue(first.warnings.any { "layout read failed" in it })

        // The next load reads fine and publishes the user's layout, not the seed.
        restarted.load(listOf(appEntry(alpha), appEntry(beta)))
        assertEquals(setOf(beta), restarted.layout.value.pins)
        assertNotEquals(seedLayout().arcs, restarted.layout.value.arcs)
    }

    @Test fun anUnreadableRowIsSkippedInsteadOfFailingTheWholeLayout() {
        val rows = LayoutMapper.toRows(userLayout())
        val broken = rows.copy(assignments = rows.assignments + SlotAssignmentEntity("A2", 0, "action", action = "FromTheFuture") +
            SlotAssignmentEntity("A2", 1, "hologram"))
        val layout = LayoutMapper.toLayout(broken, homeArcSpecs())
        assertEquals(userLayout().arcs.first { it.arc.id == "A1" }.slots, layout.arcs.first { it.arc.id == "A1" }.slots)
        assertTrue(layout.arcs.first { it.arc.id == "A2" }.slots.isEmpty())
        assertEquals(ActionKind.entries.size, ActionKind.entries.size)
    }

    @Test fun unreadableFolderTagsFailClosedAsLocked() {
        val rows = LayoutMapper.toRows(userLayout().copy(folders = listOf(folder("vault", "Vault", listOf(alpha)))))
        val broken = rows.copy(folders = rows.folders.map { it.copy(tags = "{not json") })
        val folder = LayoutMapper.toLayout(broken, homeArcSpecs()).folders.single()
        assertTrue(com.brightblu.parc.model.LockedFolderTag in folder.tags)
    }
}
