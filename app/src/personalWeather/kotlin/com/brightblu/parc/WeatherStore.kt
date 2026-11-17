package com.brightblu.parc

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * T137, T184: what the weather variant keeps on the phone: up to six places (name, region text,
 * latitude, longitude; the first is home), which one is showing, the unit choice and, for each
 * place, its last forecast answer with its time. Its own store with the OPUS6 corruption rescue. The place list (not the cached forecasts) is part of the Parc backup; see [WeatherBackupAccess].
 */
internal val Context.weatherDataStore by preferencesDataStore(name = WeatherStore.Name,
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler(WeatherStore.Name))

/** One place's saved record, as the page and the fetcher use it. */
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

/** What is saved for one place's forecast. */
internal data class PlaceCache(val body: String?, val at: Long?, val lastAttempt: Long?)

/** Everything the Weather page keeps: the place list (first is home), the shown place, units, a cache per place. */
internal data class WeatherBook(
    val places: List<WeatherPlace> = emptyList(),
    val selectedKey: String? = null,
    val units: UnitChoice = UnitChoice.Auto,
    val caches: Map<String, PlaceCache> = emptyMap(),
) {
    /** The place being shown: the chosen one, or home when the choice is gone. */
    val place: WeatherPlace? get() = WeatherPlaces.selected(places, selectedKey)

    fun saved(place: WeatherPlace?): WeatherSaved {
        val cache = place?.let { caches[it.key] }
        return WeatherSaved(place, units, cache?.body, cache?.at, place?.key.takeIf { cache?.body != null }, cache?.lastAttempt)
    }

    /** The record of the place being shown. */
    val shown: WeatherSaved get() = saved(place)
}

internal class WeatherStore(context: Context) {
    companion object {
        const val Name = "weather"
        private val placesKey = stringPreferencesKey("places")
        private val selectedKey = stringPreferencesKey("selected")
        private val units = stringPreferencesKey("units")
        // Before 1.2.0: one place under separate keys, one cache.
        private val oldName = stringPreferencesKey("place_name")
        private val oldDetail = stringPreferencesKey("place_detail")
        private val oldLat = doublePreferencesKey("place_lat")
        private val oldLon = doublePreferencesKey("place_lon")
        private val oldBody = stringPreferencesKey("cache_body")
        private val oldAt = longPreferencesKey("cache_at")
        private val oldPlace = stringPreferencesKey("cache_place")
        private val oldAttempt = longPreferencesKey("last_attempt")

        private fun bodyKey(key: String) = stringPreferencesKey("cache_body.$key")
        private fun atKey(key: String) = longPreferencesKey("cache_at.$key")
        private fun attemptKey(key: String) = longPreferencesKey("last_attempt.$key")

        private fun legacyPlaces(p: Preferences) = WeatherPlaces.migrate(p[placesKey], p[oldName], p[oldDetail], p[oldLat], p[oldLon])

        /** Reads the saved state, including the one-place state of earlier versions, without changing anything. */
        fun decode(p: Preferences): WeatherBook {
            val places = legacyPlaces(p)
            val caches = places.associate { place ->
                val key = place.key
                key to when {
                    p[placesKey] == null && p[oldPlace] == key -> PlaceCache(p[oldBody], p[oldAt], p[oldAttempt])
                    else -> PlaceCache(p[bodyKey(key)], p[atKey(key)], p[attemptKey(key)])
                }
            }
            return WeatherBook(places, p[selectedKey], UnitChoice.decode(p[units]), caches)
        }

        /** Moves the one-place state of earlier versions into the list form, once, at the start of any change. */
        fun migrate(p: MutablePreferences) {
            if (p[placesKey] != null) return
            val places = legacyPlaces(p)
            if (places.isNotEmpty()) {
                val key = places.first().key
                if (p[oldPlace] == key) {
                    p[oldBody]?.let { p[bodyKey(key)] = it }
                    p[oldAt]?.let { p[atKey(key)] = it }
                    p[oldAttempt]?.let { p[attemptKey(key)] = it }
                }
            }
            p[placesKey] = WeatherPlaces.encode(places)
            listOf(oldName, oldDetail, oldBody, oldPlace).forEach { p.remove(it) }
            p.remove(oldLat); p.remove(oldLon); p.remove(oldAt); p.remove(oldAttempt)
        }
    }

