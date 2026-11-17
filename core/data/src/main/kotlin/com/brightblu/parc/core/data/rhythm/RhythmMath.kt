package com.brightblu.parc.core.data.rhythm

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the phone's usage events say happened to the screen (T188), already reduced from `UsageEvents`. */
data class RhythmEvent(val kind: Kind, val atMillis: Long) {
    enum class Kind { ScreenOn, ScreenOff, KeyguardHidden }
}

/** A stretch of the day with the screen on, in epoch milliseconds, [startMillis] inclusive and [endMillis] exclusive. */
data class OnSegment(val startMillis: Long, val endMillis: Long) {
    val lengthMillis: Long get() = endMillis - startMillis
}

/** Everything the Rhythm page records for one day. */
data class RhythmDay(
    val segments: List<OnSegment>,
    val pickups: Int,
    /** Minute of the local day (0..1439) of the first pickup, or null when there was none. */
    val firstPickupMinute: Int?,
    val screenOnMillis: Long,
    /** The longest screen-off stretch inside the waking-hours window, or null when the window has not started yet. */
    val longestFreeMillis: Long?,
    /** The day's segments as 288 five-minute bins. */
    val bins: BooleanArray,
) {
    override fun equals(other: Any?): Boolean = other is RhythmDay && segments == other.segments && pickups == other.pickups &&
        firstPickupMinute == other.firstPickupMinute && screenOnMillis == other.screenOnMillis &&
        longestFreeMillis == other.longestFreeMillis && bins.contentEquals(other.bins)

    override fun hashCode(): Int = segments.hashCode() * 31 + pickups
}

/**
 * The hours that count for the longest phone-free stretch, as whole hours of the day: [startHour] 0..23 up to
 * [endHour] 1..24 (24 means midnight at the end of the day). Default 06:00 to 24:00, so sleep does not win every day.
 */
data class RhythmOptions(val startHour: Int = DefaultStart, val endHour: Int = DefaultEnd) {
    fun encode(): String = "$startHour,$endHour"

    fun withStart(hour: Int) = copy(startHour = hour.coerceIn(0, endHour - MinSpan))
    fun withEnd(hour: Int) = copy(endHour = hour.coerceIn(startHour + MinSpan, 24))

    companion object {
        const val DefaultStart = 6
        const val DefaultEnd = 24
        /** The window is never shorter than this many hours. */
        const val MinSpan = 3

        /** Old, empty, garbled or out-of-range text reads as the defaults. */
        fun decode(raw: String?): RhythmOptions {
            val parts = raw?.split(',') ?: return RhythmOptions()
            val start = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val end = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (start == null || end == null || start !in 0..23 || end !in 1..24 || end - start < MinSpan) return RhythmOptions()
            return RhythmOptions(start, end)
        }
    }
}

/** A 24-hour ring is 288 bins of five minutes, midnight first. */
object RhythmBins {
    const val Count = 288
    const val BinMillis = 5L * 60L * 1_000L
    /** A bin is lit when the screen was on for at least this long inside it. */
    const val MinLitMillis = 60L * 1_000L

    /** The bins of the day starting at [dayStartMillis]; each is lit when the screen was on long enough inside it. */
    fun of(segments: List<OnSegment>, dayStartMillis: Long): BooleanArray {
        val on = LongArray(Count)
        for (s in segments) {
            val first = ((s.startMillis - dayStartMillis) / BinMillis).coerceIn(0L, Count - 1L).toInt()
            val last = ((s.endMillis - 1L - dayStartMillis) / BinMillis).coerceIn(0L, Count - 1L).toInt()
            if (s.endMillis <= dayStartMillis || s.startMillis >= dayStartMillis + Count * BinMillis) continue
            for (i in first..last) {
                val from = maxOf(s.startMillis, dayStartMillis + i * BinMillis)
                val to = minOf(s.endMillis, dayStartMillis + (i + 1) * BinMillis)
                if (to > from) on[i] += to - from
            }
        }
        return BooleanArray(Count) { on[it] >= MinLitMillis }
    }

    /** 72 hex characters, four bins per character, the first bin in the top bit of the first character. */
    fun encode(bins: BooleanArray): String {
        require(bins.size == Count)
        val sb = StringBuilder(Count / 4)
        for (g in 0 until Count / 4) {
            var v = 0
            for (k in 0 until 4) v = v shl 1 or (if (bins[g * 4 + k]) 1 else 0)
            sb.append("0123456789abcdef"[v])
        }
        return sb.toString()
    }

    /** The bins of an encoded day, or null for anything that is not exactly what [encode] writes. */
    fun decode(text: String?): BooleanArray? {
        if (text == null || text.length != Count / 4) return null
        val out = BooleanArray(Count)
        for ((g, c) in text.withIndex()) {
            val v = "0123456789abcdef".indexOf(c)
            if (v < 0) return null
            for (k in 0 until 4) out[g * 4 + k] = (v shr (3 - k)) and 1 == 1
        }
        return out
    }

    /** Runs of lit bins as inclusive-start, exclusive-end bin indexes, in order. */
    fun runs(bins: BooleanArray): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = -1
        for (i in bins.indices) {
            if (bins[i] && start < 0) start = i
            if (!bins[i] && start >= 0) { out += start until i; start = -1 }
        }
        if (start >= 0) out += start until bins.size
        return out
    }

    /** The "HH:mm" of a bin's start. */
    fun time(bin: Int): String = clock(bin * 5)
    fun clock(minuteOfDay: Int): String = String.format(java.util.Locale.ROOT, "%02d:%02d", (minuteOfDay / 60) % 24, minuteOfDay % 60)
}

