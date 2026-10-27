package com.brightblu.parc.core.data.usage

import com.brightblu.parc.core.data.homeArcSpecs
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.LayoutRepository
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import com.brightblu.parc.model.SlotPosition
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cold-start behaviour of the usage layer (T34): when it ranks, what it writes through the frozen
 * `LayoutRepository`, and what the ring is told about it.
 */
class UsageLayerManagerTest {

    private val alpha = testId("alpha")
    private val beta = testId("beta")
    private val gamma = testId("gamma")
    private val delta = testId("delta")
    private val epsilon = testId("epsilon")
    private val pinned = testId("pinned")

    private val layer = listOf(alpha, beta, gamma, delta)
    private val installed = (layer + epsilon + pinned).toSet()

    /** 2026-10-16, 12:00 UTC: after the 04:00 boundary, so a first start ranks. */
    private val noon = Instant.parse("2026-10-16T12:00:00Z").toEpochMilli()
    private val nextNoon = Instant.parse("2026-10-17T12:00:00Z").toEpochMilli()
    private val sameDayLater = Instant.parse("2026-10-16T23:30:00Z").toEpochMilli()

    private class Fixture(
        layout: ParcLayout,
        scores: Map<AppId, Double>,
        val installed: Set<AppId>,
        val now: Long,
        val offset: Int,
        val store: FakeSlotStore,
    ) {
        val repository = FakeLayoutRepository(layout)
        val scoreSource = FakeScores(scores)
        val warnings = mutableListOf<String>()
        val manager = UsageLayerManager(
            repository = repository,
            scores = scoreSource,
            installed = { installed.toList() },
            slotStore = store,
            clock = { now },
            zoneOffsetMillis = { offset },
            logWarn = { warnings += it },
        )
    }

    private fun fixture(
        layout: ParcLayout = seededUsageLayout(layer, pinned),
        scores: Map<AppId, Double> = emptyMap(),
        installed: Set<AppId> = this.installed,
        now: Long = noon,
        offset: Int = 0,
        store: FakeSlotStore = FakeSlotStore(),
    ): Fixture = Fixture(layout, scores, installed, now, offset, store)

    private fun ParcLayout.appAt(arcId: String, index: Int): AppId? =
        arcs.first { it.arc.id == arcId }.slots[index]?.let { (it as? Slot.App)?.id }

    private val dayKey = UsageLayerRanking.dayKey(noon, 0)

    @Test
    fun `a first cold start waits for the launch log and stamps the 0400 day`() = runTest {
        val f = fixture()

        f.manager.refresh()

        assertTrue(f.scoreSource.awaited)
        assertEquals(dayKey, f.store.state.lastRankDay)
        assertEquals(1, f.store.writes)
        assertEquals(DefaultUsageSlots, f.store.state.slots)
        assertTrue(f.manager.lastChanges.value.isEmpty())
    }

    @Test
    fun `an app that beats an incumbent is written into its slot and left unlocked`() = runTest {
        val f = fixture(scores = mapOf(alpha to 1.0, beta to 10.0, gamma to 10.0, delta to 10.0, epsilon to 5.0))

        f.manager.refresh()

        assertEquals(epsilon, f.repository.layout.value.appAt("A2", 0))
        assertFalse(epsilon in f.repository.layout.value.pins)
        assertEquals(
            listOf(UsageSwap(SlotPosition("A2", 0), alpha, epsilon)),
            f.manager.lastChanges.value,
        )
    }

    @Test
    fun `a second start on the same 0400 day never moves anything`() = runTest {
        val f = fixture(scores = mapOf(alpha to 1.0))
        f.manager.refresh()
        val afterFirst = f.repository.layout.value

        f.scoreSource.set(mapOf(alpha to 1.0, epsilon to 100.0))
        f.manager.refresh()

        assertEquals(afterFirst, f.repository.layout.value)
        assertEquals(1, f.store.writes)
        assertTrue(f.manager.lastChanges.value.isEmpty())
    }

    @Test
    fun `a start on the next 0400 day ranks again`() = runTest {
        val store = FakeSlotStore(
            UsageSlotState(DefaultUsageSlots, UsageLayerRanking.dayKey(noon, 0)),
        )
        val f = fixture(
            scores = mapOf(alpha to 1.0, beta to 10.0, gamma to 10.0, delta to 10.0, epsilon to 5.0),
            now = nextNoon,
            store = store,
        )

        f.manager.refresh()

        assertEquals(epsilon, f.repository.layout.value.appAt("A2", 0))
        assertEquals(UsageLayerRanking.dayKey(nextNoon, 0), store.state.lastRankDay)
    }

    @Test
    fun `a start before 0400 still belongs to the previous day`() = runTest {
        val beforeFour = Instant.parse("2026-10-17T03:30:00Z").toEpochMilli()
        val store = FakeSlotStore(UsageSlotState(DefaultUsageSlots, UsageLayerRanking.dayKey(noon, 0)))
        val f = fixture(
            scores = mapOf(alpha to 1.0, epsilon to 100.0),
            now = beforeFour,
            store = store,
        )

        f.manager.refresh()

        assertEquals(alpha, f.repository.layout.value.appAt("A2", 0))
        assertEquals(0, store.writes)
        assertEquals(UsageLayerRanking.dayKey(noon, 0), store.state.lastRankDay)
    }

