package com.brightblu.parc.core.data.timers

/**
 * T101: plain-text storage for [TimersState]. Hostile or damaged values are dropped or clamped,
 * never thrown: a timer line is `id,total,status,endAt,remaining`, the stopwatch is
 * `running,startedAt,accumulated|lap,lap,...`.
 */
object TimerCodec {
    private const val MaxNoteId = 80

    fun encodeBook(book: TimerBook): String =
        book.timers.joinToString(";") {
            val base = "${it.id},${it.totalMs},${it.status.name},${it.endAtMs},${it.remainingMs}"
            // T183: a timer started from a note carries two more fields; ordinary timers keep the old five.
            if (it.label.isEmpty() && it.noteId.isEmpty()) base else "$base,${escape(it.label)},${escape(it.noteId)}"
        }

    private fun escape(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
    private fun unescape(value: String): String = try { java.net.URLDecoder.decode(value, "UTF-8") } catch (_: Exception) { "" }

    fun decodeBook(raw: String?): TimerBook {
        if (raw.isNullOrBlank()) return TimerBook()
        val seen = HashSet<Int>()
        val timers = raw.split(';').mapNotNull { line ->
            val p = line.split(',')
            if (p.size != 5 && p.size != 7) return@mapNotNull null
            val id = p[0].toIntOrNull() ?: return@mapNotNull null
            val total = p[1].toLongOrNull() ?: return@mapNotNull null
            val status = TimerStatus.entries.firstOrNull { it.name == p[2] } ?: return@mapNotNull null
            val end = p[3].toLongOrNull() ?: return@mapNotNull null
            val remaining = p[4].toLongOrNull() ?: return@mapNotNull null
            if (id !in 1..TimerRules.MaxTimers || total !in TimerRules.MinMs..TimerRules.MaxMs || !seen.add(id)) return@mapNotNull null
            if (status == TimerStatus.Running && end <= 0L) return@mapNotNull null
            CountdownTimer(id, total, status, end.coerceAtLeast(0L), remaining.coerceIn(0L, total),
                label = if (p.size == 7) unescape(p[5]).take(TimerRules.MaxLabel) else "",
                noteId = if (p.size == 7) unescape(p[6]).take(MaxNoteId) else "")
        }
        return TimerBook(timers.sortedBy { it.id })
    }

    fun encodeStopwatch(sw: Stopwatch): String =
        "${if (sw.running) 1 else 0},${sw.startedAtMs},${sw.accumulatedMs}|${sw.laps.joinToString(",")}"

    fun decodeStopwatch(raw: String?): Stopwatch {
        if (raw.isNullOrBlank()) return Stopwatch()
        val halves = raw.split('|')
        val head = halves[0].split(',')
        if (head.size != 3) return Stopwatch()
        val running = head[0] == "1"
        val started = head[1].toLongOrNull() ?: return Stopwatch()
        val accumulated = head[2].toLongOrNull() ?: return Stopwatch()
        if (accumulated < 0L || (running && started <= 0L)) return Stopwatch()
        val laps = halves.getOrNull(1).orEmpty().split(',').mapNotNull { it.toLongOrNull() }
            .filter { it > 0L }.take(TimerRules.MaxLaps)
        return Stopwatch(running, if (running) started else 0L, accumulated, laps)
    }
}
