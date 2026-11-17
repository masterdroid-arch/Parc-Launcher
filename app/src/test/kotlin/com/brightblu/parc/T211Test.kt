package com.brightblu.parc

import com.brightblu.parc.arcade.ArcadeCatalog
import com.brightblu.parc.arcade.ArcadeRules
import com.brightblu.parc.arcade.ArcadeState
import com.brightblu.parc.arcade.ArcadeUnlockLogic
import com.brightblu.parc.arcade.MoonTapDetector
import com.brightblu.parc.arcade.ArcadeReveal
import com.brightblu.parc.core.data.ledger.DayLedger
import com.brightblu.parc.core.data.ledger.LedgerDay
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** T211: the second arcade game, its two quiet unlocks, and that it stays out of public words. */
class T211Test {
    private val today: LocalDate = LocalDate.of(2026, 11, 12)

    private fun ledger(vararg days: LedgerDay) = days.fold(DayLedger.Empty) { l, d -> l.withDay(d) }

    @Test fun landerIsASecondRegisteredGameWithItsOwnRuleAndBestScore() {
        val lander = ArcadeCatalog.game("lander")
        assertEquals("Lander", lander?.title)
        assertEquals("lander", lander?.bestKey)
        assertEquals(ArcadeRules.StepGoalOrMoonTap, lander?.unlockRuleId)
        assertEquals("Orbit", ArcadeCatalog.game("orbit")?.title)
        assertTrue(ArcadeCatalog.ids.containsAll(listOf("orbit", "lander")))
        // The two games do not share a rule or a best-score key.
        assertEquals(2, ArcadeCatalog.games.map { it.id }.toSet().size)
        assertEquals(2, ArcadeCatalog.games.map { it.bestKey }.toSet().size)
        assertEquals(2, ArcadeCatalog.games.map { it.unlockRuleId }.toSet().size)
    }

    @Test fun landerIsInvisibleWhileOnlyOrbitIsUnlocked() {
        val orbitOnly = ArcadeState(unlocked = setOf("orbit"))
        assertTrue(orbitOnly.open)
        assertFalse(orbitOnly.isUnlocked("lander"))
        val landerOnly = ArcadeState(unlocked = setOf("lander"))
        assertTrue(landerOnly.open)
        assertTrue(landerOnly.isUnlocked("lander"))
        // Unlocking the second game adds its tile but does not unlock the first and does not need a new page.
        val both = ArcadeUnlockLogic.unlockByRule(orbitOnly, ArcadeRules.StepGoalOrMoonTap)
        assertTrue(both.isUnlocked("orbit"))
        assertTrue(both.isUnlocked("lander"))
        assertEquals(listOf("lander"), ArcadeUnlockLogic.newlyUnlocked(orbitOnly, both))
        assertEquals("arcade", com.brightblu.parc.arcade.ArcadePage.Id)
    }

    @Test fun reachingTheStepGoalUnlocksLanderAndNothingElse() {
        val day = LedgerDay(today, steps = 8_000)
        val unlocked = ArcadeUnlockLogic.checkStepGoal(ArcadeState(), ledger(day), today, 8_000)
        assertTrue(unlocked.isUnlocked("lander"))
        assertFalse(unlocked.isUnlocked("orbit"))
        assertEquals(listOf("lander"), unlocked.unlocked.toList())
    }

    @Test fun aGoalThatWasNotReachedAGoalThatIsOffAndFutureDaysDoNotUnlockIt() {
        val below = ledger(LedgerDay(today, steps = 7_999))
        assertFalse(ArcadeUnlockLogic.checkStepGoal(ArcadeState(), below, today, 8_000).open)
        assertFalse(ArcadeUnlockLogic.checkStepGoal(ArcadeState(), ledger(LedgerDay(today, steps = 20_000)), today, null).open)
        assertFalse(ArcadeUnlockLogic.checkStepGoal(ArcadeState(), ledger(LedgerDay(today, steps = 20_000)), today, 0).open)
        val future = ledger(LedgerDay(today.plusDays(1), steps = 20_000))
        assertFalse(ArcadeUnlockLogic.checkStepGoal(ArcadeState(), future, today, 8_000).open)
    }

    @Test fun theGoalCanHaveBeenReachedOnAnEarlierRecordedDay() {
        val l = ledger(LedgerDay(today.minusDays(3), steps = 9_000), LedgerDay(today, steps = 100))
        assertTrue(ArcadeUnlockLogic.stepGoalReached(l, today, 8_000))
        assertTrue(ArcadeUnlockLogic.checkStepGoal(ArcadeState(), l, today, 8_000).isUnlocked("lander"))
    }

    @Test fun anAlreadyUnlockedLanderIsNeverCheckedAgain() {
        val state = ArcadeState(unlocked = setOf("lander"))
        val l = ledger(LedgerDay(today, steps = 20_000))
        assertSame(state, ArcadeUnlockLogic.checkStepGoal(state, l, today, 8_000))
        assertFalse(ArcadeUnlockLogic.pending(state, ArcadeRules.StepGoalOrMoonTap))
    }

    @Test fun sevenMoonTapsWithinFiveSecondsUnlockTheShortcut() {
        val detector = MoonTapDetector()
        for (i in 0 until 6) assertFalse(detector.feed(i * 800L))
        assertTrue(detector.feed(4_800L))
        // The detector starts over after the unlock.
        for (i in 0 until 6) assertFalse(detector.feed(10_000L + i * 100L))
        assertTrue(detector.feed(10_600L))
    }

    @Test fun sevenMoonTapsSpreadOverMoreThanFiveSecondsDoNot() {
        val detector = MoonTapDetector()
        for (i in 0 until 7) assertFalse(detector.feed(i * 1_000L))
        // Exactly the window is allowed.
        val exact = MoonTapDetector()
        for (i in 0 until 6) assertFalse(exact.feed(i * 1_000L))
        assertTrue(exact.feed(5_000L))
        // A pause that makes the group too slow, then one more, still does not trigger.
        assertFalse(detector.feed(7_000L))
    }

    @Test fun moonTapsThatGoBackwardsStartOver() {
        val detector = MoonTapDetector()
        for (i in 0 until 3) detector.feed(1_000L + i)
        assertFalse(detector.feed(0L))
        for (i in 1 until 6) assertFalse(detector.feed(i * 100L))
        assertTrue(detector.feed(600L))
    }

    @Test fun publicWordsDoNotNameTheGame() {
        val word = Regex("(?<![\\w])lander(?![\\w])", RegexOption.IGNORE_CASE)
        val notes = File("src/main/assets/changelog.json")
        if (notes.exists()) assertFalse(word.containsMatchIn(notes.readText()))
        val readme = File("../README.md")
        if (readme.exists()) assertFalse(word.containsMatchIn(readme.readText()))
    }

    @Test fun theArcadeRevealStillBelongsToTheArcadeNotToTheNewGame() {
        // A second unlock uses the same reveal and pulse as the first: it is still the arcade page that appears.
        ArcadeReveal.pending = false
        assertFalse(ArcadeReveal.take())
        ArcadeReveal.pending = true
        assertTrue(ArcadeReveal.take())
        assertFalse(ArcadeReveal.take())
    }
}
