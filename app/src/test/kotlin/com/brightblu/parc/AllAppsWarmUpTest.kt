package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPUS3: the background warm-up stays gentle. */
class AllAppsWarmUpTest {
    @Test fun iconsAreRequestedInSmallPacedChunks() {
        assertEquals(0L, AllAppsWarmUp.pacingMs(0))
        assertEquals(AllAppsWarmUp.ChunkPauseMs, AllAppsWarmUp.pacingMs(1))
        assertEquals(12 * AllAppsWarmUp.ChunkPauseMs, AllAppsWarmUp.pacingMs(180))
        assertTrue("180 apps are paced over under two seconds", AllAppsWarmUp.pacingMs(180) < 2_000L)
        assertTrue(AllAppsWarmUp.ChunkSize in 4..32)
    }

    @Test fun warmingWaitsForHomeAndForQuiet() {
        assertTrue(AllAppsWarmUp.StartDelayMs >= 1_000L)
        assertTrue(AllAppsWarmUp.QuietMs >= 300L)
    }
}
