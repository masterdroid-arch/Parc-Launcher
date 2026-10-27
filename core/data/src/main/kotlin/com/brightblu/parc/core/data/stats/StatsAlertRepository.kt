package com.brightblu.parc.core.data.stats

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The T78 alert thresholds as a live, writable setting (T79 draws the editor).
 *
 * [thresholds] always holds one entry per [StatsAlertMetric] in enum order, starting from the card
 * defaults, so a consumer never has to invent a missing value. Writes go through one atomic
 * DataStore update: setting a switch and a value separately can never interleave into a half-applied
 * edit. Values outside [StatsAlertMetric.validRange] are clamped; a non-finite value is ignored.
 *
 * Threading: [thresholds] is a [StateFlow] and safe to collect anywhere; the suspend setters may be
 * called from any dispatcher. DataStore does its own IO.
 */
class StatsAlertRepository internal constructor(
    private val store: StatsAlertStore,
    scope: CoroutineScope,
) {

    /** The four thresholds in enum order, always complete and normalised. */
    val thresholds: StateFlow<List<StatsAlertThreshold>> =
        store.thresholds.stateIn(scope, SharingStarted.Eagerly, StatsAlertThresholds.Default)

    /** Arms or disarms [metric] and stores [value] in the same atomic write. */
    suspend fun set(metric: StatsAlertMetric, enabled: Boolean, value: Float) {
        val clean = if (value.isFinite()) value.coerceIn(metric.validRange) else metric.defaultValue
        store.update { current ->
            current.map { threshold ->
                if (threshold.metric == metric) {
                    threshold.copy(enabled = enabled, value = clean)
                } else {
                    threshold
                }
            }
        }
    }

    /** Flips [metric]'s switch, keeping its stored value. */
    suspend fun setEnabled(metric: StatsAlertMetric, enabled: Boolean) {
        store.update { current ->
            current.map { threshold ->
                if (threshold.metric == metric) threshold.copy(enabled = enabled) else threshold
            }
        }
    }

    /** Replaces [metric]'s value, keeping its switch; out-of-range values are clamped. */
    suspend fun setValue(metric: StatsAlertMetric, value: Float) {
        if (!value.isFinite()) return
        val clean = value.coerceIn(metric.validRange)
        store.update { current ->
            current.map { threshold ->
                if (threshold.metric == metric) threshold.copy(value = clean) else threshold
            }
        }
    }

    /** Removes the stored document; [thresholds] returns to [StatsAlertThresholds.Default]. */
    suspend fun reset() {
        store.reset()
    }

    companion object {
        /** Production factory: the process-wide `parc_stats_alerts` DataStore. */
        fun create(context: Context, scope: CoroutineScope): StatsAlertRepository =
            StatsAlertRepository(StatsAlertPrefs.create(context), scope)
    }
}
