package com.brightblu.parc

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

internal val Context.translateDataStore by preferencesDataStore(name = TranslateStore.Name,
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler(TranslateStore.Name))

/** The last 20 translations stay on the phone only, outside backups; the key is not here. */
internal class TranslateStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.translateDataStore)

    companion object {
        const val Name = "translate"
        private val historyKey = stringPreferencesKey("history")
    }

    val history: Flow<List<TranslationEntry>> = store.data.map { TranslationHistory.decode(it[historyKey]) }.catch {
        if (it is IOException) emit(emptyList()) else throw it
    }

    suspend fun add(entry: TranslationEntry) = store.edit {
        it[historyKey] = TranslationHistory.encode(TranslationHistory.add(TranslationHistory.decode(it[historyKey]), entry))
    }

    suspend fun clear() = store.edit { it.remove(historyKey) }
}
