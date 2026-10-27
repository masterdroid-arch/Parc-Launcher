package com.brightblu.parc

import com.brightblu.parc.core.data.timers.*

/** Extend only a live countdown, preserving identity and the scheduler's absolute deadline. */
internal fun TimerBook.addMinute(id: Int, now: Long): TimerBook = TimerBook(timers.map { timer ->
    if (timer.id != id || timer.effective(now) != TimerStatus.Running) timer
    else {
        val added = minOf(60_000L, (TimerRules.MaxMs - timer.totalMs).coerceAtLeast(0L))
        timer.copy(totalMs = timer.totalMs + added, endAtMs = timer.endAtMs + added)
    }
})
internal fun timerRemainingMs(timer: CountdownTimer, now: Long): Long =
    timer.left(now).coerceAtMost(timer.totalMs.coerceAtLeast(0L))

internal fun timerFraction(timer: CountdownTimer, now: Long): Float =
    if (timer.totalMs <= 0L) 0f else (timerRemainingMs(timer, now).toFloat() / timer.totalMs).coerceIn(0f, 1f)
internal fun timerLapsShown(size: HubSize): Int = when (size) {
    HubSize.Compact -> 0
    HubSize.Standard -> 2
    HubSize.Tall -> 6
}
