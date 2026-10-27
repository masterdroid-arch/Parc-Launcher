package com.brightblu.parc

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * T83 field diagnostics for the ring scenes. The app keeps the latest scene build facts in an
 * app-local DataStore (never `core/data` ParcPrefs) so a screenshot from Parc Settings shows what
 * the phone actually built: the shell layer, the published slot count, the arc capacities and the
 * last scene error. Values are plain text with no personal data.
 */
internal data class SceneDiagnostic(
    val layer: String = "—",
    val slots: Int = 0,
    val capacities: String = "—",
    val error: String = "",
    val updatedAtMillis: Long = 0L,
) {
    fun encode(): String = listOf(
        VERSION.toString(), layer, slots.toString(), capacities,
        updatedAtMillis.toString(), error.replace('\n', ' ').take(MAX_ERROR),
    ).joinToString("|")

    companion object {
        const val MAX_ERROR = 240
        private const val VERSION = 1

        fun decode(raw: String): SceneDiagnostic {
            val parts = raw.split('|')
            if (parts.size < 6 || parts[0].toIntOrNull() != VERSION) return SceneDiagnostic()
            return SceneDiagnostic(
                layer = parts[1].take(16),
                slots = parts[2].toIntOrNull()?.coerceAtLeast(0) ?: 0,
                capacities = parts[3].take(64),
                updatedAtMillis = parts[4].toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                error = parts.drop(5).joinToString("|").take(MAX_ERROR),
            )
        }
    }
}

/**
 * Process-local handoff for an error thrown while a scene was being built. The builder is total
 * and returns a usable scene, so the host records this text with the next diagnostics write and
 * logs it; a stale renderer can no longer hide a failure silently.
 */
internal object SceneDiagnostics {
    @Volatile
    var lastError: String = ""
        private set

    fun record(error: String) {
        lastError = error.replace('\n', ' ').take(SceneDiagnostic.MAX_ERROR)
    }

    fun clear() { lastError = "" }
}

private val Context.sceneDataStore by preferencesDataStore(name = "scene_diagnostics",
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler("scene_diagnostics"))

/** The four facts the T83 card names, plus the error, as rows for Parc Settings > Diagnostics. */
internal fun sceneDiagnosticLines(value: SceneDiagnostic): List<String> = listOf(
    "Layer: ${value.layer}",
    "Scene slots: ${value.slots}",
    "Arc capacities: ${value.capacities}",
    "Last scene error: ${value.error.ifEmpty { "none" }}",
)

internal class SceneDiagnosticsStore(context: Context) {
    private val store = context.applicationContext.sceneDataStore
    private val key = stringPreferencesKey("last_v1")
    val last = store.data.map { SceneDiagnostic.decode(it[key].orEmpty()) }.catch {
        if (it is IOException) emit(SceneDiagnostic()) else throw it
    }

    suspend fun record(value: SceneDiagnostic) {
        store.edit { it[key] = value.encode() }
    }
}
