package com.brightblu.parc

/** Sliding crash window, persisted across process death; backwards clock changes expire history. */
internal object CrashWindow {
    const val windowMs = 60_000L
    const val threshold = 3

    fun recent(timestamps: List<Long>, now: Long): List<Long> =
        timestamps.filter { it <= now && now - it <= windowMs }

    fun record(timestamps: List<Long>, now: Long): List<Long> =
        (recent(timestamps, now) + now).takeLast(threshold)
}
