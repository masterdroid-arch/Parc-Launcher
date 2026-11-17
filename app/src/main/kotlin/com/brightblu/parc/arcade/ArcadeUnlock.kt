package com.brightblu.parc.arcade

import com.brightblu.parc.core.data.ledger.DayLedger
import java.time.LocalDate
import kotlin.math.abs

/** T200: the pure side of unlocking. Nothing here knows about Android. */
internal object ArcadeUnlockLogic {
    /** Unlocks every game whose rule is [ruleId]. Returns the same state when there is nothing new. */
    fun unlockByRule(state: ArcadeState, ruleId: String): ArcadeState =
        state.unlock(ArcadeCatalog.lockedBy(ruleId).map { it.id })

    /** The games [ruleId] would newly unlock for [state]. */
    fun newlyUnlocked(before: ArcadeState, after: ArcadeState): List<String> =
        after.unlocked.filter { it !in before.unlocked && it in ArcadeCatalog.ids }.sorted()

    /** True when some game with rule [ruleId] is still locked, so a detector for it has work to do. */
    fun pending(state: ArcadeState, ruleId: String): Boolean = ArcadeCatalog.lockedBy(ruleId).any { !state.isUnlocked(it.id) }

    /**
     * The earned way for [ArcadeRules.CalmDayOrArcSpin]. The first look starts the watch (`since` = today); after that, the first full
     * calendar day after `since` that ended under the user's own average unlocks the rule.
     */
    fun checkEarned(state: ArcadeState, ledger: DayLedger, today: LocalDate): ArcadeState {
        if (!pending(state, ArcadeRules.CalmDayOrArcSpin)) return state
        val since = state.since ?: return state.copy(since = today)
        return if (ArcadeEarned.qualifyingDay(ledger, since, today) != null) unlockByRule(state, ArcadeRules.CalmDayOrArcSpin) else state
    }

    /**
     * The earned way for [ArcadeRules.StepGoalOrMoonTap]. It fires on the first recorded day, up to [today], whose step count
     * reaches the goal the user set on the Steps page. A goal that is off (null) never unlocks it. Days after [today] are
     * ignored, so a clock change cannot unlock it from a future record.
     */
    fun checkStepGoal(state: ArcadeState, ledger: DayLedger, today: LocalDate, goal: Int?): ArcadeState {
        if (!pending(state, ArcadeRules.StepGoalOrMoonTap)) return state
        if (goal == null || goal <= 0 || !stepGoalReached(ledger, today, goal)) return state
        return unlockByRule(state, ArcadeRules.StepGoalOrMoonTap)
    }

    /** True when any recorded day up to [today] has at least [goal] steps. */
    fun stepGoalReached(ledger: DayLedger, today: LocalDate, goal: Int): Boolean =
        goal > 0 && ledger.days.values.any { !it.date.isAfter(today) && (it.steps ?: 0) >= goal }
}

/** T211: seven taps on the Sky page's moon within [windowMs], independent of where the taps land. */
internal class MoonTapDetector(val taps: Int = 7, val windowMs: Long = 5_000L) {
    private val times = LongArray(taps.coerceAtLeast(1))
    private var count = 0

    fun reset() { count = 0 }

    /** [nowMs] is the monotonic uptime. Returns true once, on the tap that completes a fast enough group. */
    fun feed(nowMs: Long): Boolean {
        if (count > 0 && nowMs < times[count - 1]) count = 0
        if (count == times.size) {
            // Slide the window: keep the newest taps, drop the oldest. No allocation.
            times.copyInto(times, 0, 1, count)
            count--
        }
        times[count++] = nowMs
        if (count == times.size && times[count - 1] - times[0] <= windowMs) {
            count = 0
            return true
        }
        return false
    }
}

/**
 * T200: the earned unlock. A day qualifies when it is closed, is a full day after the watch began and before today, has recorded
 * screen-on time, the 14 days before it hold at least 7 recorded days (the user's own average), and its screen time is strictly
 * below that average. Days before the watch began never count, so a backfilled history cannot unlock the arcade on day one.
 */
