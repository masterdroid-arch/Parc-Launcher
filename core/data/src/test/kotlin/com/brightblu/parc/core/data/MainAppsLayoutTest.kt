package com.brightblu.parc.core.data

import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import com.brightblu.parc.model.SlotPosition
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Applying the picker's four apps to the innermost arc (T62), with a fake prefs store. */
class MainAppsLayoutTest {

    private val alpha = appId("com.alpha/com.alpha.Main")
    private val beta = appId("com.beta/com.beta.Main")
    private val gamma = appId("com.gamma/com.gamma.Main")
    private val unknown = appId("com.unknown/com.unknown.Main")
    private val installed = listOf(appEntry(alpha), appEntry(beta), appEntry(gamma))

    private fun seeded(): ParcLayout = ParcLayout(
        version = LayoutVersion,
        arcs = homeArcSpecs().map { spec ->
            if (spec.id == "A1") ArcAssignment(spec, mapOf(0 to Slot.App(alpha))) else ArcAssignment(spec, emptyMap())
        },
        pins = setOf(alpha),
    )

    private fun ParcLayout.a1(): Map<Int, Slot> = arcs.first { it.arc.id == "A1" }.slots

    @Test
    fun `a pending picker write is applied on cold start and marked once`() = runTest {
        val store = FakeMainAppsStore(revision = 1L, ids = listOf(gamma, beta))
        val h = RepositoryHarness(seed = seeded(), mainAppsStore = store)

        h.repository.load(installed)

        assertEquals(mapOf(0 to Slot.App(gamma), 1 to Slot.App(beta)), h.repository.layout.value.a1())
        assertEquals(setOf(gamma, beta), h.repository.layout.value.pins)
        assertEquals(1L, store.applied)

        // A second start sees the marker and leaves the arrangement alone.
        val restarted = h.newRepository()
        restarted.load(installed)
        assertEquals(h.repository.layout.value, restarted.layout.value)
        assertEquals(1L, store.applied)
    }

    @Test
    fun `an already applied revision never overrides a manual arrangement`() = runTest {
        val store = FakeMainAppsStore(
            revision = 1L,
            applied = 1L,
            ids = listOf(gamma, beta),
        )
        val h = RepositoryHarness(seed = seeded(), mainAppsStore = store)

        h.repository.load(installed)

        assertEquals(mapOf(0 to Slot.App(alpha)), h.repository.layout.value.a1())
        assertEquals(setOf(alpha), h.repository.layout.value.pins)
    }

    @Test
    fun `a picker save during the session applies without a restart`() = runTest {
        val store = FakeMainAppsStore()
        val h = RepositoryHarness(seed = seeded(), mainAppsStore = store)
        h.repository.load(installed)

        store.ids = listOf(beta)
        store.revision = 1L
        h.repository.applyPendingMainApps()

        assertEquals(mapOf(0 to Slot.App(beta)), h.repository.layout.value.a1())
        assertEquals(setOf(beta), h.repository.layout.value.pins)
        assertEquals(1L, store.applied)
    }

    @Test
    fun `picker ids that are not installed are dropped`() = runTest {
        val store = FakeMainAppsStore(revision = 1L, ids = listOf(unknown, beta))
        val h = RepositoryHarness(seed = seeded(), mainAppsStore = store)

        h.repository.load(installed)

        assertEquals(mapOf(0 to Slot.App(beta)), h.repository.layout.value.a1())
        assertTrue(unknown !in h.repository.layout.value.pins)
    }

    @Test
    fun `at most four apps occupy the innermost arc`() = runTest {
        val extra = (1..4).map { appId("com.extra$it/com.extra$it.Main") }
        val many = listOf(alpha, beta, gamma) + extra
        val store = FakeMainAppsStore(revision = 1L, ids = many)
        val h = RepositoryHarness(seed = seeded(), mainAppsStore = store)

        h.repository.load(listOf(appEntry(alpha), appEntry(beta), appEntry(gamma)) + extra.map { appEntry(it) })

        assertEquals(4, h.repository.layout.value.a1().size)
        assertEquals((0..3).toSet(), h.repository.layout.value.a1().keys)
        assertEquals(many.take(4).toSet(), h.repository.layout.value.pins)
    }

