package com.brightblu.parc.core.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Reads and writes the raw values of one store (T87). Values are Boolean, Int, Long, String or
 * Set of String, matching [BackupValueType]. Implementations only touch keys named by the spec.
 */
interface StoreAccess {
    /** The values currently stored under the keys of [spec]; absent keys are absent. */
    suspend fun read(spec: BackupStoreSpec): Map<String, Any>

    /** Writes [values] (already validated against the spec) in one atomic update where possible. */
    suspend fun write(spec: BackupStoreSpec, values: Map<String, Any>)
}

/** A store plus its allow-list. */
class BackupStore(val spec: BackupStoreSpec, val access: StoreAccess)

/** [StoreAccess] over a Jetpack DataStore of preferences. */
class PreferencesStoreAccess(private val store: DataStore<Preferences>) : StoreAccess {

    override suspend fun read(spec: BackupStoreSpec): Map<String, Any> {
        val snapshot = store.data.first().asMap()
        val out = LinkedHashMap<String, Any>()
        for (key in spec.keys) {
            val raw = snapshot.entries.firstOrNull { it.key.name == key.name }?.value ?: continue
            if (BackupValues.matches(key, raw)) out[key.name] = raw
        }
        return out
    }

    override suspend fun write(spec: BackupStoreSpec, values: Map<String, Any>) {
        store.edit { prefs ->
            for ((name, value) in values) {
                val key = spec.key(name) ?: continue
                if (!BackupValues.matches(key, value)) continue
                @Suppress("UNCHECKED_CAST")
                when (key.type) {
                    BackupValueType.Bool -> prefs[booleanPreferencesKey(name)] = value as Boolean
                    BackupValueType.Int -> prefs[intPreferencesKey(name)] = value as Int
                    BackupValueType.Long -> prefs[longPreferencesKey(name)] = value as Long
                    BackupValueType.Text -> prefs[stringPreferencesKey(name)] = value as String
                    BackupValueType.TextSet -> prefs[stringSetPreferencesKey(name)] = (value as Set<String>).toSet()
                }
            }
        }
    }
}

/** Type and size checks shared by the codec and the store adapters. Pure. */
object BackupValues {
    /** True when [value] has the Kotlin type and size [key] allows. */
    fun matches(key: BackupKey, value: Any): Boolean = when (key.type) {
        BackupValueType.Bool -> value is Boolean
        BackupValueType.Int -> value is Int
        BackupValueType.Long -> value is Long
        BackupValueType.Text -> value is String && value.length <= key.maxLength
        BackupValueType.TextSet -> value is Set<*> && value.size <= BackupKey.MaxSetEntries &&
            value.all { it is String && it.length <= key.maxLength }
    }
}
