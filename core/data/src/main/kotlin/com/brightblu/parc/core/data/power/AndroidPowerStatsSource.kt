package com.brightblu.parc.core.data.power

import android.content.Context
import android.os.SystemClock
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Production [PowerStatsSource]: reads the battery and, when usage access is granted, screen-on
 * time on the caller's [scope]. Inactive until [setActive]`(true)`, and back to no work at all on
 * `setActive(false)`.
 *
 * [scope] should live as long as the caller wants power data (the launcher's UI scope), and
 * [setActive] follows the hub's lifecycle: the Power page is visible. No foreground service, no
 * receiver and no scheduled work outside that window.
 */
class AndroidPowerStatsSource(
    context: Context,
    scope: CoroutineScope,
) : PowerStatsSource {

    private val sampler = PowerSampler(
        probe = AndroidPowerProbe(context),
        scope = scope,
        elapsedMillis = SystemClock::elapsedRealtime,
        wallClock = System::currentTimeMillis,
        zone = { ZoneId.systemDefault() },
    )

    override val stats: StateFlow<PowerStats> get() = sampler.stats

    override fun setActive(active: Boolean) {
        sampler.setActive(active)
    }
}
