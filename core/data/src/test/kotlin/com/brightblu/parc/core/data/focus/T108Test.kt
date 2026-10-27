package com.brightblu.parc.core.data.focus

import com.brightblu.parc.core.data.FakePreferencesStore
import com.brightblu.parc.core.data.backup.BackupSpecs
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class T108Test {
    private val utc = ZoneId.of("UTC")
    private fun at(s: String) = Instant.parse(s).toEpochMilli()
    @Test fun rfc6238Sha1Vectors() {
        val secret = FocusTotp.encode("12345678901234567890".toByteArray())
        listOf(59L to "94287082", 1111111109L to "07081804", 1111111111L to "14050471",
            1234567890L to "89005924", 2000000000L to "69279037", 20000000000L to "65353130").forEach { (t, expected) ->
            assertEquals(expected, FocusTotp.code(secret, t * 1000, 8))
        }
        val code = FocusTotp.code(secret, 59_000)
        assertTrue(FocusTotp.valid(secret, code, 59_000))
        assertFalse(FocusTotp.valid(secret, code, 60_000))
        assertFalse(FocusTotp.valid("invalid!", code, 59_000))
        assertFalse(FocusTotp.valid(secret, "", 59_000))
    }
    @Test fun localMidnightWeekAndDstResetOnlyPeriodCounts() {
        val zone = ZoneId.of("Europe/Lisbon")
        val times = listOf(
            Triple("2026-03-28T23:59:59Z", "2026-03-29T00:00:00Z", PassPeriod.Day),
            Triple("2026-10-24T22:59:59Z", "2026-10-24T23:00:00Z", PassPeriod.Day),
            Triple("2026-10-25T23:59:59Z", "2026-10-26T00:00:00Z", PassPeriod.Week))
        times.forEach { (before, after, period) ->
            val mode = FocusMode("Study", friction = FocusFriction(period = period), ledger = FocusLedger(listOf(FocusUse(at(before), true))))
            assertEquals(1, FocusFrictionRules.passCount(mode, at(before), zone))
            assertEquals(0, FocusFrictionRules.passCount(mode, at(after), zone))
            assertTrue(FocusFrictionRules.cooldownUntil(mode) > at(after))
        }
        val mode = FocusMode("Study", ledger = FocusLedger(listOf(FocusUse(at("2026-10-25T00:30:00Z"), true))))
        assertEquals(1, FocusFrictionRules.passCount(mode, at("2026-10-25T01:30:00Z"), zone))
        assertEquals(23 * 60 * 60_000L, FocusFrictionRules.periodStart(at("2026-03-30T12:00:00Z"), PassPeriod.Day, zone) -
            FocusFrictionRules.periodStart(at("2026-03-29T12:00:00Z"), PassPeriod.Day, zone))
    }
    @Test fun delaysLimitsAndCurrentCodeOverride() {
        val now = at("2026-10-19T12:00:00Z")
        val base = FocusMode("Study")
        assertEquals(30_000L, FocusFrictionRules.delayMillis(base, now, utc))
        val one = base.copy(ledger = FocusLedger(listOf(FocusUse(now - 1, true))))
        assertEquals(120_000L, FocusFrictionRules.delayMillis(one, now, utc))
        val two = one.copy(ledger = FocusLedger(one.ledger.uses + FocusUse(now, true)))
        assertEquals(300_000L, FocusFrictionRules.delayMillis(two, now, utc))
        assertTrue(FocusFrictionRules.limitReached(two, now, utc))
        assertEquals(FocusChallenge.Accountability, FocusFrictionRules.challenge(two, now, utc))
        assertEquals("Pass 2 of 2 used", FocusFrictionRules.status(two, now, utc))
        assertEquals(FocusChallenge.Sentence, FocusFrictionRules.challenge(base.copy(strict = true), now, utc))
    }
    @Test fun sentenceEntropyArithmeticAndTypedEdits() {
        val samples = (1..100).map { FocusChallenges.sentence() }
        assertEquals(100, samples.toSet().size)
        samples.forEach { assertEquals(18, it.split(' ').size); assertTrue(it.length in 90..170) }
        assertEquals(5, FocusChallenges.arithmetic().size)
        FocusChallenges.arithmetic().forEach { assertEquals(if (it.multiply) it.left * it.right else it.left + it.right, it.answer) }
        assertTrue(FocusChallenges.acceptsInsertion(0))
        assertTrue(FocusChallenges.acceptsInsertion(1))
        assertFalse(FocusChallenges.acceptsInsertion(2))
        assertFalse(FocusChallenges.acceptsInsertion(120))
    }
    @Test fun oldJsonKeepsEveryOptionAndAddsSafeDefaultsAndBacksUpNewFields() {
        val legacy = FocusJson.decodeModes("""[{"name":"Study","strict":true,"confirmationPhrase":"legacy","pauseBeforeOpen":true,"hiddenPackages":["app"],"priority":4}]""").single()
        assertTrue(legacy.strict); assertEquals("legacy", legacy.confirmationPhrase); assertTrue(legacy.pauseBeforeOpen)
        assertEquals(2, legacy.friction.passLimit); assertEquals(4, legacy.priority)
        val mode = legacy.copy(friction = FocusFriction(15, 5, PassPeriod.Week, FocusChallenge.Accountability, FocusTotp.newSecret()),
            ledger = FocusLedger(listOf(FocusUse(1234, true))))
        assertEquals(listOf(mode), FocusJson.decodeModes(FocusJson.encodeModes(listOf(mode))))
        assertNotNull(BackupSpecs.focus.key(BackupSpecs.FocusModesKey))
    }
    @Test fun persistedCountsCooldownCancelDelayAndNoOldBypass() = runTest {
        val prefs = FocusPrefs(FakePreferencesStore())
        val start = at("2026-10-19T12:00:00Z")
        val repo = FocusRepository(prefs, backgroundScope, { start + testScheduler.currentTime }, { utc })
        repo.save(FocusMode("Study")); runCurrent(); repo.quickStart("Study", 60); runCurrent()
        assertFalse(repo.breakPass()); assertFalse(repo.setManualOff("Study"))
        val cancelled = repo.beginExit(FocusExit.Pass)!!
        assertFalse(repo.completeExit(cancelled))
        repo.cancelExit(cancelled); advanceTimeBy(30_000); runCurrent()
        assertFalse(repo.completeExit(cancelled))
        val pass = repo.beginExit(FocusExit.Pass)!!
        advanceTimeBy(30_000); runCurrent(); assertTrue(repo.completeExit(pass)); runCurrent()
        assertNull(repo.state.value.active)
        assertFalse(repo.completeExit(pass))
        advanceTimeBy(300_000); runCurrent()
        val restarted = FocusRepository(prefs, backgroundScope, { start + testScheduler.currentTime }, { utc })
        runCurrent(); assertEquals(1, FocusFrictionRules.passCount(restarted.modes.value.single(), start + testScheduler.currentTime, utc))
        assertNull(restarted.beginExit(FocusExit.Pass))
        advanceTimeBy(300_000); runCurrent()
        val second = restarted.beginExit(FocusExit.Pass)!!
        assertEquals(120_000L, restarted.exitRemaining(second))
        advanceTimeBy(120_000); runCurrent(); assertTrue(restarted.completeExit(second)); runCurrent()
        advanceTimeBy(600_000); runCurrent()
        assertNull(restarted.beginExit(FocusExit.End)); assertNull(restarted.beginExit(FocusExit.Pass))
        assertEquals(2, restarted.modes.value.single().ledger.uses.size)
    }
    @Test fun holdCannotAccumulateAcrossReleasesAndStrictSurvivesPass() = runTest {
        val start = at("2026-10-19T12:00:00Z")
        val repo = FocusRepository(FocusPrefs(FakePreferencesStore()), backgroundScope, { start + testScheduler.currentTime }, { utc })
        repo.save(FocusMode("Study", strict = true, friction = FocusFriction(challenge = FocusChallenge.Hold)))
        runCurrent(); repo.quickStart("Study", 60); runCurrent()
        val ticket = repo.beginExit(FocusExit.Pass)!!
        repo.hold(ticket, true); advanceTimeBy(30_000); runCurrent(); assertFalse(repo.completeExit(ticket))
        repo.hold(ticket, true); advanceTimeBy(19_000); repo.hold(ticket, false)
        assertFalse(repo.completeExit(ticket)); repo.hold(ticket, true)
        advanceTimeBy(20_000); runCurrent(); assertTrue(repo.completeExit(ticket)); runCurrent()
        assertTrue(repo.state.value.strictSession)
        assertFalse(repo.save(FocusMode("Other"))); repo.quickStart("Other"); repo.delete("Study")
        assertEquals(1, repo.modes.value.size)
    }
    @Test fun codePermitsExitBeyondLimitButIncorrectCodeNeverDoes() = runTest {
        val start = at("2026-10-19T12:00:00Z"); val secret = FocusTotp.newSecret()
        val prefs = FocusPrefs(FakePreferencesStore())
        val repo = FocusRepository(prefs, backgroundScope, { start + testScheduler.currentTime }, { utc })
        repo.save(FocusMode("Study", friction = FocusFriction(passLimit = 1, accountabilitySecret = secret),
            ledger = FocusLedger(listOf(FocusUse(start - 600_000, true)))))
        runCurrent(); repo.quickStart("Study", 60); runCurrent()
        val ticket = repo.beginExit(FocusExit.End)!!
        assertEquals(FocusChallenge.Accountability, ticket.challenge)
        advanceTimeBy(120_000); runCurrent()
        assertFalse(repo.completeExit(ticket, "bad"))
        assertTrue(repo.completeExit(ticket, FocusTotp.code(secret, start + testScheduler.currentTime)))
    }
    @Test fun arithmeticAndSentenceCheckExactAnswersAfterDelay() = runTest {
        val start = at("2026-10-19T12:00:00Z")
        for (challenge in listOf(FocusChallenge.Arithmetic, FocusChallenge.Sentence)) {
            val repo = FocusRepository(FocusPrefs(FakePreferencesStore()), backgroundScope, { start + testScheduler.currentTime }, { utc })
            repo.save(FocusMode("Study", friction = FocusFriction(challenge = challenge)))
            runCurrent(); repo.quickStart("Study", 60); runCurrent()
            val ticket = repo.beginExit(FocusExit.End)!!
            advanceTimeBy(30_000); runCurrent()
            assertFalse(repo.completeExit(ticket, "wrong", List(5) { "wrong" }))
            assertTrue(repo.completeExit(ticket, ticket.sentence, ticket.problems.map { it.answer.toString() }))
            runCurrent(); assertNull(repo.state.value.active)
        }
    }
    @Test fun summaryRetainsEveryPassAcrossRestartDuringPass() = runTest {
        val start = at("2026-10-19T23:55:00Z")
        val store = FocusPrefs(FakePreferencesStore())
        val repo = FocusRepository(store, backgroundScope, { start + testScheduler.currentTime }, { utc })
        repo.save(FocusMode("Study")); runCurrent(); repo.quickStart("Study", 30); runCurrent()
        val ticket = repo.beginExit(FocusExit.Pass)!!
        advanceTimeBy(30_000); runCurrent(); assertTrue(repo.completeExit(ticket)); runCurrent()
        val restarted = FocusRepository(store, backgroundScope, { start + testScheduler.currentTime }, { utc })
        runCurrent(); advanceTimeBy(30 * 60_000); runCurrent()
        assertEquals(listOf(start + 30_000), restarted.summary.value!!.emergencyPasses)
    }
    @Test fun paidStubIsHiddenAndCannotGrantAnExit() = runTest {
        val start = at("2026-10-19T12:00:00Z")
        val repo = FocusRepository(FocusPrefs(FakePreferencesStore()), backgroundScope, { start + testScheduler.currentTime }, { utc })
        assertFalse(repo.paidUnlockAvailable)
        assertFalse(PaidUnlock().authorize("Study"))
        repo.save(FocusMode("Study")); runCurrent(); repo.quickStart("Study", 30); runCurrent()
        assertNull(repo.beginExit(FocusExit.End, paid = true))
    }

    @Test fun naturalEndDuringPassClearsThePassStatusAndLogsIt() = runTest {
        val start = at("2026-10-19T12:00:00Z")
        val repo = FocusRepository(FocusPrefs(FakePreferencesStore()), backgroundScope, { start + testScheduler.currentTime }, { utc })
        repo.save(FocusMode("Study")); runCurrent(); repo.quickStart("Study", 1); runCurrent()
        val ticket = repo.beginExit(FocusExit.Pass)!!
        advanceTimeBy(30_000); runCurrent(); assertTrue(repo.completeExit(ticket)); runCurrent()
        assertNotNull(repo.state.value.breakUntilMillis)
        advanceTimeBy(30_000); runCurrent()
        assertNull(repo.state.value.active); assertNull(repo.state.value.breakUntilMillis)
        assertEquals(listOf(start + 30_000), repo.summary.value!!.emergencyPasses)
    }

}
