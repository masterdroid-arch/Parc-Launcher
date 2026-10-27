package com.brightblu.parc

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

internal val Context.hubDataStore by preferencesDataStore(name = "stats_hub",
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler("stats_hub"))
internal class HubConfigStore(context: Context) {
    private val store = context.applicationContext.hubDataStore
    private val key = stringPreferencesKey("config_v1")
    private val clockKey = stringPreferencesKey("clock_options")
    private val sizeKey = stringPreferencesKey("hub_size")
    private val pageKey = stringPreferencesKey("last_page")
    private val headerTimeKey = booleanPreferencesKey("header_time")
    val headerTime = store.data.map { it[headerTimeKey] ?: false }.catch {
        if (it is IOException) emit(false) else throw it
    }
    suspend fun setHeaderTime(show: Boolean) { store.edit { it[headerTimeKey] = show } }
    val lastPage = store.data.map { it[pageKey] ?: "clock" }.catch {
        if (it is IOException) emit("clock") else throw it
    }
    suspend fun rememberPage(page: String) {
        store.edit { it[pageKey] = page }
    }
    val config = store.data.map { HubConfig.decode(it[key].orEmpty()).copy(size = HubSize.decode(it[sizeKey]), clock = ClockOptions.decode(it[clockKey])) }.catch {
        if (it is IOException) emit(HubConfig()) else throw it
    }
    suspend fun update(change: (HubConfig) -> HubConfig) {
        store.edit {
            val next = change(HubConfig.decode(it[key].orEmpty()).copy(size = HubSize.decode(it[sizeKey]), clock = ClockOptions.decode(it[clockKey])))
            it[key] = next.encode()
            it[sizeKey] = next.size.id
            it[clockKey] = next.clock.encode()
        }
    }
}
