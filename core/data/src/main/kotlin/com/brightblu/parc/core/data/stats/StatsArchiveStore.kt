package com.brightblu.parc.core.data.stats

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Storage boundary for the T78 archive. One document holds every series and the write timestamp, so
 * a reader can never see a half-written history and the 15-minute write gate survives a restart.
 *
 * The production implementation is [StatsArchivePrefs]; a test substitutes an in-memory store.
 */
internal interface StatsArchiveStore {

    /** The last persisted document; an unreadable document reads as empty with no timestamp. */
    suspend fun load(): StoredStatsArchive

    /** Replaces the whole document atomically. */
    suspend fun save(archive: StatsArchive, writtenAtMillis: Long)
}

/**
 * DataStore-backed [StatsArchiveStore] in its own `parc_stats_history` preferences file, separate
 * from the launcher settings and from the alert thresholds. The archive is written at most once per
 * 15 minutes by [StatsArchiveRepository], not once per sample.
 */
internal class StatsArchivePrefs internal constructor(
    private val store: DataStore<Preferences>,
) : StatsArchiveStore {

    override suspend fun load(): StoredStatsArchive =
        StatsArchiveJson.decode(store.data.first()[KeyArchive])

    override suspend fun save(archive: StatsArchive, writtenAtMillis: Long) {
        val text = StatsArchiveJson.encode(archive, writtenAtMillis)
        store.edit { prefs -> prefs[KeyArchive] = text }
    }

    companion object {
        const val StoreName = "parc_stats_history"
        internal val KeyArchive = stringPreferencesKey("stats_archive")

        /** The process-wide store for an app context. */
        fun create(context: Context): StatsArchivePrefs =
            StatsArchivePrefs(context.applicationContext.parcStatsHistoryStore)
    }
}

private val Context.parcStatsHistoryStore by preferencesDataStore(name = StatsArchivePrefs.StoreName,
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler(StatsArchivePrefs.StoreName))
