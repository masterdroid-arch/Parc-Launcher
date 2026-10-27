package com.brightblu.parc.core.data.apps

import com.brightblu.parc.model.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T47 last app: source merging, Parc/unknown filtering and the tracker's fake clock. */
class LastAppTrackerTest {

    private class FakeEvents(var result: List<ForegroundMark>? = emptyList()) : ForegroundEventQuery {
        val windows = ArrayList<Pair<Long, Long>>()

        override fun events(sinceMillis: Long, untilMillis: Long): List<ForegroundMark>? {
            windows += sinceMillis to untilMillis
            return result
        }
    }

    private val ownPackage = "com.brightblu.parc"
    private val a = testEntry("a", label = "Alpha")
    private val b = testEntry("b", label = "Bravo")
    private val parc = testEntry("parc", packageName = ownPackage, label = "Parc")
    private val installed = listOf(a, b, parc)

    private fun tracker(
        installedApps: List<AppEntry> = installed,
        now: () -> Long,
        log: suspend () -> List<ForegroundMark> = { emptyList() },
        events: ForegroundEventQuery = FakeEvents(),
    ): AndroidLastAppTracker = AndroidLastAppTracker(
        recentLaunches = log,
        foregroundEvents = events,
        installed = { installedApps },
        ownPackage = ownPackage,
        clock = now,
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `the resolver returns the newest launchable app`() {
        val last = LastAppResolver.resolve(
            marks = listOf(ForegroundMark(b.packageName, 200L), ForegroundMark(a.packageName, 100L)),
            appsByPackage = AppPackages.byPackage(installed),
            ownPackage = ownPackage,
        )

        assertEquals(b.id, last?.appId)
        assertEquals("Bravo", last?.label)
        assertEquals(200L, last?.foregroundAtMillis)
    }

    @Test
    fun `Parc is never the last app`() {
        val last = LastAppResolver.resolve(
            marks = listOf(
                ForegroundMark(ownPackage, 300L),
                ForegroundMark(a.packageName, 200L),
            ),
            appsByPackage = AppPackages.byPackage(installed),
            ownPackage = ownPackage,
        )

        assertEquals(a.id, last?.appId)
    }

    @Test
    fun `a package with no launchable app is skipped`() {
        val last = LastAppResolver.resolve(
            marks = listOf(
                ForegroundMark("com.android.systemui", 300L),
                ForegroundMark(a.packageName, 200L),
            ),
            appsByPackage = AppPackages.byPackage(installed),
            ownPackage = ownPackage,
        )

        assertEquals(a.id, last?.appId)
    }

    @Test
    fun `nothing known means no last app`() {
        assertNull(
            LastAppResolver.resolve(
                marks = listOf(ForegroundMark(ownPackage, 300L)),
                appsByPackage = AppPackages.byPackage(installed),
                ownPackage = ownPackage,
            ),
        )
        assertNull(LastAppResolver.resolve(emptyList(), emptyMap(), ownPackage))
    }

    @Test
    fun `a Parc launch and its platform event collapse to one mark`() {
        val merged = LastAppMarks.merge(
            newMarks = listOf(
                ForegroundMark(b.packageName, 1_000L),
                ForegroundMark(b.packageName, 1_001L),
            ),
            existing = listOf(ForegroundMark(b.packageName, 1_000L)),
            limit = 10,
        )

        assertEquals(listOf(ForegroundMark(b.packageName, 1_001L)), merged)
    }

    @Test
    fun `two uses of an app separated by another app are both kept`() {
        val merged = LastAppMarks.merge(
            newMarks = listOf(
                ForegroundMark(b.packageName, 3_000L),
                ForegroundMark(a.packageName, 2_000L),
                ForegroundMark(b.packageName, 1_000L),
            ),
            existing = emptyList(),
            limit = 10,
        )

        assertEquals(
            listOf(b.packageName, a.packageName, b.packageName),
            merged.map { it.packageName },
        )
    }

    @Test
    fun `the mark list is capped`() {
        val marks = (1..10).map { index ->
            ForegroundMark(if (index % 2 == 0) a.packageName else b.packageName, index.toLong())
        }

        assertEquals(3, LastAppMarks.merge(marks, emptyList(), limit = 3).size)
        assertEquals(emptyList<ForegroundMark>(), LastAppMarks.merge(marks, emptyList(), limit = 0))
    }

    @Test
    fun `onLaunched updates the flow immediately and without a refresh`() {
        var now = 1_000L
        val tracker = tracker(now = { now })

        tracker.onLaunched(b.id)
        assertEquals(b.id, tracker.lastApp.value?.appId)
        assertEquals(1_000L, tracker.lastApp.value?.foregroundAtMillis)

        now = 2_000L
        tracker.onLaunched(a.id)
        assertEquals(a.id, tracker.lastApp.value?.appId)
    }

    @Test
    fun `refresh merges the Room log and the platform events by time`() = runTest {
        val events = FakeEvents(listOf(ForegroundMark(b.packageName, 90_000L)))
        val tracker = tracker(
            now = { 100_000L },
            log = { listOf(ForegroundMark(a.packageName, 50_000L)) },
            events = events,
        )

        tracker.refresh()

        assertEquals(b.id, tracker.lastApp.value?.appId)
        assertEquals(90_000L, tracker.lastApp.value?.foregroundAtMillis)
        assertEquals(100_000L - AndroidLastAppTracker.LookbackMillis, events.windows.single().first)
        assertEquals(100_001L, events.windows.single().second)
    }

    @Test
    fun `without usage access the Parc log still supplies the last app`() = runTest {
        val tracker = tracker(
            now = { 100_000L },
            log = { listOf(ForegroundMark(a.packageName, 50_000L)) },
            events = FakeEvents(result = null),
        )

        tracker.refresh()

        assertEquals(a.id, tracker.lastApp.value?.appId)
    }

    @Test
    fun `the event window advances only while events are readable`() = runTest {
        var now = 100_000L
        val events = FakeEvents(result = null)
        val tracker = tracker(now = { now }, events = events)

        tracker.refresh()
        assertEquals(100_000L - AndroidLastAppTracker.LookbackMillis, events.windows.last().first)

        now = 200_000L
        events.result = emptyList()
        tracker.refresh()
        assertEquals(200_000L - AndroidLastAppTracker.LookbackMillis, events.windows.last().first)

        now = 300_000L
        tracker.refresh()
        assertEquals(200_000L, events.windows.last().first)
    }

    @Test
    fun `a failed log read does not discard the event answer`() = runTest {
        val tracker = tracker(
            now = { 100_000L },
            log = { throw IllegalStateException("database unavailable") },
            events = FakeEvents(listOf(ForegroundMark(a.packageName, 90_000L))),
        )

        tracker.refresh()

        assertEquals(a.id, tracker.lastApp.value?.appId)
    }

    @Test
    fun `refresh with no sources leaves the value null`() = runTest {
        val tracker = tracker(now = { 100_000L }, events = FakeEvents(result = null))

        tracker.refresh()

        assertNull(tracker.lastApp.value)
    }

    @Test
    fun `an old watermark is capped to the configured lookback`() = runTest {
        var now = 100L * DayMillis
        val events = FakeEvents(result = emptyList())
        val tracker = tracker(now = { now }, events = events)

        tracker.refresh()
        assertEquals(now - AndroidLastAppTracker.LookbackMillis, events.windows.last().first)

        now = 1_000L * DayMillis
        tracker.refresh()

        assertEquals(
            now - AndroidLastAppTracker.MaxLookbackMillis,
            events.windows.last().first,
        )
    }
}
