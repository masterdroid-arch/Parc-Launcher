package com.brightblu.parc.core.data.usage

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.FolderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decay, buckets and the hysteresis decision, all against synthetic scores: no clock, no database,
 * no coroutines, so every branch of the rule is pinned down exactly.
 */
class UsageRankingTest {

    private val a = testId("a")
    private val b = testId("b")
    private val c = testId("c")
    private val d = testId("d")
    private val e = testId("e")
    private val x = testId("x")
    private val folder = testFolder("Social", listOf(a, b, c, d, e, x))

    private fun scores(vararg pairs: Pair<AppId, Double>): Map<AppId, Double> = pairs.toMap()

    private fun bucketOfOrder(state: UsageRankState, id: AppId, folderId: FolderId = folder.id): Int {
        val order = state.ranked.getValue(folderId.value).map(::AppId)
        return UsageRanking.bucketOf(order.indexOf(id))
    }

    /** One recompute, as a cold start would run it. */
    private fun recompute(
        previous: UsageRankState,
        scores: Map<AppId, Double>,
        today: Long,
        pinned: Set<AppId> = emptySet(),
        members: List<AppId> = listOf(a, b, c, d, e, x),
    ): RankRecompute = UsageRanking.recompute(
        state = previous,
        folders = listOf(testFolder("Social", members)),
        scores = scores,
        pinned = pinned,
        today = today,
    )

    /** State whose committed buckets are the ones the given scores imply (a day-zero placement). */
    private fun placed(scores: Map<AppId, Double>, members: List<AppId> = listOf(a, b, c, d, e, x)): UsageRankState =
        recompute(UsageRankState(), scores, today = 1L, members = members).state

    @Test
    fun `a score halves over one half-life`() {
        assertEquals(1.0, UsageRanking.score(listOf(0L), 0L), 1e-9)
        assertEquals(0.5, UsageRanking.score(listOf(0L), UsageRanking.HalfLifeMillis), 1e-9)
        assertEquals(0.25, UsageRanking.score(listOf(0L), 2 * UsageRanking.HalfLifeMillis), 1e-9)
    }

    @Test
    fun `a score sums launches and never counts a future launch as negative age`() {
        assertEquals(3.0, UsageRanking.score(listOf(0L, 0L, 0L), 0L), 1e-9)
        assertEquals(2.0, UsageRanking.score(listOf(0L, UsageRanking.MillisPerDay * 100), 0L), 1e-9)
    }

    @Test
    fun `scores group rows per app and leave unknown apps absent`() {
        val rows = listOf(
            LaunchRow(a, 0L),
            LaunchRow(a, UsageRanking.HalfLifeMillis),
            LaunchRow(b, 0L),
        )

        val scored = UsageRanking.scores(rows, UsageRanking.HalfLifeMillis)

        assertEquals(1.5, scored.getValue(a), 1e-9)
        assertEquals(0.5, scored.getValue(b), 1e-9)
        assertNull(scored[e])
    }

    @Test
    fun `bucket boundaries are the design's vier arcs`() {
        assertEquals(0, UsageRanking.bucketOf(0))
        assertEquals(0, UsageRanking.bucketOf(3))
        assertEquals(1, UsageRanking.bucketOf(4))
        assertEquals(1, UsageRanking.bucketOf(9))
        assertEquals(2, UsageRanking.bucketOf(10))
        assertEquals(2, UsageRanking.bucketOf(17))
        assertEquals(3, UsageRanking.bucketOf(18))
        assertEquals(3, UsageRanking.bucketOf(400))
        assertEquals(0, UsageRanking.bucketStart(0))
        assertEquals(4, UsageRanking.bucketStart(1))
        assertEquals(10, UsageRanking.bucketStart(2))
        assertEquals(18, UsageRanking.bucketStart(3))
    }

    @Test
    fun `relative gap is a fraction of the higher score and zero when there is nothing to compare`() {
        assertEquals(0.5, UsageRanking.relativeGap(1.0, 0.5), 1e-9)
        assertEquals(0.0, UsageRanking.relativeGap(0.0, 0.0), 1e-9)
        assertEquals(1.0, UsageRanking.relativeGap(1.0, 0.0), 1e-9)
    }

