package com.brightblu.parc.core.data.timers

/** T101: where a countdown timer is. [Finished] is stored once the end time has passed. */
enum class TimerStatus { Idle, Running, Paused, Finished }

/**
 * One countdown timer. Times are wall-clock epoch milliseconds so the end time survives the process
 * being killed and matches what `AlarmManager.setAlarmClock` is given. [endAtMs] is only meaningful
 * while [status] is Running; [remainingMs] while Idle or Paused.
 */
data class CountdownTimer(
    val id: Int,
    val totalMs: Long,
    val status: TimerStatus,
    val endAtMs: Long = 0L,
    val remainingMs: Long = totalMs,
) {
    /** What the page shows: a running timer whose end has passed counts as finished even before the receiver ran. */
    fun effective(nowMs: Long): TimerStatus =
        if (status == TimerStatus.Running && endAtMs <= nowMs) TimerStatus.Finished else status

    /** Milliseconds left at [nowMs]; zero when finished. */
    fun left(nowMs: Long): Long = when (effective(nowMs)) {
        TimerStatus.Running -> (endAtMs - nowMs).coerceAtLeast(0L)
        TimerStatus.Finished -> 0L
        else -> remainingMs.coerceIn(0L, totalMs)
    }
}

/** The set of timers, at most [TimerRules.MaxTimers], ids unique in 1..MaxTimers. Immutable. */
data class TimerBook(val timers: List<CountdownTimer> = emptyList()) {
    val isFull: Boolean get() = timers.size >= TimerRules.MaxTimers
    fun find(id: Int): CountdownTimer? = timers.firstOrNull { it.id == id }
    private fun replace(id: Int, change: (CountdownTimer) -> CountdownTimer): TimerBook =
        TimerBook(timers.map { if (it.id == id) change(it) else it })

    /** Adds a running timer; null when the book is full or [totalMs] is outside the allowed range. */
    fun start(totalMs: Long, nowMs: Long): TimerBook? {
        if (isFull || totalMs !in TimerRules.MinMs..TimerRules.MaxMs) return null
        val id = (1..TimerRules.MaxTimers).first { slot -> timers.none { it.id == slot } }
        val added = CountdownTimer(id, totalMs, TimerStatus.Running, endAtMs = nowMs + totalMs)
        return TimerBook((timers + added).sortedBy { it.id })
    }

    fun pause(id: Int, nowMs: Long): TimerBook = replace(id) {
        if (it.status != TimerStatus.Running) it
        else if (it.endAtMs <= nowMs) it.copy(status = TimerStatus.Finished, remainingMs = 0L, endAtMs = 0L)
        else it.copy(status = TimerStatus.Paused, remainingMs = it.endAtMs - nowMs, endAtMs = 0L)
    }

    /** Starts an Idle timer or continues a Paused one. */
    fun resume(id: Int, nowMs: Long): TimerBook = replace(id) {
        if (it.status != TimerStatus.Paused && it.status != TimerStatus.Idle) it
        else {
            val left = it.remainingMs.coerceIn(1L, it.totalMs)
            it.copy(status = TimerStatus.Running, endAtMs = nowMs + left, remainingMs = left)
        }
    }

    /** Back to the full duration, not running. */
    fun reset(id: Int): TimerBook = replace(id) { it.copy(status = TimerStatus.Idle, endAtMs = 0L, remainingMs = it.totalMs) }

    fun delete(id: Int): TimerBook = TimerBook(timers.filterNot { it.id == id })

    /** Marks every running timer whose end time has passed as Finished. */
    fun settle(nowMs: Long): TimerBook {
        val due: (CountdownTimer) -> Boolean = { it.status == TimerStatus.Running && it.endAtMs <= nowMs }
        if (timers.none(due)) return this
        return TimerBook(timers.map { if (due(it)) it.copy(status = TimerStatus.Finished, remainingMs = 0L, endAtMs = 0L) else it })
    }

    /** Ids that are Finished here but were not in [before]: the ones to ring. */
    fun newlyFinished(before: TimerBook): List<Int> =
        timers.filter { it.status == TimerStatus.Finished && before.find(it.id)?.status != TimerStatus.Finished }.map { it.id }
}

/** Stopwatch with laps. [startedAtMs] is wall-clock time of the last start; [accumulatedMs] is time before it. */
data class Stopwatch(
    val running: Boolean = false,
    val startedAtMs: Long = 0L,
    val accumulatedMs: Long = 0L,
    /** Cumulative elapsed time at each lap press, oldest first. */
    val laps: List<Long> = emptyList(),
) {
    fun elapsed(nowMs: Long): Long = (accumulatedMs + if (running) (nowMs - startedAtMs).coerceAtLeast(0L) else 0L).coerceAtLeast(0L)

    fun start(nowMs: Long): Stopwatch = if (running) this else copy(running = true, startedAtMs = nowMs)

    fun stop(nowMs: Long): Stopwatch = if (!running) this else copy(running = false, accumulatedMs = elapsed(nowMs), startedAtMs = 0L)

    /** Records a lap while running (a lap at zero time is ignored). */
    fun lap(nowMs: Long): Stopwatch {
        val at = elapsed(nowMs)
        if (!running || at <= 0L || laps.size >= TimerRules.MaxLaps) return this
        return copy(laps = laps + at)
    }

    fun reset(): Stopwatch = Stopwatch()

    /** Split of lap [index] (0-based): its time minus the previous lap. */
    fun split(index: Int): Long = if (index !in laps.indices) 0L else laps[index] - (if (index == 0) 0L else laps[index - 1])

    val isPristine: Boolean get() = !running && accumulatedMs == 0L && laps.isEmpty()
}

/** Everything the Timers page persists. */
data class TimersState(val book: TimerBook = TimerBook(), val stopwatch: Stopwatch = Stopwatch())

object TimerRules {
    const val MaxTimers = 5
    const val MinMs = 1_000L
    const val MaxMs = 24L * 3_600_000L - 1_000L
    const val MaxLaps = 99
    /** A notification stops ringing on its own after this long. */
    const val RingMs = 60_000L
    val PresetMinutes = listOf(1, 5, 10, 15, 30)

    /** A custom duration from the picker's three fields, clamped into the allowed range. */
    fun custom(hours: Int, minutes: Int, seconds: Int): Long =
        ((hours.coerceIn(0, 23) * 3600L + minutes.coerceIn(0, 59) * 60L + seconds.coerceIn(0, 59)) * 1000L).coerceIn(MinMs, MaxMs)

    /** Whether to ask for the notification permission: the first time a timer starts and not yet asked. */
    fun shouldAskNotifications(granted: Boolean, alreadyAsked: Boolean): Boolean = !granted && !alreadyAsked

    /** The earliest end among running timers, or null. */
    fun nextEnd(book: TimerBook): Long? = book.timers.filter { it.status == TimerStatus.Running }.minOfOrNull { it.endAtMs }
}

/** One line telling the user what is limited, or null when nothing is. */
object TimerNotices {
    fun of(notificationsAllowed: Boolean, exactAlarms: Boolean): String? = when {
        !exactAlarms && !notificationsAllowed -> "Alarms may be late and notifications are off, so a finished timer shows only here"
        !exactAlarms -> "Exact alarms are unavailable, so timers may ring a little late"
        !notificationsAllowed -> "Notifications are off, so a finished timer shows only here"
        else -> null
    }
}
