package com.brightblu.parc.ring.module

import com.brightblu.parc.model.ArcFeel
import com.brightblu.parc.model.Bounce
import com.brightblu.parc.model.SpinLimit
import com.brightblu.parc.ring.ArcMotion
import com.brightblu.parc.ring.ArcPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/** T148: random gesture streams through the module state always land on a whole slot inside the Spin limit. */
class ArcGestureReducerTest {
    private val frame = 1f / 60f

    private fun runStream(seed: Long, spin: SpinLimit, bounce: Bounce) {
        val ticks = ArrayList<HubOutput>()
        val module = HubModuleState(onOutput = { ticks += it })
        module.dispatch(HubEvent.ArcFeelChanged(ArcFeel(spinLimit = spin, bounce = bounce)))
        val random = Random(seed)
        var offset = random.nextInt(21) - 10 + 0f
        repeat(6) { gesture ->
            module.dispatch(HubEvent.ArcDown("a1", module.ui.arcMotion.takeIf {
                it.phase == ArcPhase.Settling }?.offset ?: offset))
            val start = module.ui.arcMotion.startSlot
            repeat(random.nextInt(40)) {
                val magnitude = random.nextFloat() * (if (random.nextInt(8) == 0) 6f else 0.6f)
                module.dispatch(HubEvent.ArcDrag(if (random.nextBoolean()) magnitude else -magnitude))
            }
            when (random.nextInt(4)) {
                0 -> module.dispatch(HubEvent.Cancel)
                else -> module.dispatch(HubEvent.ArcRelease((random.nextFloat() - 0.5f) * 500f, 1f))
            }
            val settling = module.ui.arcMotion
            if (settling.phase == ArcPhase.Settling || settling.phase == ArcPhase.Rest) {
                assertTrue("target ${settling.target} start $start limit ${spin.slots}",
                    abs(settling.target - start) <= spin.slots)
            }
            // Sometimes let it finish, sometimes catch it mid settle with the next gesture.
            val frames = if (gesture < 5 && random.nextBoolean()) random.nextInt(12) else 600
            var n = 0
            while (module.ui.arcMotion.phase == ArcPhase.Settling && n < frames) {
                module.dispatch(HubEvent.ArcFrame(frame)); n++
            }
            offset = module.ui.arcMotion.offset
            if (module.ui.arcMotion.phase == ArcPhase.Rest) {
                assertEquals("integer offset, seed $seed", Math.rint(offset.toDouble()).toFloat(), offset, 0f)
                assertTrue("travel from $start to $offset (limit ${spin.slots}) seed $seed",
                    abs(offset - start) <= spin.slots)
            } else {
                // Mid settle: the offset is on its way, but its target is always a whole slot inside the limit.
                assertTrue(abs(module.ui.arcMotion.target - module.ui.arcMotion.startSlot) <= spin.slots)
            }
        }
        // Whatever is left is settled to a whole slot.
        while (module.ui.arcMotion.phase == ArcPhase.Settling) module.dispatch(HubEvent.ArcFrame(frame))
        val end = module.ui.arcMotion.offset
        assertEquals(Math.rint(end.toDouble()).toFloat(), end, 0f)
        assertEquals(end, module.ui.arcOffsets["a1"]!!)
    }

    @Test fun randomGesturesEndOnAWholeSlotInsideTheSpinLimit() {
        for (spin in SpinLimit.entries) for (bounce in Bounce.entries) for (seed in 1L..150L) runStream(seed, spin, bounce)
    }

    @Test fun aMaximumFlickWithAMaximumDragStaysInsideTheLimitFromTouchDown() {
        for (spin in SpinLimit.entries) {
            val module = HubModuleState()
            module.dispatch(HubEvent.ArcFeelChanged(ArcFeel(spinLimit = spin)))
            module.dispatch(HubEvent.ArcDown("a1", 0f))
            module.dispatch(HubEvent.ArcDrag(7f))
            module.dispatch(HubEvent.ArcRelease(1e6f))
            while (module.ui.arcMotion.phase == ArcPhase.Settling) module.dispatch(HubEvent.ArcFrame(frame))
            assertEquals(spin.slots.toFloat(), module.ui.arcMotion.offset, 0f)
        }
    }

    @Test fun jumpTargetWhenTheAnimatorScaleIsZero() {
        val module = HubModuleState()
        module.dispatch(HubEvent.ArcDown("a1", 0f))
        module.dispatch(HubEvent.ArcDrag(1.4f))
        module.dispatch(HubEvent.ArcRelease(0f, 0f))
        assertEquals(ArcPhase.Rest, module.ui.arcMotion.phase)
        assertEquals(1f, module.ui.arcMotion.offset, 0f)
    }

    @Test fun slotsPassingTheFrontTickDuringTheDrag() {
        val outs = ArrayList<HubOutput>()
        val module = HubModuleState(onOutput = { outs += it })
        module.dispatch(HubEvent.ArcDown("a1", 0f))
        repeat(4) { module.dispatch(HubEvent.ArcDrag(0.6f)) }
        assertEquals(listOf(HubOutput.Tick, HubOutput.Tick), outs) // 0.6 -> 1, 1.2 same, 1.8 -> 2, 2.4 same
        assertEquals(2, ArcMotion.slotOf(module.ui.arcMotion.offset))
    }
}