    @Test
    fun `an empty slot is filled and the change says where it came from`() = runTest {
        val f = fixture(
            layout = seededUsageLayout(listOf(alpha, beta, gamma), pinned),
            scores = mapOf(alpha to 5.0, beta to 5.0, gamma to 5.0, epsilon to 0.5),
        )

        f.manager.refresh()

        assertEquals(epsilon, f.repository.layout.value.appAt("A2", 3))
        assertNull(f.manager.lastChanges.value.single().from)
        assertEquals(epsilon, f.manager.lastChanges.value.single().to)
        assertEquals(SlotPosition("A2", 3), f.manager.lastChanges.value.single().position)
    }

    @Test
    fun `a hard-pinned slot is left alone even with a much higher scoring candidate`() = runTest {
        val f = fixture(
            layout = seededUsageLayout(layer, pinned, pins = setOf(alpha, pinned)),
            scores = mapOf(alpha to 0.0, epsilon to 1_000.0),
        )

        f.manager.refresh()

        assertEquals(alpha, f.repository.layout.value.appAt("A2", 0))
        assertTrue(alpha in f.repository.layout.value.pins)
        assertEquals(epsilon, f.repository.layout.value.appAt("A2", 1))
    }

    @Test
    fun `a hidden candidate never enters the layer`() = runTest {
        val f = fixture(
            layout = seededUsageLayout(layer, pinned, hidden = setOf(epsilon)),
            scores = mapOf(alpha to 1.0, epsilon to 1_000.0),
        )

        f.manager.refresh()

        assertEquals(alpha, f.repository.layout.value.appAt("A2", 0))
        assertTrue(f.manager.lastChanges.value.isEmpty())
    }

    @Test
    fun `a malformed usageSlots value is replaced by the default layer`() = runTest {
        val store = FakeSlotStore(UsageSlotState(listOf(SlotPosition("A9", 0))))
        val f = fixture(store = store)

        f.manager.refresh()

        assertEquals(DefaultUsageSlots, store.state.slots)
        assertEquals(dayKey, store.state.lastRankDay)
    }

    @Test
    fun `a geometry without the usage arc leaves the day unset instead of writing`() = runTest {
        val noA2 = ParcLayout(
            version = 1,
            arcs = homeArcSpecs().filter { it.id != "A2" }.map { ArcAssignment(it, emptyMap()) },
        )
        val f = fixture(layout = noA2)

        f.manager.refresh()

        assertNull(f.store.state.lastRankDay)
        assertEquals(0, f.store.writes)
        assertTrue(f.manager.lastChanges.value.isEmpty())
    }

    @Test
    fun `a layout write that fails costs the change but not the day marker`() = runTest {
        val delegate = FakeLayoutRepository(seededUsageLayout(layer, pinned))
        val refusing = object : LayoutRepository by delegate {
            override suspend fun pin(id: AppId, position: SlotPosition) {
                throw IllegalStateException("slot write unavailable")
            }
        }
        val store = FakeSlotStore()
        val manager = UsageLayerManager(
            repository = refusing,
            scores = FakeScores(mapOf(alpha to 1.0, epsilon to 100.0)),
            installed = { installed.toList() },
            slotStore = store,
            clock = { noon },
            zoneOffsetMillis = { 0 },
            logWarn = {},
        )

        manager.refresh()

        assertTrue(manager.lastChanges.value.isEmpty())
        assertEquals(dayKey, store.state.lastRankDay)
    }

    @Test
    fun `consumeChanges acknowledges a swap exactly once`() = runTest {
        val f = fixture(scores = mapOf(alpha to 1.0, beta to 10.0, gamma to 10.0, delta to 10.0, epsilon to 5.0))
        f.manager.refresh()

        assertEquals(1, f.manager.lastChanges.value.size)
        f.manager.consumeChanges()
        assertTrue(f.manager.lastChanges.value.isEmpty())

        // A later start on the same day does not re-publish an old change.
        f.manager.refresh()
        assertTrue(f.manager.lastChanges.value.isEmpty())
    }

    @Test
    fun `a rename or a reorder of the installed list never changes the outcome`() = runTest {
        val scores = mapOf(alpha to 1.0, beta to 10.0, gamma to 10.0, delta to 10.0, epsilon to 5.0)
        val first = fixture(scores = scores)
        val second = fixture(scores = scores, installed = installed.reversed().toSet())

        first.manager.refresh()
        second.manager.refresh()

        assertEquals(first.repository.layout.value, second.repository.layout.value)
        assertEquals(first.manager.lastChanges.value, second.manager.lastChanges.value)
    }

    @Test
    fun `the layer keeps four apps on A2 and never touches the anchors arc`() = runTest {
        val f = fixture(scores = mapOf(alpha to 1.0, epsilon to 5.0))

        f.manager.refresh()

        assertEquals(setOf(0, 1, 2, 3, 4), f.repository.layout.value.arcs.first { it.arc.id == "A2" }.slots.keys)
        assertTrue(f.repository.layout.value.arcs.first { it.arc.id == "A1" }.slots.isEmpty())
        assertEquals(DefaultUsageSlots, f.store.state.slots)
    }
}
