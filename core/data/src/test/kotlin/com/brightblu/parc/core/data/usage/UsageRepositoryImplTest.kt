package com.brightblu.parc.core.data.usage

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repository contract: recents, the alphabetical folder order (T36), the cold-start restore and
 * the optional enrichment, driven by in-memory stores and a clock the test owns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsageRepositoryImplTest {

    private val a = testId("a")
    private val b = testId("b")
    private val c = testId("c")
    private val d = testId("d")
    private val e = testId("e")
    private val x = testId("x")
    private val social = testFolder("Social", listOf(a, b, c, d, e, x))

    /** The names the ring would draw, chosen so label order differs from membership order. */
    private val labels = mapOf(
        a to "Alpha",
        b to "bravo",
        c to "CHARLIE",
        d to "delta",
        e to "zulu",
        x to "echo",
    )

    /** A repository over in-memory stores. [now] is set before construction, so the cold start sees it. */
    private class Harness(
        scope: TestScope,
        val folder: Folder,
        var now: Long = 0L,
        val store: InMemoryUsageStore = InMemoryUsageStore(),
        val stateStore: InMemoryUsageStateStore = InMemoryUsageStateStore(),
        val enrichment: FakeEnrichment? = null,
        pinned: Set<AppId> = emptySet(),
        appIdForPackage: (String) -> AppId? = { null },
        labels: Map<AppId, String> = emptyMap(),
        overrideStore: UsageStore? = null,
    ) {
        val warnings = ArrayList<String>()

        val repo = UsageRepositoryImpl(
            store = overrideStore ?: store,
            stateStore = stateStore,
            folders = { listOf(folder) },
            pinned = { pinned },
            clock = { now },
            scope = scope,
            ioDispatcher = UnconfinedTestDispatcher(scope.testScheduler),
            enrichment = enrichment,
            appIdForPackage = { appIdForPackage },
            logWarn = { warnings += it },
            labels = { labels },
        )
    }

    @Test
    fun `recents are newest first, distinct and capped`() = runTest {
        val harness = Harness(this, social)

        harness.repo.recordLaunch(a)
        harness.repo.recordLaunch(b)
        harness.repo.recordLaunch(a)

        assertEquals(listOf(a, b), harness.repo.recents(10).first())
        assertEquals(listOf(a), harness.repo.recents(1).first())
        assertEquals(emptyList<AppId>(), harness.repo.recents(0).first())
        assertEquals(3, harness.store.all().size)
    }

    @Test
    fun `recents exclude pinned apps`() = runTest {
        val harness = Harness(this, social, pinned = setOf(b))

        harness.repo.recordLaunch(b)
        harness.repo.recordLaunch(a)

        assertEquals(listOf(a), harness.repo.recents(10).first())
    }

    @Test
    fun `recents and the log survive a restart`() = runTest {
        val first = Harness(this, social)
        first.repo.recordLaunch(a)
        first.repo.recordLaunch(x)

        val second = Harness(this, social, store = first.store, stateStore = first.stateStore)

        assertEquals(listOf(x, a), second.repo.recents(10).first())
    }

    @Test
    fun `rank orders the folder alphabetically by label, case-insensitive`() = runTest {
        val harness = Harness(this, social, labels = labels)

        // membership (a, b, c, d, e, x) is not label order; the ring shows label order.
        assertEquals(listOf(a, b, c, d, x, e), harness.repo.rank(social.id))
        assertEquals(emptyList<AppId>(), harness.repo.rank(FolderId("nope")))
    }

    @Test
    fun `rank falls back to the stored membership order when no labels are known`() = runTest {
        // The seed writes membership alphabetically (T36), so an unwired build still shows it in order.
        val harness = Harness(this, social)

        assertEquals(listOf(a, b, c, d, e, x), harness.repo.rank(social.id))
    }

    @Test
    fun `launches never reorder a folder and a restart keeps the same order`() = runTest {
        val first = Harness(this, social, labels = labels)
        val expected = listOf(a, b, c, d, x, e)
        assertEquals(expected, first.repo.rank(social.id))

        repeat(50) { first.repo.recordLaunch(x) }
        first.now = 3 * UsageRanking.MillisPerDay
        first.repo.loadAndRecompute()

        assertEquals(expected, first.repo.rank(social.id))

        val second = Harness(
            this,
            social,
            now = first.now + 5_000L,
            store = first.store,
            stateStore = first.stateStore,
            labels = labels,
        )

        assertEquals(expected, second.repo.rank(social.id))
        second.repo.loadAndRecompute()
        assertEquals(expected, second.repo.rank(social.id))
    }

    @Test
    fun `enrichment adds only the launches our own log does not have`() = runTest {
        val enrichment = FakeEnrichment()
        val harness = Harness(
            this,
            social,
            enrichment = enrichment,
            appIdForPackage = { pkg -> if (pkg == "com.test.a") a else null },
        )
        harness.repo.recordLaunch(a)
        enrichment.events = listOf(
            UsageEvent("com.test.a", 0L),
            UsageEvent("com.test.a", 20_000L),
            UsageEvent("com.test.a", 10_000L),
            UsageEvent("com.test.notours", 40_000L),
        )

        harness.repo.loadAndRecompute()

        // The newest three events, one of which is the launch our own log recorded at the same instant.
        assertEquals(3, harness.store.countFor(a))
        assertTrue(harness.store.all().all { it.appId == a })
    }

    @Test
    fun `enrichment is asked once, and not again while the watermark holds`() = runTest {
        val enrichment = FakeEnrichment(events = emptyList())
        val harness = Harness(this, social, enrichment = enrichment)

        harness.repo.loadAndRecompute()
        harness.repo.loadAndRecompute()

        assertEquals(1, enrichment.calls)
        assertEquals(-UsageRepositoryImpl.EnrichmentWindowMillis, enrichment.lastWindow!!.first)
        assertEquals(0L, enrichment.lastWindow!!.second)
        assertEquals(0L, harness.stateStore.state.enrichmentWatermarkMillis)
    }

    @Test
    fun `enrichment that is not granted adds nothing and keeps the window open`() = runTest {
        val enrichment = FakeEnrichment(events = null)
        val harness = Harness(
            this,
            social,
            enrichment = enrichment,
            appIdForPackage = { pkg -> if (pkg == "com.test.a") a else null },
        )
        harness.repo.recordLaunch(a)

        harness.repo.loadAndRecompute()

        assertEquals(1, harness.store.countFor(a))
        assertNull(harness.stateStore.state.enrichmentWatermarkMillis)
    }

    @Test
    fun `the log is pruned to the cap at cold start`() = runTest {
        val harness = Harness(this, social)

        harness.repo.loadAndRecompute()

        assertEquals(UsageRepositoryImpl.MaxRows, harness.store.prunedTo)
    }

    @Test
    fun `a failing store leaves the repository usable and logs one constant string`() = runTest {
        val harness = Harness(this, social, overrideStore = FailingUsageStore())

        harness.repo.recordLaunch(a)
        harness.repo.loadAndRecompute()

        assertEquals(listOf(a, b, c, d, e, x), harness.repo.rank(social.id))
        assertTrue(harness.warnings.isNotEmpty())
        assertTrue(harness.warnings.all { !it.contains("com.test") })
    }

    @Test
    fun `a launch recorded while the log read is in flight is not lost`() = runTest {
        val stale = listOf(LaunchRow(a, -10L))
        val harness = Harness(this, social, overrideStore = StaleLoadUsageStore(stale))
        assertEquals(listOf(a), harness.repo.recents(5).first())

        harness.repo.recordLaunch(x)
        harness.repo.loadAndRecompute()

        assertEquals(listOf(x, a), harness.repo.recents(5).first())
    }

    @Test
    fun `the usage layer reads the decayed score from the same log`() = runTest {
        val harness = Harness(this, social, now = 0L)
        harness.repo.recordLaunch(a)
        harness.repo.recordLaunch(a)

        assertEquals(2.0, harness.repo.scores(0L).getValue(a), 1e-9)
        harness.now = UsageRanking.HalfLifeMillis
        assertEquals(1.0, harness.repo.scores(UsageRanking.HalfLifeMillis).getValue(a), 1e-9)
        assertNull(harness.repo.scores(0L)[b])
    }

    @Test
    fun `awaitLoaded returns once the stored log is in memory`() = runTest {
        val stored = InMemoryUsageStore()
        stored.record(LaunchRow(a, 1L))
        val harness = Harness(this, social, now = 1L, store = stored)

        harness.repo.awaitLoaded()

        assertEquals(1.0, harness.repo.scores(1L).getValue(a), 1e-9)
    }

    /** Every operation fails, the way a corrupted database or a full disk would. */
    private class FailingUsageStore : UsageStore {
        override suspend fun load(limit: Int): List<LaunchRow> = throw IllegalStateException("load")
        override suspend fun record(row: LaunchRow) = throw IllegalStateException("record")
        override suspend fun prune(keep: Int) = throw IllegalStateException("prune")
    }

    /** A read that keeps returning an outdated snapshot, the way a read racing a write would look. */
    private class StaleLoadUsageStore(private val stale: List<LaunchRow>) : UsageStore {
        override suspend fun load(limit: Int): List<LaunchRow> = stale
        override suspend fun record(row: LaunchRow) = Unit
        override suspend fun prune(keep: Int) = Unit
    }
}
