package com.brightblu.parc

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * T137: what the weather variant keeps on the phone: the chosen place (name, region text,
 * latitude, longitude), the unit choice and the last forecast answer with its time. Its own store
 * with the OPUS6 corruption rescue. Not part of Parc backups: a backup restored on a normal build
 * could not use it, and a new phone can look the city up again in one step.
 */
internal val Context.weatherDataStore by preferencesDataStore(name = WeatherStore.Name,
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler(WeatherStore.Name))

internal data class WeatherSaved(
    val place: WeatherPlace? = null,
    val units: UnitChoice = UnitChoice.Auto,
    val cacheBody: String? = null,
    val cachedAt: Long? = null,
    val cachePlace: String? = null,
    val lastAttempt: Long? = null,
) {
    val cacheMatchesPlace: Boolean get() = place != null && cachePlace == place.key && cacheBody != null
}

internal class WeatherStore(context: Context) {
    companion object { const val Name = "weather" }
    private val store = context.applicationContext.weatherDataStore
    private val placeName = stringPreferencesKey("place_name")
    private val placeDetail = stringPreferencesKey("place_detail")
    private val placeLat = doublePreferencesKey("place_lat")
    private val placeLon = doublePreferencesKey("place_lon")
    private val units = stringPreferencesKey("units")
    private val cacheBody = stringPreferencesKey("cache_body")
    private val cachedAt = longPreferencesKey("cache_at")
    private val cachePlace = stringPreferencesKey("cache_place")
    private val lastAttempt = longPreferencesKey("last_attempt")

    private fun decode(p: Preferences): WeatherSaved {
        val name = p[placeName]; val lat = p[placeLat]; val lon = p[placeLon]
        val place = if (name != null && lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0)
            WeatherPlace(name, p[placeDetail].orEmpty(), lat, lon) else null
        return WeatherSaved(place, UnitChoice.decode(p[units]), p[cacheBody], p[cachedAt], p[cachePlace], p[lastAttempt])
    }

    val saved: Flow<WeatherSaved> = store.data.map(::decode).catch {
        if (it is IOException) { ParcLog.w("Weather", "store unreadable", it); emit(WeatherSaved()) } else throw it
    }

    suspend fun read(): WeatherSaved = saved.first()

    /** A new place drops the old forecast and the retry clock, so the new city loads at once. */
    suspend fun setPlace(place: WeatherPlace) = store.edit {
        it[placeName] = place.name; it[placeDetail] = place.detail; it[placeLat] = place.latitude; it[placeLon] = place.longitude
        it.remove(cacheBody); it.remove(cachedAt); it.remove(cachePlace); it.remove(lastAttempt)
    }

    /** Removes the place and the saved forecast from the phone. */
    suspend fun forget() = store.edit {
        listOf(placeName, placeDetail, cacheBody, cachePlace).forEach { key -> it.remove(key) }
        it.remove(placeLat); it.remove(placeLon); it.remove(cachedAt); it.remove(lastAttempt)
    }

    suspend fun setUnits(choice: UnitChoice) = store.edit { it[units] = choice.id }
    suspend fun markAttempt(now: Long) = store.edit { it[lastAttempt] = now }
    suspend fun saveForecast(body: String, now: Long, placeKey: String) = store.edit {
        it[cacheBody] = body; it[cachedAt] = now; it[cachePlace] = placeKey
    }
}