/** Framework-free arithmetic: usage events in, segments, pickups and the longest phone-free stretch out. */
object RhythmMath {
    /**
     * The screen-on segments inside [windowStart, windowEnd), clipped to the window and to [now] (the
     * future is not on). The state at the window start comes from the last screen event before it, so a
     * session across midnight is cut in two, one half per day. An on without an off runs to the end of
     * the window or to [now], whichever is first (this is also how "screen on right now" reads); an off
     * with no on before it, and a second on while already on, change nothing.
     */
    fun segments(events: List<RhythmEvent>, windowStart: Long, windowEnd: Long, now: Long): List<OnSegment> {
        val end = minOf(windowEnd, now)
        if (end <= windowStart) return emptyList()
        var onSince: Long? = null
        val out = ArrayList<OnSegment>()
        for (e in events.filter { it.kind != RhythmEvent.Kind.KeyguardHidden }.sortedBy { it.atMillis }) {
            val on = e.kind == RhythmEvent.Kind.ScreenOn
            if (e.atMillis <= windowStart) {
                onSince = if (on) windowStart else null
                continue
            }
            if (e.atMillis >= end) break
            if (on) {
                if (onSince == null) onSince = e.atMillis
            } else {
                onSince?.let { out += OnSegment(it, e.atMillis) }
                onSince = null
            }
        }
        onSince?.let { if (end > it) out += OnSegment(it, end) }
        return out.filter { it.lengthMillis > 0L }
    }

    /**
     * Unlocks in the window. Android's keyguard-hidden event is the unlock; a phone with no lock
     * screen sends none, so when [events] holds no keyguard event at all, every screen-on counts as a pickup.
     * Returns the count and the time of the first one.
     */
    fun pickups(events: List<RhythmEvent>, windowStart: Long, windowEnd: Long, now: Long): Pair<Int, Long?> {
        val end = minOf(windowEnd, now)
        val sorted = events.sortedBy { it.atMillis }
        val usesKeyguard = sorted.any { it.kind == RhythmEvent.Kind.KeyguardHidden }
        val wanted = if (usesKeyguard) RhythmEvent.Kind.KeyguardHidden else RhythmEvent.Kind.ScreenOn
        var count = 0
        var first: Long? = null
        var screenOn = false
        for (e in sorted) {
            if (e.kind == RhythmEvent.Kind.ScreenOn) {
                val wasOn = screenOn
                screenOn = true
                if (wanted == RhythmEvent.Kind.ScreenOn && !wasOn && e.atMillis > windowStart && e.atMillis < end) {
                    count++; if (first == null) first = e.atMillis
                }
            } else if (e.kind == RhythmEvent.Kind.ScreenOff) {
                screenOn = false
            } else if (wanted == RhythmEvent.Kind.KeyguardHidden && e.atMillis >= windowStart && e.atMillis < end) {
                count++; if (first == null) first = e.atMillis
            }
        }
        return count to first
    }

    /**
     * The longest stretch inside [from], [to] with no screen-on segment. Null when the window is empty
     * (it has not started yet). A stretch still running counts up to [to].
     */
    fun longestFree(segments: List<OnSegment>, from: Long, to: Long): Long? {
        if (to <= from) return null
        var best = 0L
        var cursor = from
        for (s in segments.sortedBy { it.startMillis }) {
            if (s.endMillis <= cursor) continue
            if (s.startMillis >= to) break
            best = maxOf(best, s.startMillis - cursor)
            cursor = maxOf(cursor, s.endMillis)
        }
        best = maxOf(best, to - cursor)
        return best.coerceAtLeast(0L)
    }

    /** The waking-hours window of [date] in [zone] as epoch milliseconds, cut at [now]. */
    fun freeWindow(date: LocalDate, zone: ZoneId, options: RhythmOptions, now: Long): Pair<Long, Long> {
        val start = date.atStartOfDay(zone).plusHours(options.startHour.toLong()).toInstant().toEpochMilli()
        val end = (if (options.endHour >= 24) date.plusDays(1).atStartOfDay(zone) else date.atStartOfDay(zone).plusHours(options.endHour.toLong()))
            .toInstant().toEpochMilli()
        return start to minOf(end, now)
    }

    /** The minute of the local day of [millis]. */
    fun minuteOfDay(millis: Long, zone: ZoneId): Int = Instant.ofEpochMilli(millis).atZone(zone).let { it.hour * 60 + it.minute }

    /** One whole day from the events. [now] cuts everything off, so a day in progress is only as long as it is. */
    fun day(events: List<RhythmEvent>, date: LocalDate, zone: ZoneId, options: RhythmOptions, now: Long): RhythmDay {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val segments = segments(events, start, end, now)
        val (pickups, first) = pickups(events, start, end, now)
        val (from, to) = freeWindow(date, zone, options, now)
        return RhythmDay(
            segments = segments,
            pickups = pickups,
            firstPickupMinute = first?.let { minuteOfDay(it, zone) },
            screenOnMillis = segments.sumOf { it.lengthMillis },
            longestFreeMillis = longestFree(segments, from, to),
            bins = RhythmBins.of(segments, start),
        )
    }

    /** `2 h 05 min`, `45 min` or `0 min`; null reads as a dash. */
    fun duration(millis: Long?): String {
        if (millis == null) return "—"
        val minutes = millis / 60_000L
        return if (minutes >= 60) String.format(java.util.Locale.ROOT, "%d h %02d min", minutes / 60, minutes % 60) else "$minutes min"
    }
}
