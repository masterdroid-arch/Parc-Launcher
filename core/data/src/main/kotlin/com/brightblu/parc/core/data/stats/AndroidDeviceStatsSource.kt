package com.brightblu.parc.core.data.stats

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Production [DeviceStatsSource]: reads the device on the caller's [scope] and publishes immutable
 * snapshots. Inactive until [setActive]`(true)`, and back to no work at all on `setActive(false)`.
 *
 * [scope] should live as long as the caller wants stats (the launcher's UI scope), and [setActive]
 * follows the stats module's lifecycle: resumed + screen on + visible. The last HAL temperatures
 * are persisted in DataStore on deactivation so the next process start can hide a frozen HAL
 * immediately (T58); no caller wiring is needed.
 */
class AndroidDeviceStatsSource(
    context: Context,
    scope: CoroutineScope,
    /** `getThermalHeadroom(0)` reports current headroom rather than a forecast. */
    forecastSeconds: Int = 0,
) : DeviceStatsSource {

    private val sampler = StatsSampler(
        probe = AndroidStatsProbe(
            context = context,
            dumpsysRunner = ProcessDumpsysRunner(),
            forecastSeconds = forecastSeconds,
        ),
        scope = scope,
        elapsedMillis = SystemClock::elapsedRealtime,
        thermalMemory = DataStoreThermalMemory(context),
    )

    override val stats: StateFlow<DeviceStats> get() = sampler.stats

    override val history: StateFlow<StatsHistory> get() = sampler.history

    override fun setActive(active: Boolean) {
        sampler.setActive(active)
    }
}
