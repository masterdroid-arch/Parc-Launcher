package com.brightblu.parc.core.data.stats

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Storage boundary for the T78 alert thresholds. One document holds all four thresholds, so a
 * switch and its value can never be read from different writes.
 *
 * The production implementation is [StatsAlertPrefs]; a test substitutes an in-memory DataStore.
 */
internal interface StatsAlertStore {

    /** The normalised thresholds; always one entry per metric, even when nothing is stored. */
    val thresholds: Flow<List<StatsAlertThreshold>>

    /** Atomically replaces the list with `transform(current)` and writes the normalised result. */
    suspend fun update(transform: (List<StatsAlertThreshold>) -> List<StatsAlertThreshold>)

    /** Removes the stored document, so the next read is the card defaults again. */
    suspend fun reset()
}

/**
 * DataStore-backed [StatsAlertStore] in its own `parc_stats_alerts` preferences file, independent of
 * `ParcPrefs` and of the history archive. The single key holds the JSON document from
 * [StatsAlertJson]; reads fall back to the defaults instead of throwing.
 */
internal class StatsAlertPrefs internal constructor(
    private val store: DataStore<Preferences>,
) : StatsAlertStore {

    override val thresholds: Flow<List<StatsAlertThreshold>> =
        store.data.map { StatsAlertJson.decode(it[KeyThresholds]) }

    override suspend fun update(
        transform: (List<StatsAlertThreshold>) -> List<StatsAlertThreshold>,
    ) {
        store.edit { prefs ->
            val current = StatsAlertJson.decode(prefs[KeyThresholds])
            prefs[KeyThresholds] = StatsAlertJson.encode(transform(current))
        }
    }

    override suspend fun reset() {
        store.edit { prefs -> prefs.remove(KeyThresholds) }
    }

    companion object {
        const val StoreName = "parc_stats_alerts"
        internal val KeyThresholds = stringPreferencesKey("stats_alert_thresholds")

        /** The process-wide store for an app context. */
        fun create(context: Context): StatsAlertPrefs =
            StatsAlertPrefs(context.applicationContext.parcStatsAlertStore)
    }
}

internal val Context.parcStatsAlertStore by preferencesDataStore(name = StatsAlertPrefs.StoreName,
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler(StatsAlertPrefs.StoreName))
