package com.brightblu.parc.core.data.power

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.brightblu.parc.core.data.stats.StatsParsers
import com.brightblu.parc.core.data.usage.UsageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production [PowerProbe].
 *
 * Battery readings use public `BatteryManager` APIs and the sticky `ACTION_BATTERY_CHANGED`
 * broadcast: current, voltage, status, health, cycle count, temperature, and
 * `computeChargeTimeRemaining()`. The deprecated `registerReceiver(null, filter)` overload only
 * reads the sticky broadcast; no receiver is registered and nothing stays alive. No permission is
 * required for any of it.
 *
 * Screen-on time uses `UsageStatsManager.queryEvents`, which needs the GET_USAGE_STATS app op
 * (already the optional enrichment grant used by T13/T47). Without it, the method returns null.
 *
 * Every failure path returns null rather than a guess, and nothing here throws.
 *
 * [sdkInt] is injected only so the Android 13 fallback for the API 34 cycle-count extra is
 * unit-tested with a fake level; production passes the real `Build.VERSION.SDK_INT`.
 */
internal class AndroidPowerProbe(
    context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : PowerProbe {

    private val appContext = context.applicationContext
    private val batteryManager =
        appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    override suspend fun battery(): PowerBatterySample? = try {
        val status = batteryStatusIntent()
        val manager = batteryManager ?: return null
        val rawCurrent = try {
            manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        } catch (unexpected: Throwable) {
            Long.MIN_VALUE
        }
        val current = rawCurrent.takeIf {
            it != Long.MIN_VALUE && it != Int.MIN_VALUE.toLong()
        }
        val rawVoltage = status?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val rawTemperature = status
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, StatsParsers.UnsupportedProperty)
            ?: StatsParsers.UnsupportedProperty
        val rawHealth = status?.getIntExtra(BatteryManager.EXTRA_HEALTH, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val rawStatus = status
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN

        PowerBatterySample(
            currentMicroAmps = current,
            voltageMillivolts = rawVoltage.takeIf { it > 0 },
            chargingState = StatsParsers.chargingState(rawStatus),
            chargeTimeRemainingMillis = PowerMath.chargeTimeRemaining(
                manager.computeChargeTimeRemaining(),
            ),
            health = PowerMath.batteryHealth(rawHealth),
            cycleCount = BatteryCycleCount.read(sdkInt) { key ->
                status?.getIntExtra(key, -1) ?: -1
            },
            temperatureC = StatsParsers.batteryTemperatureCelsius(rawTemperature),
        )
    } catch (unexpected: Throwable) {
        null
    }

    /**
     * Reads one day of lookback before local midnight so a screen that was already on at midnight
     * is counted from midnight. `UsageEvents` has no close method. The read is off the main thread.
     */
    override suspend fun screenOn(
        windowStartMillis: Long,
        windowEndMillis: Long,
    ): Long? = withContext(Dispatchers.IO) {
        if (windowEndMillis <= windowStartMillis) return@withContext 0L
        if (!UsageAccess.granted(appContext)) return@withContext null
        val manager = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return@withContext null
        try {
            val queryFrom = (windowStartMillis - ScreenOnLookbackMillis).coerceAtLeast(0L)
            val usageEvents = manager.queryEvents(queryFrom, windowEndMillis)
                ?: return@withContext null
            val events = ArrayList<ScreenEvent>()
            val event = UsageEvents.Event()
            while (usageEvents.hasNextEvent()) {
                if (!usageEvents.getNextEvent(event)) break
                when (event.eventType) {
                    UsageEvents.Event.SCREEN_INTERACTIVE ->
                        events += ScreenEvent(interactive = true, atMillis = event.timeStamp)
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE ->
                        events += ScreenEvent(interactive = false, atMillis = event.timeStamp)
                }
            }
            ScreenOnTimeMath.todayMillis(events, windowStartMillis, windowEndMillis)
        } catch (unexpected: Throwable) {
            null
        }
    }

    override fun reset() {
        // Nothing is cached between samples; the next active period reads the platform again.
    }

    private fun batteryStatusIntent(): Intent? = try {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    } catch (unexpected: RuntimeException) {
        null
    }

    private companion object {
        /** One day is enough to establish the screen state at local midnight. */
        const val ScreenOnLookbackMillis = 24L * 60L * 60L * 1000L
    }
}
