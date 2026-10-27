package com.brightblu.parc.ring

import com.brightblu.parc.model.Metrics
import org.junit.Assert.*
import org.junit.Test

class FolderTransitionTest {
    @Test fun staggerEndpointsAndOvershoot() {
        for (i in 0..30) {
            assertEquals(0f, FolderTransition.slot(0f, i), 0f)
            assertEquals(1f, FolderTransition.slot(1f, i), 0f)
        }
        assertTrue(FolderTransition.slot(0.1f, 0) > 0f)
        assertEquals(0f, FolderTransition.slot(17f / Metrics.folderTransitionMs, 1), 0f)
        assertTrue(FolderTransition.slot(19f / Metrics.folderTransitionMs, 1) > 0f)
        assertTrue((0..100).any { FolderTransition.slot(it / 100f, 0) > 1f })
        assertEquals(FolderTransition.slot(0.6f, 7), FolderTransition.slot(0.6f, 30), 0f)
        assertEquals(0f, FolderTransition.emphasis(0f), 0f)
        assertEquals(1f, FolderTransition.emphasis(1f), 0f)
    }
    @Test fun backHalfPlaneRequiresReachAndEitherFreeDirection() {
        assertFalse(joystickBack(10f, 10f))
        assertFalse(joystickBack(-100f, -100f))
        assertFalse(joystickBack(0f, -30f))
        assertFalse(joystickBack(-30f, 0f))
        assertTrue(joystickBack(25f, -10f))
        assertTrue(joystickBack(-10f, 25f))
        assertTrue(joystickBack(0f, 20f))
        assertTrue(joystickBack(20f, 0f))
        assertFalse(joystickBack(Float.NaN, 30f))
    }
}
