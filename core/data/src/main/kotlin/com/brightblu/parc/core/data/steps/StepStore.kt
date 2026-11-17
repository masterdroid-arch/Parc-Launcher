package com.brightblu.parc.core.data.steps

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Where the last counter reading is kept, so a restart does not lose it. It is deliberately NOT in the
 * backup: it describes one phone's sensor since its last boot, and on another phone it would credit
 * the wrong steps. The steps themselves live in the day ledger, which is backed up.
 */
internal interface StepStore {
    suspend fun read(): String?
    suspend fun write(text: String)
}

internal class StepPrefs(private val store: DataStore<Preferences>) : StepStore {
    override suspend fun read(): String? = store.data.first()[Key]
    override suspend fun write(text: String) {
        store.edit { it[Key] = text }
    }

    companion object {
        const val StoreName = "parc_steps"
        private val Key = stringPreferencesKey("last_reading_v1")
        fun create(context: Context): StepPrefs = StepPrefs(context.applicationContext.parcStepsStore)
    }
}

internal val Context.parcStepsStore by preferencesDataStore(
    name = StepPrefs.StoreName,
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler(StepPrefs.StoreName),
)