internal object ArcadeEarned {
    const val HistoryDays = 14L
    const val MinimumHistory = 7

    /** Average screen-on millis over the 14 days before [day], or null with fewer than 7 recorded days. */
    fun average(ledger: DayLedger, day: LocalDate): Double? {
        val samples = ledger.range(day.minusDays(HistoryDays), day.minusDays(1)).mapNotNull { it.screenOnMillis }.filter { it >= 0 }
        return if (samples.size < MinimumHistory) null else samples.map(Long::toDouble).average()
    }

    fun qualifies(ledger: DayLedger, day: LocalDate): Boolean {
        val d = ledger.day(day) ?: return false
        val screen = d.screenOnMillis ?: return false
        if (!d.closed) return false
        val average = average(ledger, day) ?: return false
        return screen.toDouble() < average
    }

    /** The earliest qualifying day strictly after [since] and strictly before [today], or null. */
    fun qualifyingDay(ledger: DayLedger, since: LocalDate, today: LocalDate): LocalDate? =
        ledger.range(since.plusDays(1), today.minusDays(1)).firstOrNull { qualifies(ledger, it.date) }?.date
}

/** T200: tuning of the arc-turn shortcut. A turn is one full circle of an arc's items (turns are slots moved divided by the item count). */
internal data class ArcSpinRules(
    val forwardTurns: Float = 3f,
    val backTurns: Float = 1f,
    /** Whole gesture, from the first move of the attempt to the last. */
    val windowMs: Long = 20_000L,
    /** Longest pause between two moves (a lifted finger) before the attempt starts again. */
    val gapMs: Long = 1_500L,
    /** A step against the chosen direction smaller than this is finger wobble; a bigger one restarts the attempt. */
    val jitterTurns: Float = 0.25f,
    /** Movement before a direction is chosen. */
    val startTurns: Float = 0.05f,
)

/**
 * T200: the shortcut detector, fed with rotation deltas of the Home arcs. Three full turns in one direction, then one full turn back,
 * all within 20 s and with no pause of more than 1.5 s between moves, on one arc. Pure: time comes from the caller. [feed] returns true
 * once, when the gesture completes, and the detector starts over.
 */
internal class ArcSpinDetector(private val rules: ArcSpinRules = ArcSpinRules()) {
    private var arc: String? = null
    private var position = 0f
    private var direction = 0
    private var extreme = 0f
    private var startMs = 0L
    private var lastMs = 0L
    private var forwardDone = false

    fun reset() { arc = null; position = 0f; direction = 0; extreme = 0f; startMs = 0L; lastMs = 0L; forwardDone = false }

    private fun restart(arcId: String, now: Long) {
        arc = arcId; position = 0f; direction = 0; extreme = 0f; startMs = now; lastMs = now; forwardDone = false
    }

    fun feed(arcId: String, turns: Float, uptimeMs: Long): Boolean {
        if (!turns.isFinite() || turns == 0f) return false
        if (arc != arcId || uptimeMs - lastMs > rules.gapMs || uptimeMs < lastMs || uptimeMs - startMs > rules.windowMs) restart(arcId, uptimeMs)
        lastMs = uptimeMs
        position += turns
        if (direction == 0) {
            if (abs(position) < rules.startTurns) return false
            direction = if (position > 0f) 1 else -1
            extreme = abs(position)
            return false
        }
        val progress = position * direction
        if (progress > extreme) extreme = progress
        if (!forwardDone) {
            if (extreme - progress > rules.jitterTurns) {
                // Turned back too early: this is the start of a new attempt in the other direction, from here.
                restart(arcId, uptimeMs)
                return false
            }
            if (extreme >= rules.forwardTurns) forwardDone = true
            return false
        }
        if (extreme - progress >= rules.backTurns) { reset(); return true }
        return false
    }
}
