package com.brightblu.parc.core.data.stats

import android.content.Context
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Stores the last HAL temperature seen per sensor so the next app start can recognise a vendor
 * HAL that has returned the same number since the previous run (T58). Only CPU/GPU/skin/NPU/SOC
 * are stored; the live battery temperature is never part of the frozen rule.
 *
 * Thread-safety: implementations may be called from the sampler's coroutine only.
 */
internal interface ThermalMemory {
    /** The previous start's values, or an empty map when none were stored or readable. */
    suspend fun load(): Map<TemperatureSensor, Float>

    /** Replaces the stored values; an empty map clears them. */
    suspend fun save(values: Map<TemperatureSensor, Float>)
}

/** Used when no persistence is wired, and by sampler tests that do not exercise a restart. */
internal object NoThermalMemory : ThermalMemory {
    override suspend fun load(): Map<TemperatureSensor, Float> = emptyMap()

    override suspend fun save(values: Map<TemperatureSensor, Float>) = Unit
}

/**
 * The on-disk form: `CPU=49.888;GPU=49.628`, sensors in declaration order. A separate encoded
 * string keeps the pref file readable and makes the parser a pure, testable function; malformed
 * entries are skipped so a damaged value only loses the cross-start check, never the stats module.
 */
internal object ThermalMemoryCodec {
    const val Separator = ";"
    const val Assignment = "="

    fun encode(values: Map<TemperatureSensor, Float>): String =
        values.entries
            .filter { it.key != TemperatureSensor.Battery && it.value.isFinite() }
            .sortedBy { it.key.ordinal }
            .joinToString(Separator) { "${it.key.name.uppercase()}$Assignment${it.value}" }

    fun decode(text: String?): Map<TemperatureSensor, Float> {
        if (text.isNullOrBlank()) return emptyMap()
        val decoded = HashMap<TemperatureSensor, Float>()
        for (entry in text.split(Separator)) {
            val name = entry.substringBefore(Assignment, missingDelimiterValue = "")
            val raw = entry.substringAfter(Assignment, missingDelimiterValue = "")
            val sensor = TemperatureSensor.entries
                .firstOrNull { it.name.equals(name, ignoreCase = true) } ?: continue
            val value = raw.toFloatOrNull() ?: continue
            if (!value.isFinite()) continue
            decoded[sensor] = value
        }
        return decoded
    }

    /**
     * True when the stored baseline came from an earlier app process. Values written by the same
     * process (the hub was merely hidden and shown again) are not a cross-start observation and
     * must not hide a live sensor. `elapsedRealtime` at process start distinguishes the two.
     */
    fun isFromPreviousProcess(storedProcessStart: Long?, currentProcessStart: Long): Boolean =
        storedProcessStart != null && storedProcessStart != currentProcessStart
}

/** Production [ThermalMemory] in the app's private DataStore. */
internal class DataStoreThermalMemory(
    context: Context,
    private val processStartMillis: Long = CurrentProcessStartMillis,
) : ThermalMemory {
    private val appContext = context.applicationContext

    override suspend fun load(): Map<TemperatureSensor, Float> {
        val prefs = appContext.thermalMemoryStore.data.first()
        val storedStart = prefs[KeyProcessStart] ?: return emptyMap()
        if (!ThermalMemoryCodec.isFromPreviousProcess(storedStart, processStartMillis)) {
            return emptyMap()
        }
        return ThermalMemoryCodec.decode(prefs[KeyLastHal])
    }

    override suspend fun save(values: Map<TemperatureSensor, Float>) {
        appContext.thermalMemoryStore.edit { prefs ->
            if (values.isEmpty()) {
                prefs.remove(KeyLastHal)
            } else {
                prefs[KeyLastHal] = ThermalMemoryCodec.encode(values)
                prefs[KeyProcessStart] = processStartMillis
            }
        }
    }

    private companion object {
        val KeyLastHal = stringPreferencesKey("last_hal_temperatures")
        val KeyProcessStart = longPreferencesKey("last_hal_process_start")
    }
}

private val Context.thermalMemoryStore: DataStore<Preferences> by
    preferencesDataStore(name = "parc_thermal_memory",
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler("parc_thermal_memory"))

/**
 * One token per process, so every stats source in the same process agrees on what "a previous app
 * start" means. `elapsedRealtime` differs across process lifetimes.
 */
private val CurrentProcessStartMillis: Long = SystemClock.elapsedRealtime()
