package com.brightblu.parc

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.brightblu.parc.core.data.stats.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException

/** Process-owned dismissal memory. A recovered metric is rearmed independently of other alerts. */
data class StatsAlertUiState(
    val active: Set<StatsAlertMetric> = emptySet(),
    val dismissed: Set<StatsAlertMetric> = emptySet(),
) {
    val banner: StatsAlertMetric? get() = StatsAlertMetric.entries.firstOrNull { it in active && it !in dismissed }
    fun dismiss(metric: StatsAlertMetric) = if (metric in active) copy(dismissed = dismissed + metric) else this
    fun evaluate(stats: DeviceStats, thresholds: List<StatsAlertThreshold>, enabled: Boolean): StatsAlertUiState {
        val next = if (enabled) StatsAlertRules.evaluate(stats, thresholds, active) else emptySet()
        return StatsAlertUiState(next, dismissed.intersect(next))
    }
}

internal fun StatsAlertMetric.alertLabel(): String = when (this) {
    StatsAlertMetric.BatteryTemperatureHigh -> "Battery temperature high"
    StatsAlertMetric.StorageHigh -> "Storage used high"
    StatsAlertMetric.BatteryLow -> "Battery low"
    StatsAlertMetric.RamHigh -> "RAM used high"
}

internal fun alertForTile(tile: String, active: Set<StatsAlertMetric>): StatsAlertMetric? = when (tile) {
    "battery", "charging" -> StatsAlertMetric.BatteryLow
    "thermal", "battery temperature" -> StatsAlertMetric.BatteryTemperatureHigh
    "ram" -> StatsAlertMetric.RamHigh
    "storage" -> StatsAlertMetric.StorageHigh
    else -> null
}?.takeIf { it in active }

internal val Context.statsAlertUiDataStore by preferencesDataStore(name = "stats_alert_ui",
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler("stats_alert_ui"))
internal class StatsAlertUiStore(context: Context) {
    private val store = context.applicationContext.statsAlertUiDataStore
    private val key = booleanPreferencesKey("enabled")
    val enabled = store.data.map { it[key] ?: false }.catch {
        if (it is IOException) emit(false) else throw it
    }
    suspend fun setEnabled(enabled: Boolean) { store.edit { it[key] = enabled } }
}

internal class StatsAlertController(
    stats: StateFlow<DeviceStats>, thresholds: StateFlow<List<StatsAlertThreshold>>,
    store: StatsAlertUiStore, scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(StatsAlertUiState())
    val state = mutable.asStateFlow()
    init {
        scope.launch {
            combine(stats, thresholds, store.enabled) { reading, limits, enabled -> Triple(reading, limits, enabled) }
                .collect { (reading, limits, enabled) -> mutable.update { it.evaluate(reading, limits, enabled) } }
        }
    }
    fun dismiss(metric: StatsAlertMetric) { mutable.update { it.dismiss(metric) } }
}
