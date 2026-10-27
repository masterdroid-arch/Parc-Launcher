package com.brightblu.parc

import org.junit.Assert.*
import org.junit.Test

class SearchSwipeDetectorTest {
    private val detector = SearchSwipeDetector()
    private fun start(blocked: Boolean = false, onControl: Boolean = false, y: Float = 100f) =
        detector.start(100f, y, 0L, blocked, onControl)

    @Test fun slowDownwardDragRequires110DpAndFiresOnlyOnce() {
        start()
        assertFalse(detector.move(100f, 209f, 2_000))
        assertTrue(detector.move(100f, 210f, 3_000))
        assertFalse(detector.move(100f, 250f, 3_100))
        assertFalse(detector.move(100f, 300f, 3_200, released = true))
    }

    @Test fun downwardFlingRequires60DpAndStrictlyMoreThan1200DpPerSecond() {
        start()
        assertFalse(detector.move(100f, 159f, 20, released = true))
        start()
        assertFalse(detector.move(100f, 160f, 50, released = true))
        start()
        assertFalse(detector.move(100f, 160f, 40)) // A short drag is not a fling until UP.
        assertTrue(detector.move(100f, 160f, 49, released = true))
        start()
        assertFalse(detector.move(100f, 160f, 1_000, released = true))
    }

    @Test fun diagonalDriftNeedsStrictOnePointFiveDominanceForDragAndFling() {
        start()
        assertFalse(detector.move(174f, 210f, 1_000))
        assertTrue(detector.move(173f, 210f, 1_100))
        start()
        assertFalse(detector.move(140f, 160f, 30, released = true))
        start()
        assertTrue(detector.move(120f, 160f, 40, released = true))
    }

    @Test fun upwardHorizontalTapAndInvalidSamplesNeverOpen() {
        for ((x, y) in listOf(100f to 20f, 200f to 110f, 100f to 100f,
                Float.NaN to 200f, 100f to Float.POSITIVE_INFINITY)) {
            start()
            assertFalse(detector.move(x, y, 20, released = true))
        }
    }

    @Test fun topKeepoutAndControlsDisqualifyTheWholeGesture() {
        start(y = 56f)
        assertFalse(detector.move(100f, 300f, 100))
        start(y = 55f)
        assertFalse(detector.move(100f, 300f, 100))
        // Slot, rotating arc, hub and search button are classified by the live renderer.
        repeat(4) {
            start(onControl = true)
            assertFalse(detector.move(100f, 300f, 100))
            assertFalse(detector.move(100f, 340f, 120, released = true))
        }
        start(y = 57f)
        assertTrue(detector.move(100f, 167f, 1_000))
    }

    @Test fun blockedStateAndCancellationCannotBecomeSearchMidGesture() {
        start(blocked = true) // Edit, menu, folder, overlay, search or paused host.
        assertFalse(detector.move(100f, 300f, 100))
        start()
        detector.cancel() // Includes multi-touch and a newly opened overlay.
        assertFalse(detector.move(100f, 300f, 100))
    }

    @Test fun repeatedSwipesResetAndReleaseEndsRejectedGesture() {
        repeat(3) {
            start()
            assertTrue(detector.move(100f, 210f, 1_000))
            assertFalse(detector.move(100f, 200f, 1_100, released = true))
        }
        start()
        assertFalse(detector.move(100f, 110f, 1_000, released = true))
        assertFalse(detector.move(100f, 200f, 1_100))
    }
}
