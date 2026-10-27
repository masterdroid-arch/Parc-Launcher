package com.brightblu.parc.core.data.stats

import kotlinx.coroutines.flow.StateFlow

/**
 * Thread-safe device-stats stream. [stats] is an immutable snapshot that never changes while the
 * subsystem is inactive; [history] is the matching bounded sample history for sparklines, and is
 * empty until the active period has produced its first sample.
 *
 * Sampling runs only between [setActive] calls: `setActive(true)` starts a 1 s tick, and
 * `setActive(false)` stops it. While inactive there is no coroutine, no `delay`, no scheduled
 * wakeup and no source read at all, so a hidden stats module costs nothing.
 *
 * Call [setActive] from one thread (the caller that owns the lifecycle). All methods are safe to
 * call from any thread.
 */
interface DeviceStatsSource {
    val stats: StateFlow<DeviceStats>

    /** Bounded rx/tx/CPU-temperature series for the stats sparklines; empty while inactive. */
    val history: StateFlow<StatsHistory>

    fun setActive(active: Boolean)
}
