package com.brightblu.parc.core.data.stats

/**
 * Turns successive `/proc/stat` readings into a CPU load percentage (T58). The first reading only
 * establishes a baseline, so no percentage is published until a second sample arrives. A missing
 * or reset counter drops the baseline and the next readable sample starts a new pair; this is why
 * an unreadable file never leaves an old percentage on screen.
 *
 * Framework-free and not thread-safe; called from the sampler's coroutine only.
 */
internal class CpuLoadTracker {
    private var previous: ProcStatTicks? = null

    /** Feeds one reading and returns the busy percentage, or null while unavailable. */
    fun update(current: ProcStatTicks?): Float? {
        if (current == null) {
            previous = null
            return null
        }
        val before = previous
        previous = current
        return before?.let { StatsParsers.cpuLoadPercent(it, current) }
    }

    fun reset() {
        previous = null
    }
}
