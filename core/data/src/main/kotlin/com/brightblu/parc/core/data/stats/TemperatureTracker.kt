package com.brightblu.parc.core.data.stats

/** The temperatures to publish and the sensors whose frozen readings were suppressed. */
internal data class TrackedTemperatures(
    val readings: TemperatureReading = TemperatureReading(),
    val stale: Set<TemperatureSensor> = emptySet(),
)

/**
 * Chooses one temperature source per sensor and detects a frozen source (T44, rule revised by T58).
 *
 * Source rule: a value from `/sys/class/thermal` wins for that sensor; the HAL value fills only
 * the sensors the kernel did not report. A HAL value is a new sample only on a tick whose
 * [halResolved] is true (a `dumpsys` process ran, or DUMP is not held so there is no HAL source);
 * between rounds the parsed sample is carried and published but does not advance the counter, so
 * a slow source is not mistaken for a frozen one.
 *
 * Frozen rule (T58): a sensor that reports the same value on [identicalSamplesForStale]
 * consecutive fresh samples is frozen. Its value is then null and the sensor appears in
 * [TrackedTemperatures.stale]. Unlike T44 no battery-temperature change is required: the card
 * defines identical samples alone as the evidence, and the sampler's 5 s cadence turns three
 * samples into 10 s of observation. A changed reading clears the mark immediately.
 *
 * Cross-start rule (T58): [beginSession] receives the HAL values persisted by the previous app
 * start. The first fresh HAL value of each sensor is compared with that baseline; a match marks
 * the sensor frozen at once (a HAL that has returned the same number for two days is not live).
 * The comparison is consumed by that first observation, so a temperature that merely returns to an
 * old value later in the session is not hidden.
 *
 * A sensor missing from a source that was actually read clears its run, and its stale mark, so a
 * vanished source is unavailable rather than stale. The battery temperature is not tracked here:
 * [StatsSampler] always prefers the live `BatteryManager` broadcast, which is the card's one live
 * temperature tile.
 *
 * Framework-free and not thread-safe; called from the sampler's coroutine only.
 */
internal class TemperatureTracker(
    private val identicalSamplesForStale: Int = DefaultIdenticalSamples,
) {
    private class Streak(val value: Float, var identical: Int)

    private val streaks = HashMap<TemperatureSensor, Streak>()
    private val staleSensors = HashSet<TemperatureSensor>()
    private val pendingStart = HashMap<TemperatureSensor, Float>()
    private val lastHal = HashMap<TemperatureSensor, Float>()

    /**
     * Supplies the persisted HAL values from the previous app start. Called once when a sampling
     * period starts, before the first [update].
     */
    fun beginSession(previousStart: Map<TemperatureSensor, Float>) {
        pendingStart.clear()
        pendingStart.putAll(previousStart)
    }

    /**
     * Feeds one sampling tick. [zones] is a fresh kernel reading (null when unreadable), [hal] the
     * latest HAL reading including a carried value, and [halResolved] true when this tick's HAL
     * result is authoritative: a process ran, or DUMP is not held so there is no HAL source.
     */
    fun update(
        zones: TemperatureReading?,
        hal: TemperatureReading?,
        halResolved: Boolean,
    ): TrackedTemperatures {
        for (sensor in TemperatureSensor.entries) {
            val zoneValue = valueOf(zones, sensor)
            val halValue = valueOf(hal, sensor)
            when {
                zoneValue != null -> observe(sensor, zoneValue)
                halValue != null && halResolved -> {
                    lastHal[sensor] = halValue
                    observeHal(sensor, halValue)
                }
                // Carried HAL value: publish it, but do not pretend it is a new sample.
                halValue != null -> Unit
                // A source that was actually read and did not report this sensor means the
                // sensor is gone; stop calling the previous value current.
                zones != null || halResolved -> clear(sensor)
            }
        }
        return TrackedTemperatures(readings = snapshot(), stale = staleSensors.toSet())
    }

    /**
     * The raw HAL values seen this active period, for the next start's cross-start check. The
     * battery sensor is excluded: the live `BatteryManager` reading is never suppressed, so a
     * stale HAL battery value must not become a cross-start baseline.
     */
    fun lastHalValues(): Map<TemperatureSensor, Float> =
        lastHal.filterKeys { it != TemperatureSensor.Battery }

    /** Drops every run, the baseline and the recorded HAL values. */
    fun reset() {
        streaks.clear()
        staleSensors.clear()
        pendingStart.clear()
        lastHal.clear()
    }

    private fun observe(sensor: TemperatureSensor, value: Float) {
        val streak = streaks[sensor]
        if (streak != null && streak.value == value) {
            streak.identical++
            if (streak.identical >= identicalSamplesForStale) staleSensors += sensor
        } else {
            streaks[sensor] = Streak(value = value, identical = 1)
            staleSensors -= sensor
        }
    }

    private fun observeHal(sensor: TemperatureSensor, value: Float) {
        val baseline = pendingStart.remove(sensor)
        observe(sensor, value)
        if (baseline != null && baseline == value) staleSensors += sensor
    }

    private fun clear(sensor: TemperatureSensor) {
        streaks.remove(sensor)
        staleSensors.remove(sensor)
    }

    private fun snapshot(): TemperatureReading = TemperatureReading(
        cpuTemperatureC = published(TemperatureSensor.Cpu),
        gpuTemperatureC = published(TemperatureSensor.Gpu),
        skinTemperatureC = published(TemperatureSensor.Skin),
        npuTemperatureC = published(TemperatureSensor.Npu),
        socTemperatureC = published(TemperatureSensor.Soc),
        batteryTemperatureC = published(TemperatureSensor.Battery),
    )

    private fun published(sensor: TemperatureSensor): Float? =
        if (sensor in staleSensors) null else streaks[sensor]?.value

    private fun valueOf(reading: TemperatureReading?, sensor: TemperatureSensor): Float? =
        when (sensor) {
            TemperatureSensor.Cpu -> reading?.cpuTemperatureC
            TemperatureSensor.Gpu -> reading?.gpuTemperatureC
            TemperatureSensor.Skin -> reading?.skinTemperatureC
            TemperatureSensor.Npu -> reading?.npuTemperatureC
            TemperatureSensor.Soc -> reading?.socTemperatureC
            TemperatureSensor.Battery -> reading?.batteryTemperatureC
        }

    companion object {
        /** T58: three identical fresh samples. At the 5 s temperature cadence, 10 s of evidence. */
        const val DefaultIdenticalSamples = 3
    }
}