    @Test
    fun `pinning a picker app elsewhere keeps that pin when the picker changes`() = runTest {
        val store = FakeMainAppsStore(revision = 1L, applied = 1L, ids = listOf(alpha))
        val h = RepositoryHarness(seed = seeded(), mainAppsStore = store)
        h.repository.load(installed)
        // Alpha also sits on A2 in the user's own arrangement.
        h.repository.pin(alpha, SlotPosition("A2", 0))

        store.ids = listOf(gamma)
        store.revision = 2L
        h.repository.applyPendingMainApps()

        assertTrue(alpha in h.repository.layout.value.pins)
        assertEquals(mapOf(0 to Slot.App(gamma)), h.repository.layout.value.a1())
    }

    @Test
    fun `row operations preserve search placements drag results and overflow through restart`() = runTest {
        val h = RepositoryHarness(seed = seeded())
        val repo = h.repository
        repo.load(installed)
        val overflow = SlotPosition("A1", 7)
        val placed = SlotPosition("A1", 2)
        // Search pins a previously unplaced result to the chosen position.
        repo.pin(beta, placed)
        repo.pin(gamma, overflow)
        repo.move(placed, placed.copy(index = 3))
        fun slots() = repo.layout.value.a1()
        assertEquals(mapOf(0 to Slot.App(alpha), 3 to Slot.App(beta), 7 to Slot.App(gamma)), slots())
        repo.updateLayout { it.editMainApp(MainAppEdit.Remove(alpha, 0), locked = false) }
        assertEquals(mapOf(3 to Slot.App(beta), 7 to Slot.App(gamma)), slots())
        repo.updateLayout { it.editMainApp(MainAppEdit.Add(alpha), locked = false) }
        repo.updateLayout { it.editMainApp(MainAppEdit.Move(alpha, 0, 1, null), locked = false) }
        val expected = mapOf(1 to Slot.App(alpha), 3 to Slot.App(beta), 7 to Slot.App(gamma))
        assertEquals(expected, slots())
        assertEquals(expected, h.newRepository().also { it.load(installed) }.layout.value.a1())
    }

    @Test
    fun `lock at save and a replaced destination both refuse the edit`() = runTest {
        val repo = RepositoryHarness(seed = seeded()).repository
        repo.load(installed)
        val tap = MainAppEdit.Move(alpha, 0, 1, null)
        repo.pin(beta, SlotPosition("A1", 1))
        val before = repo.layout.value
        for (locked in listOf(true, false)) {
            val error = runCatching { repo.updateLayout { it.editMainApp(tap, locked) } }.exceptionOrNull()
            assertTrue(error is MainAppEditRefused)
            if (locked) assertTrue(error!!.message!!.contains("Unlock"))
            assertEquals(before, repo.layout.value)
        }
    }

    @Test
    fun `onboarding suggestions keep legacy positions beyond the fourth row`() = runTest {
        val repo = RepositoryHarness(seed = seeded()).repository
        repo.load(installed)
        repo.pin(alpha, SlotPosition("A1", 9))
        repo.applyMainApps(listOf(beta))
        assertEquals(mapOf(0 to Slot.App(beta), 9 to Slot.App(alpha)), repo.layout.value.a1())
        assertEquals(setOf(alpha, beta), repo.layout.value.pins)
    }

    @Test
    fun `adding an outer arc app fills only the inner hole and moves its pin`() = runTest {
        val repo = RepositoryHarness(seed = seeded()).repository
        repo.load(installed)
        repo.pin(beta, SlotPosition("A2", 0))
        repo.pin(gamma, SlotPosition("A1", 2))
        repo.updateLayout { it.editMainApp(MainAppEdit.Add(beta), locked = false) }
        assertEquals(mapOf(0 to Slot.App(alpha), 1 to Slot.App(beta), 2 to Slot.App(gamma)), repo.layout.value.a1())
        assertTrue(repo.layout.value.arcs.first { it.arc.id == "A2" }.slots.isEmpty())
        assertEquals(setOf(alpha, beta, gamma), repo.layout.value.pins)
    }
}
