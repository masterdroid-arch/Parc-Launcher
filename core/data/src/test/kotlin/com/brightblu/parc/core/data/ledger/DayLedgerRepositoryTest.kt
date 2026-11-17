package com.brightblu.parc.core.data.ledger

import com.brightblu.parc.core.data.power.ScreenEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T185 refresh behaviour on a fake Android and an in-memory store: first-run backfill, the midnight
 * date change, a time zone change, a reboot (a fresh repository over the same store), and a backup
 * restore. The clock and the zone are plain variables.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DayLedgerRepositoryTest {

    private class MemoryStore(var text: String? = null) : LedgerStore {
        var writes = 0
        override suspend fun read() = text
        override suspend fun write(text: String) { this.text = text; writes++ }
    }

    private class FakeSource(var zone: ZoneId) : LedgerSource {
        var granted = true
        val net = HashMap<LocalDate, WindowNetwork>()
        val fg = HashMap<LocalDate, Map<String, Long>>()
        val events = ArrayList<ScreenEvent>()
        val networkWindows = ArrayList<Pair<Long, Long>>()
        override fun accessGranted() = granted
        private fun dateOf(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        override fun network(startMillis: Long, endMillis: Long): WindowNetwork? {
            networkWindows += startMillis to endMillis
            return if (granted) net[dateOf(startMillis)] else null
        }
        override fun screenEvents(fromMillis: Long, toMillis: Long) =
            if (granted) events.filter { it.atMillis in fromMillis..toMillis }.sortedBy { it.atMillis } else null
        override fun foreground(startMillis: Long, endMillis: Long) =
            if (granted) (fg[dateOf(startMillis)] ?: emptyMap()) else null
    }

    private fun utc(date: String, time: String = "12:00") =
        Instant.parse("${date}T$time:00Z").toEpochMilli()

    private fun TestScope.repo(
        store: MemoryStore,
        source: FakeSource,
        clock: () -> Long,
        zone: () -> ZoneId = { ZoneOffset.UTC },
        excluded: () -> Set<String> = { emptySet() },
    ) = DayLedgerRepository(
        store = store, source = source, scope = backgroundScope, clock = clock, zone = zone,
        io = Dispatchers.Unconfined, minRefreshMillis = 0L, startTicker = false, maxLookbackDays = 20,
        excludedPackages = excluded,
    )

    @Test
    fun `apps in a locked folder never reach the stored ledger, totals still count them`() = runTest {
        val source = FakeSource(ZoneOffset.UTC)
        val today = LocalDate.of(2026, 11, 10)
        source.net[today.minusDays(1)] = wn(10, 5, "open.app" to 12, "secret.app" to 3)
        source.net[today] = wn(4, 2, "open.app" to 5, "secret.app" to 1)
        source.fg[today.minusDays(1)] = mapOf("open.app" to 60_000L, "secret.app" to 30_000L)
        source.fg[today] = mapOf("secret.app" to 9_000L)
        val store = MemoryStore()
        val repository = repo(store, source, { utc("2026-11-10") }, excluded = { setOf("secret.app") })

        repository.refresh()

        val l = repository.ledger.value
        assertEquals(10L, l.day(today.minusDays(1))!!.wifiBytes) // the totals still include it
        assertEquals(mapOf("open.app" to 12L), l.day(today.minusDays(1))!!.appData)
        assertEquals(mapOf("open.app" to 60_000L), l.day(today.minusDays(1))!!.appScreen)
        assertTrue(l.day(today)!!.appScreen.isEmpty())
        assertFalse(store.text!!.contains("secret.app")) // not in the stored file, so not in the backup
        assertFalse(l.months.values.any { "secret.app" in it.data || "secret.app" in it.screen })
    }

    @Test
    fun `entries stored earlier for a now-locked app are removed on the next refresh`() = runTest {
        val source = FakeSource(ZoneOffset.UTC)
        val today = LocalDate.of(2026, 11, 10)
        source.net[today.minusDays(1)] = wn(10, 5, "open.app" to 12, "secret.app" to 3)
        source.net[today] = wn(4, 2, "open.app" to 5, "secret.app" to 1)
        val store = MemoryStore()
        var locked = emptySet<String>()
        val repository = repo(store, source, { utc("2026-11-10") }, excluded = { locked })
        repository.refresh()
        assertTrue(store.text!!.contains("secret.app"))

        locked = setOf("secret.app")
        repository.refresh()

        assertFalse(store.text!!.contains("secret.app"))
        assertFalse(repository.ledger.value.months.values.any { "secret.app" in it.data })
        assertEquals(10L, repository.ledger.value.day(today.minusDays(1))!!.wifiBytes)
        assertEquals(12L, repository.ledger.value.months.getValue("2026-11").data.getValue("open.app"))
    }

    @Test
    fun `stripping works without usage access too`() = runTest {
        val source = FakeSource(ZoneOffset.UTC)
        val today = LocalDate.of(2026, 11, 10)
        source.net[today] = wn(4, 2, "secret.app" to 1)
        val store = MemoryStore()
        var locked = emptySet<String>()
        val repository = repo(store, source, { utc("2026-11-10") }, excluded = { locked })
        repository.refresh()
        locked = setOf("secret.app"); source.granted = false
        repository.refresh()
        assertFalse(store.text!!.contains("secret.app"))
    }

    private fun wn(wifi: Long?, mobile: Long?, vararg apps: Pair<String, Long>) =
        WindowNetwork(wifi, mobile, mapOf(*apps))

    @Test
    fun `first run backfills what Android still answers and remembers the first date`() = runTest {
        val source = FakeSource(ZoneOffset.UTC)
        val d = { n: Int -> LocalDate.of(2026, 11, 10).minusDays(n.toLong()) }
        // Forgotten days answer zero; real traffic starts 6 days ago.
        source.net[d(6)] = wn(100, 0, "a" to 100)
        source.net[d(5)] = wn(0, 0)
        source.net[d(4)] = wn(50, 10, "a" to 60)
        source.net[d(1)] = wn(7, 7, "a" to 14)
        source.net[d(0)] = wn(1, 2, "a" to 3)
        // Events only reach back to 3 days ago 08:00.
        source.events += listOf(
            ScreenEvent(true, utc(d(3).toString(), "08:00")), ScreenEvent(false, utc(d(3).toString(), "09:00")),
            ScreenEvent(true, utc(d(1).toString(), "10:00")), ScreenEvent(false, utc(d(1).toString(), "12:00")),
            ScreenEvent(true, utc(d(0).toString(), "10:00")),
        )
        val store = MemoryStore()
        val repository = repo(store, source, { utc("2026-11-10") })

        repository.refresh()

        val l = repository.ledger.value
        assertTrue(l.backfilled)
        assertEquals(d(6), l.firstDate)
        assertNull(l.day(d(7)))
        assertTrue(l.day(d(6))!!.closed)
        assertEquals(100L, l.day(d(6))!!.wifiBytes)
        assertEquals(0L, l.day(d(5))!!.wifiBytes) // a real zero after the first traffic
        assertNull(l.day(d(3))) // Android had nothing for it at all: not invented
        assertNull(l.day(d(4))!!.screenOnMillis) // before the oldest screen event
        assertEquals(2L * 3_600_000L, l.day(d(1))!!.screenOnMillis)
        assertEquals(3L, l.day(d(0))!!.let { it.appData.getValue("a") })
        assertFalse(l.day(d(0))!!.closed)
        assertEquals(2L * 3_600_000L, l.day(d(0))!!.screenOnMillis) // screen on since 10:00, now 12:00
        // The month sum holds only closed days: day -6 (100) and -4 (60) and -1 (14); today is still open.
        assertEquals(174L, l.months.getValue("2026-11").data.getValue("a"))
        assertNotNull(store.text)
        assertEquals(l, LedgerJson.decode(store.text))
    }

    @Test
    fun `without usage access nothing is stored and granting it later backfills`() = runTest {
        val source = FakeSource(ZoneOffset.UTC).also { it.granted = false }
        val store = MemoryStore()
        val repository = repo(store, source, { utc("2026-11-10") })

        repository.refresh()

        assertEquals(false, repository.accessGranted.value)
        assertEquals(DayLedger.Empty, repository.ledger.value)
        assertNull(store.text)

        source.granted = true
        source.net[LocalDate.of(2026, 11, 10)] = wn(1, 1)
        repository.refresh()
        assertEquals(true, repository.accessGranted.value)
        assertTrue(repository.ledger.value.backfilled)
        assertNotNull(repository.ledger.value.day(LocalDate.of(2026, 11, 10)))
    }

    @Test
    fun `a date change at midnight closes the finished day from its whole day numbers`() = runTest {
        val source = FakeSource(ZoneOffset.UTC)
        val nov10 = LocalDate.of(2026, 11, 10)
        val nov11 = nov10.plusDays(1)
        var now = utc("2026-11-10", "23:50")
        source.net[nov10] = wn(10, 0, "a" to 10)
        val repository = repo(MemoryStore(), source, { now })
        repository.refresh()
        assertFalse(repository.ledger.value.day(nov10)!!.closed)

        // The last ten minutes of the day add traffic; then midnight passes.
        source.net[nov10] = wn(25, 5, "a" to 30)
        source.net[nov11] = wn(1, 0, "a" to 1)
        now = utc("2026-11-11", "00:05")
        repository.refresh()
        repository.refresh() // a second refresh must not count the day again

        val l = repository.ledger.value
        assertTrue(l.day(nov10)!!.closed)
        assertEquals(25L, l.day(nov10)!!.wifiBytes)
        assertEquals(30L, l.months.getValue("2026-11").data.getValue("a"))
        assertFalse(l.day(nov11)!!.closed)
        assertEquals(1L, l.day(nov11)!!.wifiBytes)
        assertEquals(nov11, repository.today())
    }

    @Test
    fun `a month roll over keeps each month's app sums apart`() = runTest {
        val source = FakeSource(ZoneOffset.UTC)
        var now = utc("2026-10-31", "23:00")
        source.net[LocalDate.of(2026, 10, 31)] = wn(5, 0, "a" to 5)
        val repository = repo(MemoryStore(), source, { now })
        repository.refresh()
        source.net[LocalDate.of(2026, 11, 1)] = wn(2, 0, "a" to 2)
        now = utc("2026-11-01", "00:30")
        repository.refresh()
        source.net[LocalDate.of(2026, 11, 1)] = wn(9, 0, "a" to 9)
        now = utc("2026-11-02", "00:30")
        source.net[LocalDate.of(2026, 11, 2)] = wn(1, 0, "a" to 1)
        repository.refresh()

        val months = repository.ledger.value.months
        assertEquals(5L, months.getValue("2026-10").data.getValue("a"))
        assertEquals(9L, months.getValue("2026-11").data.getValue("a"))
    }

    @Test
    fun `a reboot is a fresh repository over the same store and counts nothing twice`() = runTest {
        val source = FakeSource(ZoneOffset.UTC)
        val store = MemoryStore()
        var now = utc("2026-11-10", "09:00")
        source.net[LocalDate.of(2026, 11, 9)] = wn(100, 0, "a" to 100)
        source.net[LocalDate.of(2026, 11, 10)] = wn(4, 0, "a" to 4)
        repo(store, source, { now }).refresh()

        // After the reboot: new process, same document, Android's counters restarted but its history did not.
        now = utc("2026-11-10", "09:30")
        source.net[LocalDate.of(2026, 11, 10)] = wn(6, 0, "a" to 6)
        val after = repo(store, source, { now })
        after.refresh()
        after.refresh()

        val l = after.ledger.value
        assertEquals(100L, l.day(LocalDate.of(2026, 11, 9))!!.wifiBytes)
        assertEquals(6L, l.day(LocalDate.of(2026, 11, 10))!!.wifiBytes) // rewritten, not added
        assertEquals(100L, l.months.getValue("2026-11").data.getValue("a"))
    }

    @Test
    fun `a time zone change never rewrites a closed day and uses 25 hour windows`() = runTest {
        val source = FakeSource(ZoneOffset.UTC)
        var zone: ZoneId = ZoneOffset.UTC
        var now = utc("2026-11-10", "22:00")
        source.net[LocalDate.of(2026, 11, 10)] = wn(10, 0, "a" to 10)
        source.net[LocalDate.of(2026, 11, 9)] = wn(3, 0)
        val repository = repo(MemoryStore(), source, { now }, { zone })
        repository.refresh()
        now = utc("2026-11-11", "01:00")
        source.net[LocalDate.of(2026, 11, 11)] = wn(1, 0)
        repository.refresh()
        val nov10 = repository.ledger.value.day(LocalDate.of(2026, 11, 10))!!
        assertTrue(nov10.closed)

        // The phone flies west: its local date is 10 Nov again. The closed day is left alone.
        zone = ZoneId.of("America/Los_Angeles")
        source.zone = zone
        source.net[LocalDate.of(2026, 11, 10)] = wn(999, 0, "a" to 999)
        repository.refresh()
        assertEquals(nov10, repository.ledger.value.day(LocalDate.of(2026, 11, 10)))
        assertEquals(LocalDate.of(2026, 11, 10), repository.today())

        // A day with a clock change is 25 hours long (New York, 1 Nov 2026).
        val ny = ZoneId.of("America/New_York")
        source.zone = ny
        source.networkWindows.clear()
        now = Instant.parse("2026-11-03T15:00:00Z").toEpochMilli()
        val fresh = repo(MemoryStore(), source, { now }, { ny })
        fresh.refresh()
        val fallBack = source.networkWindows.first {
            Instant.ofEpochMilli(it.first).atZone(ny).toLocalDate() == LocalDate.of(2026, 11, 1)
        }
        assertEquals(25L * 3_600_000L, fallBack.second - fallBack.first)
    }

    @Test
    fun `another card's fields survive the refresh and the closing`() = runTest {
        val source = FakeSource(ZoneOffset.UTC)
        var now = utc("2026-11-10", "10:00")
        source.net[LocalDate.of(2026, 11, 10)] = wn(1, 0)
        val repository = repo(MemoryStore(), source, { now })
        repository.refresh()

        repository.updateToday { it.copy(steps = 4_200, pickups = 31) }
        source.net[LocalDate.of(2026, 11, 10)] = wn(8, 0)
        repository.refresh()
        var today = repository.ledger.value.day(LocalDate.of(2026, 11, 10))!!
        assertEquals(4_200, today.steps)
        assertEquals(8L, today.wifiBytes)

        now = utc("2026-11-11", "00:10")
        source.net[LocalDate.of(2026, 11, 11)] = wn(1, 0)
        repository.refresh()
        today = repository.ledger.value.day(LocalDate.of(2026, 11, 10))!!
        assertTrue(today.closed)
        assertEquals(4_200, today.steps)
        assertEquals(31, today.pickups)
        assertEquals(listOf(today), repository.range(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 10)))
    }

    @Test
    fun `a restored backup is read on the next refresh`() = runTest {
        val source = FakeSource(ZoneOffset.UTC)
        val store = MemoryStore()
        val now = utc("2026-11-10")
        source.net[LocalDate.of(2026, 11, 10)] = wn(1, 0)
        val repository = repo(store, source, { now })
        repository.refresh()

        val restored = DayLedger(backfilled = true).withDay(
            LedgerDay(LocalDate.of(2026, 8, 12), wifiBytes = 77, closed = true),
        )
        store.text = LedgerJson.encode(restored)
        repository.refresh()

        assertEquals(LocalDate.of(2026, 8, 12), repository.ledger.value.firstDate)
        assertEquals(77L, repository.ledger.value.day(LocalDate.of(2026, 8, 12))!!.wifiBytes)
    }
}
