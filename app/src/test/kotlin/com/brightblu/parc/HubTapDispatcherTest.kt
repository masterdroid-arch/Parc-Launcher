package com.brightblu.parc

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HubTapDispatcherTest {
    @Test fun defaultOnDefersSingleTapOnlyFor280Millis() = runTest {
        var singles = 0
        val taps = HubTapDispatcher(this, clock = { testScheduler.currentTime }, singleTap = { singles++ }, doubleTap = { fail() })
        taps.tap()
        assertEquals(0, singles)
        advanceTimeBy(279); runCurrent()
        assertEquals(0, singles)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, singles)
    }

    @Test fun secondTapInsideWindowLaunchesOnceWithoutToggling() = runTest {
        for (gap in listOf(0L, 120L, 279L, 280L)) {
            var singles = 0; var doubles = 0
            var now = 0L
            val taps = HubTapDispatcher(this, clock = { now }, singleTap = { singles++ }, doubleTap = { doubles++ })
            taps.tap(); now = gap; taps.tap()
            advanceUntilIdle()
            assertEquals(0, singles); assertEquals(1, doubles)
        }
    }

    @Test fun lateSecondTapIsAnotherSingle() = runTest {
        var singles = 0
        val taps = HubTapDispatcher(this, clock = { testScheduler.currentTime }, singleTap = { singles++ }, doubleTap = { fail() })
        taps.tap(); advanceTimeBy(281); runCurrent()
        assertEquals(1, singles)
        taps.tap(); advanceUntilIdle()
        assertEquals(2, singles)
    }

    @Test fun disabledSettingHasNoDelayAndNoDoubleTap() = runTest {
        var singles = 0
        val taps = HubTapDispatcher(this, false, { testScheduler.currentTime }, { singles++ }, { fail() })
        taps.tap(); taps.tap()
        assertEquals(2, singles)
        advanceUntilIdle(); assertEquals(2, singles)
    }

    @Test fun turningOffFlushesPendingSingleAndTurningOnRestoresDelay() = runTest {
        var singles = 0; var doubles = 0
        val taps = HubTapDispatcher(this, clock = { testScheduler.currentTime }, singleTap = { singles++ }, doubleTap = { doubles++ })
        taps.tap(); taps.setEnabled(false)
        assertEquals(1, singles)
        taps.tap(); assertEquals(2, singles)
        taps.setEnabled(true); taps.tap(); assertEquals(2, singles)
        taps.tap(); advanceUntilIdle()
        assertEquals(2, singles); assertEquals(1, doubles)
    }

    @Test fun pauseOrNavigationCancellationCannotToggleLater() = runTest {
        var singles = 0
        val taps = HubTapDispatcher(this, clock = { testScheduler.currentTime }, singleTap = { singles++ }, doubleTap = { fail() })
        taps.tap(); taps.cancel(); advanceUntilIdle()
        assertEquals(0, singles)
        taps.tap(); advanceUntilIdle(); assertEquals(1, singles)
    }

}