    private val store = context.applicationContext.weatherDataStore

    val book: Flow<WeatherBook> = store.data.map(::decode).catch {
        if (it is IOException) { ParcLog.w("Weather", "store unreadable", it); emit(WeatherBook()) } else throw it
    }

    suspend fun read(): WeatherBook = book.first()

    private fun forgetCache(p: MutablePreferences, key: String) {
        p.remove(bodyKey(key)); p.remove(atKey(key)); p.remove(attemptKey(key))
    }

    private suspend fun change(block: (MutablePreferences, List<WeatherPlace>) -> List<WeatherPlace>) = store.edit {
        migrate(it)
        val next = block(it, WeatherPlaces.decode(it[placesKey]))
        it[placesKey] = WeatherPlaces.encode(next)
    }

    /** Adds a place and shows it. False when the list is full or the place is already in it (then it is only shown). */
    suspend fun addPlace(place: WeatherPlace): Boolean {
        var added = false
        change { p, list ->
            val next = WeatherPlaces.add(list, place)
            if (next != null) { added = true; p[selectedKey] = place.key; next }
            else { if (list.any { it.key == place.key }) p[selectedKey] = place.key; list }
        }
        return added
    }

    suspend fun removePlace(key: String) = change { p, list ->
        forgetCache(p, key)
        if (p[selectedKey] == key) p.remove(selectedKey)
        WeatherPlaces.remove(list, key)
    }

    suspend fun movePlace(key: String, steps: Int) = change { _, list -> WeatherPlaces.move(list, key, steps) }
    suspend fun setHome(key: String) = change { _, list -> WeatherPlaces.setHome(list, key) }
    suspend fun select(key: String) = change { p, list -> if (list.any { it.key == key }) p[selectedKey] = key; list }

    /** Removes every place and every saved forecast from the phone. */
    suspend fun forgetAll() = store.edit { p ->
        WeatherPlaces.decode(p[placesKey]).forEach { forgetCache(p, it.key) }
        listOf(placesKey, selectedKey, oldName, oldDetail, oldBody, oldPlace).forEach { p.remove(it) }
        p.remove(oldLat); p.remove(oldLon); p.remove(oldAt); p.remove(oldAttempt)
    }

    suspend fun setUnits(choice: UnitChoice) = store.edit { it[units] = choice.id }
    suspend fun markAttempt(key: String, now: Long) = change { p, list -> p[attemptKey(key)] = now; list }
    suspend fun saveForecast(key: String, body: String, now: Long) = change { p, list ->
        if (list.any { it.key == key }) { p[bodyKey(key)] = body; p[atKey(key)] = now }
        list
    }
}

/** M13 review: the saved places in the backup. A restore keeps only places that decode (valid, no repeats, at most six). */
internal class WeatherBackupAccess(private val delegate: com.brightblu.parc.core.data.backup.StoreAccess) : com.brightblu.parc.core.data.backup.StoreAccess {
    override suspend fun read(spec: com.brightblu.parc.core.data.backup.BackupStoreSpec): Map<String, Any> = delegate.read(spec)
    override suspend fun write(spec: com.brightblu.parc.core.data.backup.BackupStoreSpec, values: Map<String, Any>) {
        val text = values["places"] as? String ?: return
        val places = WeatherPlaces.decode(text)
        // A readable-but-empty or unreadable list must not wipe the places the user already has.
        if (places.isEmpty()) return
        delegate.write(spec, mapOf("places" to WeatherPlaces.encode(places)))
    }
}
