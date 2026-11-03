package com.brightblu.parc.ring

import com.brightblu.parc.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class RingScenesTest {
    private val layout = RingLayout(384f, 853f)
    private fun apps(count: Int) = List(count) { RingSlot(SlotPosition("source", it), Slot.App(AppId("app/$it")), "App $it") }
    @Test fun folderUsesTwoWindowsAndKeepsAllItemsInOrder() {
        for (count in 0..20) {
            val ranked = apps(count)
            val state = RingScenes.folder(layout, Folder(FolderId("social"), "Social"), ranked)
            assertEquals(listOf(3, 5), state.arcs.map { it.slotCapacity })
            assertEquals(ranked.map { it.slot }, state.slots.map { it.slot })
            assertEquals(minOf(3, count), state.slots.count { it.position.arcId.endsWith("A1") })
            val outer = state.slots.filter { it.position.arcId.endsWith("A2") }
            assertEquals((0 until outer.size).toList(), outer.map { it.position.index })
            assertEquals(count > 8, RingRotation.rotates(outer.size, 5))
        }
    }

    // OPUS1: the All-apps view is one ring of every app on the outermost arc.
    @Test fun allAppsIsOneRingOfEveryAppInOrderOnTheOuterArc() {
        val ordered = apps(60)
        val state = RingScenes.allApps(layout, ordered)
        assertEquals(Layer.AllApps, state.layer)
        assertEquals(1, state.arcs.size)
        val ring = state.arcs.single()
        assertEquals(RingScenes.allAppsRingId(layout.arcs.lastIndex), ring.id)
        assertEquals(layout.arcs.last().radiusDp, ring.radiusDp, 0f)
        assertEquals(ordered.map { it.slot }, state.slots.map { it.slot })
        assertEquals((0 until 60).toList(), state.slots.map { it.position.index })
        assertTrue(state.slots.all { it.position.arcId == ring.id })
        assertTrue(RingRotation.rotates(state.slots.size, ring.slotCapacity))
        // Index zero (the first app, A) sits at the top end of the ring.
        assertTrue(sin(Math.toRadians(ring.slotAngle(0).toDouble())) <=
            sin(Math.toRadians(ring.slotAngle(ring.slotCapacity - 1).toDouble())))
        val mirrored = RingScenes.allApps(RingLayout(384f, 853f, options = RingLayoutOptions(mirrored = true)), ordered)
        assertEquals(ordered.map { it.slot }, mirrored.slots.map { it.slot })
        assertEquals(60, state.slots.map { it.slot }.distinct().size)
    }

    @Test fun allAppsRingFollowsArcsShownAndDoesNotTurnWhenEverythingFits() {
        val three = RingLayout(384f, 853f, options = RingLayoutOptions(arcsShown = 3))
        val state = RingScenes.allApps(three, apps(3))
        assertEquals(RingScenes.allAppsRingId(2), state.arcs.single().id)
        assertFalse(RingRotation.rotates(state.slots.size, state.arcs.single().slotCapacity))
    }

    @Test fun allAppsIsTotalForAnEmptyList() {
        val empty = RingScenes.allApps(layout, emptyList())
        assertEquals(Layer.AllApps, empty.layer)
        assertTrue(empty.slots.isEmpty())
        assertEquals(1, empty.arcs.size)
        assertEquals("No apps to show", empty.emptyMessage)
        assertTrue(RingScenes.allApps(RingLayout(384f, 853f, options = RingLayoutOptions(arcsShown = 3)), emptyList()).slots.isEmpty())
    }

    // OPUS2: one preparation at a time; a burst of states never piles up parallel preparations.
    @Test fun aBurstOfStatesRunsOnePreparationAtATimeAndEndsOnTheNewest() {
        val queue = ScenePrepareQueue<Int>()
        assertTrue("the first request starts the worker", queue.offer(1))
        assertEquals(1, queue.take())
        // While 1 is being prepared, 200 more states arrive (one per loaded icon on a big phone).
        for (i in 2..201) assertFalse("no second worker", queue.offer(i))
        assertEquals("only the newest waiting state is prepared next", 201, queue.take())
        assertNull("then the worker stops", queue.take())
        assertFalse(queue.busy)
        assertTrue("a later state starts a worker again", queue.offer(202))
    }

    @Test fun everyPreparationFinishesAndPublishes() {
        // Simulated worker: each take is prepared and published, so the screen always moves forward.
        val queue = ScenePrepareQueue<Int>()
        val published = mutableListOf<Int>()
        var next = 0
        queue.offer(++next)
        while (true) {
            val request = queue.take() ?: break
            if (next < 5) queue.offer(++next)
            published += request
        }
        assertEquals(listOf(1, 2, 3, 4, 5), published)
    }

    // OPUS3: the All-apps warm-up never delays or replaces a real scene.
    @Test fun aWarmUpRunsOnlyWhenNoRealSceneIsWaiting() {
        val queue = ScenePrepareQueue<String>()
        assertTrue(queue.offerWarm("all-apps warm"))
        assertEquals("all-apps warm" to true, queue.takeNext())
        // While the warm-up runs, Home changes and a newer warm-up arrives.
        assertFalse(queue.offer("home"))
        assertFalse(queue.offerWarm("all-apps warm 2"))
        assertEquals("the real scene goes first", "home" to false, queue.takeNext())
        assertEquals("all-apps warm 2" to true, queue.takeNext())
        assertNull(queue.takeNext())
        assertFalse(queue.busy)
    }

    @Test fun aWarmedRingStandsInOnlyForTheSameLayerWhenAnotherIsShown() {
        val warmed = RingScenes.allApps(layout, apps(30))
        val requested = RingScenes.allApps(layout, apps(30))
        val home = RingScenes.home(layout, emptyList())
        assertTrue(WarmStandIn.usable(warmed, requested, home, sameSkin = true, sameLook = true))
        assertFalse("already showing the ring", WarmStandIn.usable(warmed, requested, requested, true, true))
        assertFalse("skin changed", WarmStandIn.usable(warmed, requested, home, false, true))
        assertFalse("look changed", WarmStandIn.usable(warmed, requested, home, true, false))
        assertFalse("Home was asked for", WarmStandIn.usable(warmed, home, requested, true, true))
        assertFalse("search covers the ring", WarmStandIn.usable(warmed, requested.copy(searchOpen = true), home, true, true))
    }

    @Test fun rasterFamiliesKeepHomeAndAllAppsApart() {
        var made = 0
        val families = RasterFamilies<String, Int>(3) { ++made }
        val home = families.get("home")
        val all = families.get("all")
        assertEquals(home, families.get("home"))
        assertEquals(all, families.get("all"))
        families.get("folder a"); families.get("folder b")
        assertEquals("the least recently used family is dropped", setOf("all", "folder a", "folder b"), families.keys())
        assertEquals(4, made)
    }

    @Test fun slotRastersAreReusedAndOnlyOneSceneIsKept() {
        val reuse = RasterReuse<String, Int>()
        var made = 0
        reuse.begin("midnight")
        List(200) { "app$it" }.forEach { reuse.get(it) { ++made } }
        reuse.end()
        assertEquals(200, made)
        // A rebuilt scene with the same apps makes nothing new.
        reuse.begin("midnight")
        List(200) { "app$it" }.forEach { reuse.get(it) { ++made } }
        reuse.end()
        assertEquals(200, made)
        // A smaller scene releases what it no longer shows.
        reuse.begin("midnight")
        List(7) { "app$it" }.forEach { reuse.get(it) { ++made } }
        reuse.end()
        assertEquals(7, reuse.size)
        // A new look starts over.
        reuse.begin("dusk")
        reuse.get("app0") { ++made }
        reuse.end()
        assertEquals(201, made)
        assertEquals(1, reuse.size)
    }

    @Test fun recentsRemovePinsAndDuplicatesAndHaveTwoArcs() {
        val ranked = apps(10)
        val state = RingScenes.recents(layout, ranked + ranked, setOf(AppId("app/0")))
        assertEquals(2, state.arcs.size)
        assertEquals(9, state.slots.size)
        assertEquals(ranked.drop(1).map { it.slot }, state.slots.map { it.slot })
        assertEquals("Nothing yet", RingScenes.recents(layout, emptyList(), emptySet()).emptyMessage)
    }
    @Test fun emptyRecentsWarmUpAndStaleFocusHaveNoActiveArc() {
        val empty = RingScenes.recents(layout, emptyList(), emptySet())
        assertTrue(empty.slots.isEmpty())
        assertEquals("Nothing yet", empty.emptyMessage)
        val indices = IntArray(empty.slots.size)
        assertEquals(-1, focusedArc(indices, 0)) // SceneDrawing's warm-up focus
        assertEquals(-1, focusedArc(indices, -1))
        assertEquals(-1, focusedArc(intArrayOf(2), 1))
        assertEquals(2, focusedArc(intArrayOf(2), 0))
    }
    @Test fun snapEndpointsAndSequentialFadesStayValid() {
        assertFalse(RingSnap.endpoint(0.499f)); assertTrue(RingSnap.endpoint(0.5f))
        assertEquals(1f, RingSnap.outgoingAlpha(0f), 0f)
        assertEquals(1f, RingSnap.incomingAlpha(1f), 0f)
        assertEquals(0f, RingSnap.outgoingAlpha(0.5f), 0f)
        for (step in 0..100) {
            val p = step / 100f
            assertTrue(RingSnap.outgoingAlpha(p) in 0f..1f)
            assertTrue(RingSnap.incomingAlpha(p) in 0f..1f)
        }
    }
    @Test fun fineSpokesPreserveMinimumPitchAndReportCapacityTradeoff() {
        val fine = RingLayout(384f, 853f, options = RingLayoutOptions(fineSpokes = true))
        assertEquals(21, fine.count)
        for (arc in fine.arcs) for (i in 1 until arc.slotCapacity) {
            assertTrue(abs(arc.slotAngle(i) - arc.slotAngle(i-1)) * PI / 180 * arc.radiusDp >= Metrics.minSlotPitchDp - 0.001)
        }
        assertEquals(23, layout.count)
    }

    // OPUS4: the letter strip's jump target.
    @Test fun nearestOffsetBringsAnItemToTheFrontTheShortWay() {
        assertEquals(10f, RingRotation.nearestOffsetFor(10, 8f, 100), 0f)
        assertEquals("back across the wrap", -2f, RingRotation.nearestOffsetFor(98, 0f, 100), 0f)
        assertEquals(98, RingRotation.frontItem(-2f, 100))
        assertEquals("unwrapped offsets keep turning", 205f, RingRotation.nearestOffsetFor(5, 203.4f, 100), 0f)
        assertEquals(0f, RingRotation.nearestOffsetFor(3, 0f, 0), 0f)
        assertEquals(-1, RingRotation.frontItem(0f, 0))
    }
}
