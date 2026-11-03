package com.brightblu.parc

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.brightblu.parc.model.HubAppearance
import com.brightblu.parc.model.HubColour
import com.brightblu.parc.model.HubOpacity
import com.brightblu.parc.core.data.backup.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import java.io.IOException

/** App-local hub preferences share the existing hub store and its demo namespace. */
internal class HubAppearanceStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.hubDataStore)
    val appearance = store.data.map { HubAppearanceCodec.decode(it.asMap().mapKeys { entry -> entry.key.name }) }.catch {
        if (it is IOException) emit(HubAppearance()) else throw it
    }
    suspend fun update(change: (HubAppearance) -> HubAppearance) {
        store.edit { prefs ->
            val next = change(HubAppearanceCodec.decode(prefs.asMap().mapKeys { it.key.name }))
            HubAppearanceCodec.encode(next).forEach { (key, value) -> prefs[stringPreferencesKey(key)] = value }
        }
    }
}

/** T173: the saved hub appearance, for the previews so they draw the cards as Home does. */
@androidx.compose.runtime.Composable
internal fun rememberHubAppearance(): HubAppearance {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = androidx.compose.runtime.remember(context) { HubAppearanceStore(context) }
    return store.appearance.collectAsState(initial = HubAppearance()).value
}

internal object HubAppearanceCodec {
    const val Arc = "arc_hub_colour"
    const val Stats = "stats_hub_colour"
    const val Glass = "hub_glass"
    /** T171: absent or "default" means the hub follows the old single panel opacity. */
    const val ArcOpacity = "arc_hub_opacity"
    const val StatsOpacity = "stats_hub_opacity"
    /** T173: "Glass refraction"; absent (an older backup) means the default, on. */
    const val Refraction = "hub_refraction"
    val keys = listOf(Arc, Stats, Glass, ArcOpacity, StatsOpacity, Refraction)
    fun decode(values: Map<String, Any>) = HubAppearance(HubColour.decode(values[Arc] as? String),
        HubColour.decode(values[Stats] as? String), (values[Glass] as? String)?.toBooleanStrictOrNull(),
        opacity(values[ArcOpacity]), opacity(values[StatsOpacity]), (values[Refraction] as? String)?.toBooleanStrictOrNull() ?: true)
    private fun opacity(raw: Any?): Int? = (raw as? String)?.toIntOrNull()?.let { HubOpacity.clamp(it, glass = true) } // the wider glass range; the mode narrows it on read
    fun encode(value: HubAppearance) = mapOf(Arc to value.arc.name, Stats to value.stats.name,
        Glass to (value.glass?.toString() ?: "default"),
        ArcOpacity to (value.arcOpacity?.toString() ?: "default"), StatsOpacity to (value.statsOpacity?.toString() ?: "default"),
        Refraction to value.refraction.toString())
}

/** FullBackup always visits the hub (including legacy layout-only files). Missing new keys reset. */
internal class HubAppearanceBackupAccess(private val delegate: StoreAccess) : StoreAccess {
    override suspend fun read(spec: BackupStoreSpec) = delegate.read(spec)
    override suspend fun write(spec: BackupStoreSpec, values: Map<String, Any>) =
        delegate.write(spec, HubAppearanceCodec.encode(HubAppearance()) + values)
}
