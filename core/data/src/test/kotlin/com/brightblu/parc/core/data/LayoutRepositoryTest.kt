package com.brightblu.parc.core.data

import com.brightblu.parc.model.ActionKind
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.LayoutJson
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import com.brightblu.parc.model.SlotPosition
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutRepositoryTest {

    private val alpha = appId("com.alpha/com.alpha.Main")
    private val beta = appId("com.beta/com.beta.Main")
    private val gamma = appId("com.gamma/com.gamma.Main")
    private val delta = appId("com.delta/com.delta.Main")
    private val social = FolderId("social")
    private val newId = FolderId("new")
    private val atA1 = { index: Int -> SlotPosition("A1", index) }
    private val atA2 = { index: Int -> SlotPosition("A2", index) }

    private fun seeded(): ParcLayout = ParcLayout(
        version = LayoutVersion,
        arcs = homeArcSpecs().map { spec ->
            when (spec.id) {
                "A1" -> ArcAssignment(spec, mapOf(0 to Slot.App(alpha)))
                "A2" -> ArcAssignment(spec, mapOf(4 to Slot.Action(ActionKind.Settings)))
                else -> ArcAssignment(spec, emptyMap())
            }
        },
        folders = listOf(folder("social", "Social", listOf(alpha, beta))),
        pins = setOf(alpha),
    )

    private fun harness(seed: ParcLayout? = seeded()): RepositoryHarness = RepositoryHarness(seed = seed)

    private suspend fun RepositoryHarness.loadInstalled(vararg entries: AppEntry) {
        repository.load(entries.toList())
    }

    @Test
    fun `load publishes the seed and never seeds twice`() = runTest {
        val h = harness()

        h.loadInstalled(appEntry(alpha), appEntry(beta), appEntry(gamma))

        assertEquals(seeded(), h.repository.layout.value)
        assertFalse(h.dao.rows().isEmpty())

        h.loadInstalled(appEntry(alpha))

        assertEquals(1, h.dao.rows().folders.size)
        assertEquals(seeded(), h.repository.layout.value)
    }

    @Test
    fun `seeding waits for an installed list and never persists an empty layout`() = runTest {
        val h = harness()

        h.repository.load(emptyList())

        assertTrue(h.repository.layout.value.arcs.all { it.slots.isEmpty() })
        assertTrue(h.dao.rows().isEmpty())

        h.loadInstalled(appEntry(alpha))

        assertEquals(setOf(alpha), h.repository.layout.value.pins)
        assertFalse(h.dao.rows().isEmpty())
    }

    @Test
    fun `process kill restores the mutated layout`() = runTest {
        val h = harness()
        h.loadInstalled(appEntry(alpha), appEntry(beta), appEntry(gamma))
        h.repository.pin(gamma, atA2(0))
        h.repository.createFolder(FolderId("tools"), "Tools")
        h.repository.renameFolder(social, "Friends")
        h.repository.hide(beta)

        val restarted = h.newRepository()
        restarted.load(listOf(appEntry(alpha), appEntry(beta), appEntry(gamma)))

        assertEquals(h.repository.layout.value, restarted.layout.value)
    }

    @Test
    fun `pin occupies the slot, flags it and survives a restart`() = runTest {
        val h = harness()
        h.loadInstalled(appEntry(alpha), appEntry(beta))

        h.repository.pin(beta, atA1(1))

        assertEquals(Slot.App(beta), h.repository.layout.value.arcs.first().slots[1])
        assertTrue(beta in h.repository.layout.value.pins)

        val restarted = h.newRepository()
        restarted.load(listOf(appEntry(alpha), appEntry(beta)))

        assertEquals(Slot.App(beta), restarted.layout.value.arcs.first().slots[1])
        assertTrue(beta in restarted.layout.value.pins)
    }

    @Test
    fun `unpin clears the flag and keeps the app in its slot`() = runTest {
        val h = harness()
        h.loadInstalled(appEntry(alpha))

        h.repository.unpin(alpha)

        assertTrue(h.repository.layout.value.pins.isEmpty())
        assertEquals(Slot.App(alpha), h.repository.layout.value.arcs.first().slots[0])
    }

    @Test
    fun `move swaps two slots and can move into an empty one`() = runTest {
        val h = harness()
        h.loadInstalled(appEntry(alpha))

        h.repository.move(atA1(0), atA1(3))
        assertEquals(Slot.App(alpha), h.repository.layout.value.arcs.first().slots[3])
        assertNull(h.repository.layout.value.arcs.first().slots[0])

        h.repository.move(atA1(3), atA2(1))
        assertEquals(Slot.App(alpha), h.repository.layout.value.arcs[1].slots[1])
        assertNull(h.repository.layout.value.arcs.first().slots[3])
    }

    @Test
    fun `pin rejects an unknown arc and a negative index`() = runTest {
        val h = harness()
        h.loadInstalled(appEntry(alpha))

        assertTrue(runCatching { h.repository.pin(beta, SlotPosition("A9", 0)) }.isFailure)
        assertTrue(runCatching { h.repository.pin(beta, atA1(-1)) }.isFailure)
        assertEquals(seeded(), h.repository.layout.value)
    }

    @Test
    fun `pin accepts an index beyond the arc capacity because a ring may hold more than it shows`() =
        runTest {
            // T36: only a negative index and an unknown arc are invalid.
            val h = harness()
            h.loadInstalled(appEntry(alpha), appEntry(beta))

            h.repository.pin(beta, SlotPosition("A2", 7))

            assertEquals(Slot.App(beta), h.repository.layout.value.arcs[1].slots[7])
            assertTrue(beta in h.repository.layout.value.pins)
        }

    @Test
    fun `an index beyond the arc capacity survives Room and an export-import round trip`() = runTest {
        val h = harness()
        h.loadInstalled(appEntry(alpha), appEntry(beta))
        h.repository.pin(beta, SlotPosition("A2", 7))
        val exported = h.repository.exportLayout()

        val restarted = h.newRepository()
        restarted.load(listOf(appEntry(alpha), appEntry(beta)))
        assertEquals(Slot.App(beta), restarted.layout.value.arcs[1].slots[7])

        restarted.unpin(beta)
        restarted.importLayout(exported)
        assertEquals(Slot.App(beta), restarted.layout.value.arcs[1].slots[7])
        assertTrue(beta in restarted.layout.value.pins)
    }

    @Test
    fun `move rejects an unknown arc and a negative index`() = runTest {
        val h = harness()
        h.loadInstalled(appEntry(alpha))

        assertTrue(runCatching { h.repository.move(atA1(0), SlotPosition("A2", -2)) }.isFailure)
        assertTrue(runCatching { h.repository.move(SlotPosition("A9", 0), atA1(1)) }.isFailure)
        assertEquals(seeded(), h.repository.layout.value)
    }

    @Test
    fun `createFolder adds once and rejects a duplicate id`() = runTest {
        val h = harness()
        h.loadInstalled(appEntry(alpha))

        h.repository.createFolder(FolderId("tools"), "Tools")

        assertEquals("Tools", h.repository.layout.value.folders.last().name)
        assertTrue(runCatching { h.repository.createFolder(FolderId("tools"), "Again") }.isFailure)
    }

    @Test
    fun `renameFolder renames and rejects an unknown id`() = runTest {
        val h = harness()
        h.loadInstalled(appEntry(alpha))

        h.repository.renameFolder(social, "Friends")

        assertEquals("Friends", h.repository.layout.value.folders.single().name)
        assertTrue(runCatching { h.repository.renameFolder(FolderId("nope"), "x") }.isFailure)
    }

    @Test
    fun `createFolder accepts eight characters and rejects nine`() = runTest {
        val h = harness()
        h.loadInstalled(appEntry(alpha))

        h.repository.createFolder(FolderId("tools"), "12345678")

        assertEquals("12345678", h.repository.layout.value.folders.last().name)
        val failure = runCatching { h.repository.createFolder(FolderId("long"), "abcdefghi") }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $failure", failure is IllegalArgumentException)
        assertTrue(h.repository.layout.value.folders.none { it.id == FolderId("long") })
    }

    @Test
    fun `renameFolder accepts eight characters and rejects nine without changing the layout`() = runTest {
        val h = harness()
        h.loadInstalled(appEntry(alpha))

        h.repository.renameFolder(social, "12345678")
        val renamed = h.repository.layout.value

        val failure = runCatching { h.repository.renameFolder(social, "abcdefghi") }.exceptionOrNull()

        assertTrue("expected IllegalArgumentException, got $failure", failure is IllegalArgumentException)
        assertEquals(renamed, h.repository.layout.value)
        assertEquals("12345678", h.repository.layout.value.folders.single().name)
    }

    @Test
    fun `addToFolder appends once and rejects an unknown folder`() = runTest {
        val h = harness()
        h.loadInstalled(appEntry(alpha))

        h.repository.addToFolder(social, gamma)
        h.repository.addToFolder(social, gamma)

        assertEquals(listOf(alpha, beta, gamma), h.repository.layout.value.folders.single().appIds)
        assertTrue(runCatching { h.repository.addToFolder(FolderId("nope"), gamma) }.isFailure)
    }

    @Test
    fun `removeFromFolder removes and rejects an unknown folder`() = runTest {
        val h = harness()
        h.loadInstalled(appEntry(alpha))

        h.repository.removeFromFolder(social, alpha)

        assertEquals(listOf(beta), h.repository.layout.value.folders.single().appIds)
        assertTrue(runCatching { h.repository.removeFromFolder(FolderId("nope"), alpha) }.isFailure)
    }

    @Test
    fun `hide and unhide round trip through a restart`() = runTest {
        val h = harness()
        h.loadInstalled(appEntry(alpha), appEntry(beta))

        h.repository.hide(beta)
        assertEquals(setOf(beta), h.repository.layout.value.hidden)

        val restarted = h.newRepository()
        restarted.load(listOf(appEntry(alpha), appEntry(beta)))
        assertEquals(setOf(beta), restarted.layout.value.hidden)

        restarted.unhide(beta)
        assertTrue(restarted.layout.value.hidden.isEmpty())
    }

    @Test
    fun `a stored label override survives a hidden flag change`() = runTest {
        val h = harness()
        h.loadInstalled(appEntry(alpha))
        h.dao.insertAppMetaIfAbsent(listOf(AppMetaEntity(appId = beta.value, labelOverride = "Bee")))

        h.repository.hide(beta)
        h.repository.unhide(beta)

        val meta = h.dao.metaRows().single { it.appId == beta.value }
        assertEquals("Bee", meta.labelOverride)
        assertFalse(meta.hidden)
    }

    @Test
    fun `export then import restores the exported layout`() = runTest {
        val h = harness()
        h.loadInstalled(appEntry(alpha))
        h.repository.pin(gamma, atA2(0))
        val exported = h.repository.exportLayout()

        h.repository.unpin(alpha)
        h.repository.importLayout(exported)

        assertEquals(setOf(alpha, gamma), h.repository.layout.value.pins)
        val restarted = h.newRepository()
        restarted.load(listOf(appEntry(alpha)))
        assertEquals(h.repository.layout.value, restarted.layout.value)
    }

    @Test
    fun `import rejects an unsupported or extended document and changes nothing`() = runTest {
        val h = harness()
        h.loadInstalled(appEntry(alpha))

        assertTrue(runCatching { h.repository.importLayout("""{"version":9}""") }.isFailure)
        assertTrue(runCatching { h.repository.importLayout("""{"version":1,"arcs":[],"unknown":1}""") }.isFailure)
        assertTrue(runCatching { h.repository.importLayout("not json") }.isFailure)
        assertEquals(seeded(), h.repository.layout.value)
    }

    @Test
    fun `import refuses a backup that uses arcs this build does not have`() = runTest {
        val h = harness()
        h.loadInstalled(appEntry(alpha))
        val foreign = LayoutJson.encode(
            ParcLayout(
                version = LayoutVersion,
                arcs = listOf(
                    ArcAssignment(
                        ArcSpec("A9", 0, 100f, 180f, 270f, 4, Layer.Home),
                        mapOf(0 to Slot.App(beta)),
                    ),
                ),
            ),
        )

        val failure = runCatching { h.repository.importLayout(foreign) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(seeded(), h.repository.layout.value)
    }

    @Test
    fun `mutating before load fails loudly instead of writing an empty layout`() = runTest {
        val h = harness()

        val failure = runCatching { h.repository.pin(beta, atA1(1)) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(h.dao.rows().isEmpty())
    }

    @Test
    fun `a database failure falls back to the seed and never leaves the screen empty`() = runTest {
        val broken = object : LayoutStore {
            override suspend fun read(): LayoutRows = throw IllegalStateException("database unavailable")
            override suspend fun write(rows: LayoutRows) = throw IllegalStateException("database unavailable")
        }
        val h = RepositoryHarness(seed = seeded(), store = broken)

        h.loadInstalled(appEntry(alpha), appEntry(beta))

        assertEquals(seeded(), h.repository.layout.value)
        assertTrue(h.warnings.any { it.contains("read") })
        // OPUS6 (audit F3): nothing is written over a store that could not be read.
        assertFalse(h.warnings.any { it.contains("write") })
        assertFalse(h.repository.loaded)
    }

    @Test
    fun `pinned slot positions never change when apps are uninstalled or installed`() = runTest {
        val h = harness()
        h.loadInstalled(appEntry(alpha), appEntry(beta), appEntry(gamma))
        h.repository.pin(beta, atA1(1))
        h.repository.pin(gamma, atA1(2))
        val before = positions(h.repository.layout.value)

        val installed = listOf(appEntry(alpha), appEntry(gamma), appEntry(delta, installTime = 5L))
        h.repository.syncInstalled(installed)

        val after = h.repository.layout.value
        assertEquals(Slot.App(gamma), after.arcs.first().slots[2])
        assertNull(after.arcs.first().slots[1])
        assertEquals(setOf(alpha, gamma), after.pins)
        assertEquals(listOf(delta), after.folders.first { it.id == newId }.appIds)
        // Beta is gone: every slot that is left sits exactly where it was.
        positions(after).forEach { (slot, position) -> assertEquals(position, before[slot]) }
        val afterUninstall = positions(after)

        // Beta comes back: it lands in New, never in the pinned slot it used to hold.
        h.repository.syncInstalled(installed + appEntry(beta, installTime = 9L))

        assertNull(h.repository.layout.value.arcs.first().slots[1])
        assertEquals(listOf(beta, delta), h.repository.layout.value.folders.first { it.id == newId }.appIds)
        assertEquals(afterUninstall, positions(h.repository.layout.value).filterKeys { it in afterUninstall })
    }

    /** Slot payload to (arcId, slotIndex); the check for "nothing moved". */
    private fun positions(layout: ParcLayout): Map<Slot, Pair<String, Int>> =
        layout.arcs.flatMap { assignment ->
            assignment.slots.map { (index, slot) -> slot to (assignment.arc.id to index) }
        }.toMap()

    @Test
    fun `syncInstalled never rewrites the pinned assignments`() = runTest {
        val installed = listOf(appEntry(alpha), appEntry(beta), appEntry(gamma))
        val h = harness()
        h.loadInstalled(*installed.toTypedArray())
        val before = h.dao.rows()

        h.repository.syncInstalled(installed + appEntry(delta, installTime = 1L))
        h.repository.syncInstalled(installed)

        assertEquals(before.assignments, h.dao.rows().assignments)
    }
}
