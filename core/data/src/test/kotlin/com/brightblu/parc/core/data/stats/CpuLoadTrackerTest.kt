package com.brightblu.parc.core.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The T58 `/proc/stat` pair tracker: one baseline, then deltas, and no value without both. */
class CpuLoadTrackerTest {

    @Test
    fun `the first reading is a baseline and the second gives the delta`() {
        val tracker = CpuLoadTracker()

        assertNull(tracker.update(ProcStatTicks(total = 1_000L, idle = 800L)))
        assertEquals(50f, tracker.update(ProcStatTicks(total = 1_100L, idle = 850L))!!, 0.001f)
    }

    @Test
    fun `a missing reading drops the baseline and publishes nothing`() {
        val tracker = CpuLoadTracker()

        tracker.update(ProcStatTicks(total = 1_000L, idle = 800L))
        assertNull(tracker.update(null))
        assertNull(tracker.update(ProcStatTicks(total = 1_100L, idle = 850L)))
    }

    @Test
    fun `reset forgets the baseline`() {
        val tracker = CpuLoadTracker()

        tracker.update(ProcStatTicks(total = 1_000L, idle = 800L))
        tracker.reset()

        assertNull(tracker.update(ProcStatTicks(total = 1_100L, idle = 850L)))
        assertEquals(50f, tracker.update(ProcStatTicks(total = 1_200L, idle = 900L))!!, 0.001f)
    }

    @Test
    fun `a stalled counter is null but becomes the new baseline`() {
        val tracker = CpuLoadTracker()
        val ticks = ProcStatTicks(total = 1_000L, idle = 800L)

        tracker.update(ticks)
        assertNull(tracker.update(ticks))
        assertEquals(20f, tracker.update(ProcStatTicks(total = 1_050L, idle = 840L))!!, 0.001f)
    }
}