    @Test
    fun `candidate order is score descending then id ascending`() {
        val order = UsageRanking.candidateOrder(
            folder,
            scores(b to 1.0, a to 1.0, c to 0.5),
        )

        assertEquals(listOf(a, b, c, d, e, x), order)
    }

    @Test
    fun `the first recompute places every app by its candidate bucket`() {
        val state = placed(scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0, e to 1.0, x to 0.5))

        assertEquals(listOf(a, b, c, d, e, x), state.ranked.getValue(folder.id.value).map(::AppId))
        assertEquals(0, state.committed(folder.id, a))
        assertEquals(1, state.committed(folder.id, e))
        assertEquals(1, state.committed(folder.id, x))
        assertTrue(state.pending.getValue(folder.id.value).isEmpty())
    }

    @Test
    fun `a single day of divergence does not move an app`() {
        val day1 = placed(scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0, x to 2.5, e to 1.0))
        assertEquals(0, bucketOfOrder(day1, x))

        // x drops below the A1 boundary; its target bucket is now A2 and it clearly trails d.
        val day2 = recompute(day1, scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0, x to 0.1, e to 0.05), today = 2L)

        assertTrue(day2.moves.isEmpty())
        assertEquals(0, bucketOfOrder(day2.state, x))
        assertEquals(1, day2.state.pending(folder.id, x))
    }

    @Test
    fun `a second consecutive day of divergence moves the app to the end of its new bucket`() {
        val day1 = placed(scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0, x to 2.5, e to 1.0))
        val day2 = recompute(day1, scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0, x to 0.1, e to 0.05), today = 2L)
        val day3 = recompute(day2.state, scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0, x to 0.1, e to 0.05), today = 3L)

        assertEquals(listOf(ArcMove(folder.id, x, 0, 1)), day3.moves)
        assertEquals(1, bucketOfOrder(day3.state, x))
        assertEquals(1, day3.state.committed(folder.id, x))
        // x is committed; d is still waiting because its own score gap versus the boundary is zero.
        assertNull(day3.state.pending(folder.id, x))
        assertEquals(mapOf(d.value to 0), day3.state.pending.getValue(folder.id.value))
        // Every app that stayed keeps its relative position; the mover is appended behind e.
        assertEquals(listOf(a, b, c, d, e, x), day3.state.ranked.getValue(folder.id.value).map(::AppId))
    }

    @Test
    fun `a score gap at or below twenty percent never commits the move`() {
        val day1 = placed(scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0, x to 2.5, e to 1.0))
        val day2 = recompute(day1, scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0, x to 1.9, e to 0.05), today = 2L)
        val day3 = recompute(day2.state, scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0, x to 1.9, e to 0.05), today = 3L)
        val day4 = recompute(day3.state, scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0, x to 1.9, e to 0.05), today = 4L)

        // x is now below d(2.0), but only by 5 % of it, so it stays where it is indefinitely.
        assertTrue(day3.moves.isEmpty())
        assertTrue(day4.moves.isEmpty())
        assertEquals(0, bucketOfOrder(day4.state, x))
        assertEquals(1, day4.state.pending(folder.id, x))
    }

    @Test
    fun `a divergence that resolves is forgotten`() {
        val day1 = placed(scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0, x to 2.5, e to 1.0))
        val day2 = recompute(day1, scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0, x to 0.1, e to 0.05), today = 2L)
        assertEquals(1, day2.state.pending(folder.id, x))

        val day3 = recompute(day2.state, scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0, x to 2.5, e to 1.0), today = 3L)

        assertTrue(day3.moves.isEmpty())
        assertEquals(0, bucketOfOrder(day3.state, x))
        assertTrue(day3.state.pending.getValue(folder.id.value).isEmpty())
    }

    @Test
    fun `a pinned app inside a folder never changes bucket`() {
        val day1 = placed(scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0, x to 2.5, e to 1.0))
        val day2 = recompute(day1, scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0, x to 0.1, e to 0.05), today = 2L, pinned = setOf(x))
        val day3 = recompute(day2.state, scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0, x to 0.1, e to 0.05), today = 3L, pinned = setOf(x))

        assertTrue(day3.moves.isEmpty())
        assertEquals(0, bucketOfOrder(day3.state, x))
        assertEquals(0, day3.state.committed(folder.id, x))
    }

    @Test
    fun `a confirmed move ripples its neighbours by at most one position`() {
        val day1 = placed(scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0, x to 2.5, e to 1.0))
        val day2 = recompute(day1, scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0, x to 0.1, e to 0.05), today = 2L)
        val day3 = recompute(day2.state, scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0, x to 0.1, e to 0.05), today = 3L)

        val before = day2.state.ranked.getValue(folder.id.value).map(::AppId)
        val after = day3.state.ranked.getValue(folder.id.value).map(::AppId)
        assertEquals(listOf(ArcMove(folder.id, x, 0, 1)), day3.moves)
        // The mover crosses the boundary; every other app shifts by at most one slot.
        for (id in before) {
            if (id == x) continue
            assertTrue("$id moved more than one position", kotlin.math.abs(after.indexOf(id) - before.indexOf(id)) <= 1)
        }
        assertEquals(UsageRanking.bucketOf(after.indexOf(x)), day3.state.committed(folder.id, x))
    }

    @Test
    fun `every committed bucket matches the position the app is displayed at`() {
        var state = placed(scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0, x to 2.5, e to 1.0))
        val falling = scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0, x to 0.1, e to 0.05)

        for (day in 2L..6L) state = recompute(state, falling, today = day).state

        val order = state.ranked.getValue(folder.id.value).map(::AppId)
        for ((index, id) in order.withIndex()) {
            assertEquals(UsageRanking.bucketOf(index), state.committed(folder.id, id))
        }
    }

    @Test
    fun `the same app is ranked per folder, not globally`() {
        val big = (1..25).map { testId("app$it") }
        val folders = listOf(testFolder("Big", big), testFolder("Small", listOf(x, a)))
        val scores = big.indices.associate { big[it] to (25.0 - it) } + mapOf(x to 0.5, a to 0.4)

        val result = UsageRanking.recompute(UsageRankState(), folders, scores, emptySet(), today = 1L)

        assertEquals(3, result.state.committed(FolderId("big"), big[24]))
        assertEquals(0, result.state.committed(FolderId("small"), x))
        assertEquals(0, result.state.committed(FolderId("small"), a))
    }

    @Test
    fun `apps that left a folder drop out of the stored state`() {
        val day1 = placed(scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0, x to 2.5, e to 1.0))
        val day2 = recompute(day1, scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0), today = 2L, members = listOf(a, b, c, d))
        val day3 = recompute(day2.state, scores(a to 5.0, b to 4.0, c to 3.0, d to 2.0), today = 3L, members = listOf(a, b, c, d))

        assertEquals(listOf(a, b, c, d), day3.state.ranked.getValue(folder.id.value).map(::AppId))
        assertNull(day3.state.committed(folder.id, x))
        assertNull(day3.state.pending(folder.id, x))
    }

    @Test
    fun `an empty folder keeps an empty order`() {
        val result = UsageRanking.recompute(
            UsageRankState(),
            listOf(testFolder("Empty", emptyList())),
            emptyMap(),
            emptySet(),
            today = 1L,
        )

        assertTrue(result.state.ranked.getValue("empty").isEmpty())
        assertTrue(result.moves.isEmpty())
    }

    @Test
    fun `the day guard is true once per day only`() {
        val state = UsageRankState(lastRecomputeDay = 7L)

        assertTrue(UsageRanking.isDue(state, 8L))
        assertTrue(!UsageRanking.isDue(state, 7L))
        assertEquals(7L, UsageRanking.dayOf(7L * UsageRanking.MillisPerDay + 12))
    }
}
