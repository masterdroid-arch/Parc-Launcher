package com.brightblu.parc.arcade

import com.brightblu.parc.core.data.ledger.DayLedger
import com.brightblu.parc.core.data.ledger.LedgerDay
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T200: the earned unlock, the registry rules and what the arcade remembers. */
class ArcadeUnlockTest {
    private val hour = 3_600_000L
    private val d0 = LocalDate.of(2026, 11, 1)
    private fun day(date: LocalDate, hours: Long?, closed: Boolean = true) = LedgerDay(date, screenOnMillis = hours?.let { it * hour }, closed = closed)
    private fun ledger(vararg days: LedgerDay) = days.fold(DayLedger.Empty) { l, d -> l.withDay(d) }
    /** [n] closed days from [start], all [hours] long. */
    private fun run(start: LocalDate, n: Int, hours: Long) = (0 until n).map { day(start.plusDays(it.toLong()), hours) }
    private fun ledgerOf(days: List<LedgerDay>) = days.fold(DayLedger.Empty) { l, d -> l.withDay(d) }

    @Test fun aCalmDayUnderTheFourteenDayAverageQualifies() {
        val history = run(d0, 10, 5)
        val calm = day(d0.plusDays(10), 3)
        assertTrue(ArcadeEarned.qualifies(ledgerOf(history + calm), calm.date))
    }

    @Test fun equalToTheAverageIsNotUnder() {
        val history = run(d0, 10, 5)
        val same = day(d0.plusDays(10), 5)
        assertFalse(ArcadeEarned.qualifies(ledgerOf(history + same), same.date))
    }

    @Test fun fewerThanSevenLedgerDaysNeverQualifies() {
        val history = run(d0, 6, 5)
        val calm = day(d0.plusDays(6), 1)
        assertNull(ArcadeEarned.average(ledgerOf(history + calm), calm.date))
        assertFalse(ArcadeEarned.qualifies(ledgerOf(history + calm), calm.date))
        val seven = run(d0, 7, 5)
        val calm7 = day(d0.plusDays(7), 1)
        assertTrue(ArcadeEarned.qualifies(ledgerOf(seven + calm7), calm7.date))
    }

    @Test fun daysWithoutScreenDataDoNotCountAsHistory() {
        val history = run(d0, 6, 5) + day(d0.plusDays(6), null)
        val calm = day(d0.plusDays(7), 1)
        assertFalse(ArcadeEarned.qualifies(ledgerOf(history + calm), calm.date))
    }

    @Test fun aDayThatIsNotClosedDoesNotQualify() {
        val history = run(d0, 10, 5)
        val open = day(d0.plusDays(10), 1, closed = false)
        assertFalse(ArcadeEarned.qualifies(ledgerOf(history + open), open.date))
    }

    @Test fun onlyTheFourteenDaysBeforeAreTheBaseline() {
        // Old heavy days beyond 14 days back must not raise the average.
        val ancient = run(d0, 10, 20)
        val recent = run(d0.plusDays(20), 8, 4)
        val day = day(d0.plusDays(28), 5)
        assertFalse(ArcadeEarned.qualifies(ledgerOf(ancient + recent + day), day.date))
    }

    @Test fun theWatchStartsOnTheFirstLookAndHistoryBeforeItNeverCounts() {
        // A backfilled ledger holds a calm day long before the watch: the first check only starts the watch.
        val history = run(d0, 10, 5) + day(d0.plusDays(10), 1)
        val l = ledgerOf(history)
        val today = d0.plusDays(12)
        val first = ArcadeUnlockLogic.checkEarned(ArcadeState(), l, today)
        assertEquals(today, first.since)
        assertFalse(first.open)
        // Still the same day, then the next day: the calm day is before the watch.
        assertFalse(ArcadeUnlockLogic.checkEarned(first, l, today.plusDays(1)).open)
    }

