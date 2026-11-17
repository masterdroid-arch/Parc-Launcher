package com.brightblu.parc.core.data.ledger

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/** Where the ledger document lives. The text is the single source of truth (backup reads it too). */
internal interface LedgerStore {
    suspend fun read(): String?
    suspend fun write(text: String)
}

/** DataStore-backed [LedgerStore] in its own `parc_day_ledger` preferences file. */
internal class LedgerPrefs(private val store: DataStore<Preferences>) : LedgerStore {
    override suspend fun read(): String? = store.data.first()[KeyLedger]
    override suspend fun write(text: String) {
        store.edit { it[KeyLedger] = text }
    }

    companion object {
        const val StoreName = "parc_day_ledger"
        const val KeyName = "ledger_v1"
        internal val KeyLedger = stringPreferencesKey(KeyName)

        fun create(context: Context): LedgerPrefs = LedgerPrefs(context.applicationContext.parcDayLedgerStore)
    }
}

internal val Context.parcDayLedgerStore by preferencesDataStore(
    name = LedgerPrefs.StoreName,
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler(LedgerPrefs.StoreName),
)
