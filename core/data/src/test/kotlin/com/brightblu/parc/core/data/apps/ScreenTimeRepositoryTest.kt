package com.brightblu.parc.core.data.apps

import com.brightblu.parc.model.AppEntry
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T47 screen time: window maths, pure aggregation and the repository's fake-clock behaviour. */
class ScreenTimeRepositoryTest {

    private class FakeQuery(
        private val today: List<ForegroundBucket>?,
        private val week: List<ForegroundBucket>?,
        private val broken: Boolean = false,
    ) : ForegroundUsageQuery {
        val windows = ArrayList<Pair<Long, Long>>()

        override fun buckets(beginMillis: Long, endMillis: Long): List<ForegroundBucket>? {
            windows += beginMillis to endMillis
            if (broken) throw IllegalStateException("usage access refused")
            return if (windows.size == 1) today else week
        }
    }

    private fun repository(
        query: ForegroundUsageQuery,
        installed: List<AppEntry> = emptyList(),
        now: Long,
        zone: ZoneId = nairobi,
    ): AndroidScreenTimeRepository = AndroidScreenTimeRepository(
        query = query,
        installed = { installed },
        ownPackage = "com.brightblu.parc",
        clock = { now },
        zone = { zone },
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `today starts at local midnight and the week starts six days before`() = runTest {
        val now = epoch(2026, 10, 17, 9, 0, nairobi)
        val query = FakeQuery(emptyList(), emptyList())

        repository(query, now = now).read()

        assertEquals(epoch(2026, 10, 17, 0, 0, nairobi), query.windows[0].first)
        assertEquals(now + 1L, query.windows[0].second)
        assertEquals(epoch(2026, 10, 11, 0, 0, nairobi), query.windows[1].first)
        assertEquals(now + 1L, query.windows[1].second)
    }

    @Test
    fun `the seven-day window is calendar based across a DST fall back`() {
        val now = epoch(2026, 11, 2, 9, 0, newYork)
        val todayStart = ScreenTimeWindows.startOfToday(now, newYork)
        val windowStart = ScreenTimeWindows.windowStart(now, newYork)

        assertEquals(epoch(2026, 11, 2, 0, 0, newYork), todayStart)
        assertEquals(epoch(2026, 10, 27, 0, 0, newYork), windowStart)
        assertEquals(145L * HourMillis, todayStart - windowStart)
    }

    @Test
    fun `per-package aggregation sums days and ignores empty or outside buckets`() {
        val totals = ScreenTimeMath.perPackage(
            buckets = listOf(
                bucket("com.test.a", 1_000L, 2_000L, 100L),
                bucket("com.test.a", 2_000L, 3_000L, 50L),
                bucket("com.test.b", 4_000L, 5_000L, 0L),
                bucket("com.test.c", 0L, 1_000L, 999L),
                bucket("com.test.d", 5_000L, 6_000L, 999L),
                bucket("com.test.e", 100L, 200L, -5L),
            ),
            windowStartMillis = 1_000L,
            windowEndMillis = 5_000L,
        )

        assertEquals(mapOf("com.test.a" to 150L), totals)
    }

    @Test
    fun `a missing grant is Unavailable, not an empty list`() = runTest {
        val now = epoch(2026, 10, 17, 9, 0, nairobi)

        val result = repository(FakeQuery(today = null, week = emptyList()), now = now).read()

        assertEquals(ScreenTimeResult.Unavailable, result)
    }

    @Test
    fun `a refused week query makes the whole read Unavailable`() = runTest {
        val now = epoch(2026, 10, 17, 9, 0, nairobi)
        val query = FakeQuery(today = emptyList(), week = null)

        val result = repository(query, now = now).read()

        assertEquals(ScreenTimeResult.Unavailable, result)
        assertEquals(2, query.windows.size)
    }

    @Test
    fun `a throwing query is caught and reported as Unavailable`() = runTest {
        val now = epoch(2026, 10, 17, 9, 0, nairobi)

        val result = repository(FakeQuery(emptyList(), emptyList(), broken = true), now = now).read()

        assertEquals(ScreenTimeResult.Unavailable, result)
    }

    @Test
    fun `an empty answer is available with zero totals`() = runTest {
        val now = epoch(2026, 10, 17, 9, 0, nairobi)

        val result = repository(FakeQuery(emptyList(), emptyList()), now = now).read()

        assertTrue(result is ScreenTimeResult.Available)
        val available = result as ScreenTimeResult.Available
        assertTrue(available.entries.isEmpty())
        assertEquals(0L, available.todayTotalMillis)
        assertEquals(0L, available.last7DaysTotalMillis)
    }

    @Test
    fun `entries join launchable apps, exclude Parc and unknown packages, and sort by week`() = runTest {
        val now = epoch(2026, 10, 17, 9, 0, nairobi)
        val instagram = testEntry("instagram", label = "Instagram")
        val threads = testEntry("threads", label = "Threads")
        val parc = testEntry("parc", packageName = "com.brightblu.parc", label = "Parc")
        val query = FakeQuery(
            today = listOf(bucket("com.test.instagram", now - HourMillis, now, 50_000L)),
            week = listOf(
                bucket("com.test.instagram", now - DayMillis, now, 100_000L),
                bucket("com.test.threads", now - DayMillis, now, 300_000L),
                bucket("com.android.systemui", now - DayMillis, now, 900_000L),
                bucket("com.brightblu.parc", now - DayMillis, now, 800_000L),
            ),
        )

        val result = repository(query, listOf(instagram, threads, parc), now).read()
        val available = result as ScreenTimeResult.Available

        assertEquals(listOf("Threads", "Instagram"), available.entries.map { it.label })
        assertEquals(threads.id, available.entries[0].appId)
        assertEquals(50_000L, available.entries[1].todayMillis)
        assertEquals(100_000L, available.entries[1].last7DaysMillis)
        assertEquals(400_000L, available.last7DaysTotalMillis)
        assertEquals(50_000L, available.todayTotalMillis)
    }

    @Test
    fun `top lists cap at five and re-sort for their own window`() = runTest {
        val now = epoch(2026, 10, 17, 9, 0, nairobi)
        val apps = (1..7).map { index -> testEntry("app$index", label = "App$index") }
        val week = apps.mapIndexed { index, app ->
            bucket(app.packageName, now - DayMillis, now, (index + 1) * 1_000L)
        }
        val today = apps.mapIndexed { index, app ->
            bucket(app.packageName, now - HourMillis, now, (7 - index) * 100L)
        }

        val result = repository(FakeQuery(today, week), apps, now).read()
            as ScreenTimeResult.Available

        assertEquals(
            listOf("App7", "App6", "App5", "App4", "App3"),
            result.topLast7Days().map { it.label },
        )
        assertEquals(
            listOf("App1", "App2", "App3", "App4", "App5"),
            result.topToday().map { it.label },
        )
        assertEquals(1, result.topLast7Days(limit = 1).size)
        assertTrue(result.topToday(limit = 0).isEmpty())
    }

    @Test
    fun `a week total smaller than today is clamped so the window includes today`() = runTest {
        val now = epoch(2026, 10, 17, 9, 0, nairobi)
        val app = testEntry("a")
        val query = FakeQuery(
            today = listOf(bucket(app.packageName, now - HourMillis, now, 20_000L)),
            week = listOf(bucket(app.packageName, now - DayMillis, now, 10_000L)),
        )

        val result = repository(query, listOf(app), now).read() as ScreenTimeResult.Available

        assertEquals(20_000L, result.entries.single().last7DaysMillis)
    }

    @Test
    fun `today survives a partial week answer`() = runTest {
        val now = epoch(2026, 10, 17, 9, 0, nairobi)
        val app = testEntry("a")
        val query = FakeQuery(
            today = listOf(bucket(app.packageName, now - HourMillis, now, 20_000L)),
            week = emptyList(),
        )

        val result = repository(query, listOf(app), now).read() as ScreenTimeResult.Available

        assertEquals(20_000L, result.entries.single().last7DaysMillis)
        assertEquals(20_000L, result.todayTotalMillis)
    }

    @Test
    fun `the smallest activity id wins when a package has several launchables`() = runTest {
        val now = epoch(2026, 10, 17, 9, 0, nairobi)
        val secondary = testEntry("a", label = "Secondary", activity = "Zed")
        val primary = testEntry("a", label = "Primary", activity = "Alpha")
        val query = FakeQuery(
            today = emptyList(),
            week = listOf(bucket("com.test.a", now - DayMillis, now, 5_000L)),
        )

        val result = repository(query, listOf(secondary, primary), now).read()
            as ScreenTimeResult.Available

        assertEquals(primary.id, result.entries.single().appId)
        assertEquals("Primary", result.entries.single().label)
    }
}