    @Test fun theFirstFullDayAfterTheWatchStartedCanUnlock() {
        val history = run(d0, 10, 5)
        val since = d0.plusDays(10)
        val state = ArcadeState(since = since)
        // The day the watch started never counts, even if calm, nor does today.
        val l1 = ledgerOf(history + day(since, 1))
        assertFalse(ArcadeUnlockLogic.checkEarned(state, l1, since.plusDays(1)).open)
        // The next full day, closed and calm, does (checked the day after it).
        val l2 = ledgerOf(history + day(since, 5) + day(since.plusDays(1), 2))
        val unlocked = ArcadeUnlockLogic.checkEarned(state, l2, since.plusDays(2))
        assertTrue(unlocked.open)
        assertTrue(unlocked.isUnlocked("orbit"))
        // Today itself (not yet a full day) does not.
        assertFalse(ArcadeUnlockLogic.checkEarned(state, l2, since.plusDays(1)).open)
    }

    @Test fun anAlreadyUnlockedGameIsNeverChecked() {
        val state = ArcadeState(unlocked = setOf("orbit"))
        assertTrue(ArcadeUnlockLogic.checkEarned(state, DayLedger.Empty, d0) === state)
        assertFalse(ArcadeUnlockLogic.pending(state, ArcadeRules.CalmDayOrArcSpin))
    }

    @Test fun theRuleUnlocksItsGamesAndReportsWhatIsNew() {
        val before = ArcadeState()
        val after = ArcadeUnlockLogic.unlockByRule(before, ArcadeRules.CalmDayOrArcSpin)
        assertEquals(listOf("orbit"), ArcadeUnlockLogic.newlyUnlocked(before, after))
        assertTrue(after.open)
        assertEquals(emptyList<String>(), ArcadeUnlockLogic.newlyUnlocked(after, ArcadeUnlockLogic.unlockByRule(after, ArcadeRules.CalmDayOrArcSpin)))
        assertTrue(ArcadeUnlockLogic.unlockByRule(before, "no_such_rule") === before)
    }

    @Test fun theRegistryHasUniqueIdsAndKeys() {
        val games = ArcadeCatalog.games
        assertEquals(games.size, games.map { it.id }.toSet().size)
        assertEquals(games.size, games.map { it.bestKey }.toSet().size)
        assertTrue(games.all { it.id == it.id.lowercase() && it.title.isNotEmpty() && it.unlockRuleId.isNotEmpty() })
        assertEquals("Orbit", ArcadeCatalog.game("orbit")?.title)
    }

    @Test fun stateRoundTripAndGarbage() {
        val state = ArcadeState(setOf("orbit", "lander"), mapOf("orbit" to 12), LocalDate.of(2026, 11, 12))
        val back = ArcadeState.decode(ArcadeState.encodeGames(state.unlocked), ArcadeState.encodeBest(state.best), state.since.toString())
        assertEquals(state, back)
        assertEquals(ArcadeState(), ArcadeState.decode(null, null, null))
        assertEquals(ArcadeState(), ArcadeState.decode("", "", ""))
        val garbled = ArcadeState.decode(",,Bad Id,orbit!", "orbit:x,:3,orbit:-1,a:b:c,,", "yesterday")
        assertEquals(emptySet<String>(), garbled.unlocked)
        assertEquals(emptyMap<String, Int>(), garbled.best)
        assertNull(garbled.since)
    }

    @Test fun anUnknownGameAloneDoesNotOpenTheArcade() {
        assertFalse(ArcadeState(unlocked = setOf("future_game")).open)
        assertTrue(ArcadeState(unlocked = setOf("future_game", "orbit")).open)
    }

    @Test fun bestScoresOnlyGoUp() {
        val game = ArcadeCatalog.game("orbit")!!
        val s = ArcadeState().withBest(game, 7)
        assertEquals(7, s.bestOf(game))
        assertTrue(s.withBest(game, 3) === s)
        assertEquals(9, s.withBest(game, 9).bestOf(game))
    }
}
