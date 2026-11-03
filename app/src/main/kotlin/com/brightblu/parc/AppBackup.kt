package com.brightblu.parc

import android.content.Context
import android.content.SharedPreferences
import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.core.data.backup.BackupStore
import com.brightblu.parc.core.data.backup.BackupStoreSpec
import com.brightblu.parc.core.data.backup.BackupStores
import com.brightblu.parc.core.data.backup.BackupValueType
import com.brightblu.parc.core.data.backup.BackupValues
import com.brightblu.parc.core.data.backup.StoreAccess

/**
 * T87: the app-local half of the backup. Lists every app-owned settings store next to the
 * `core/data` ones. Call [install] once from the process owner; Backup and restore pick the list up
 * through `BackupRegistry`.
 */
internal object AppBackup {
    val LookSpec = BackupSpecs.look
    val LayoutCountsSpec = BackupSpecs.uiPrefs
    /** Focus v2 travels through the core store registration, including friction options, delay steps, unlimited passes, accountability secrets and the persistent pass ledger. */
    val HubSpec: BackupStoreSpec get() = BackupSpecs.hub.copy(keys = BackupSpecs.hub.keys +
        HubAppearanceCodec.keys.map { com.brightblu.parc.core.data.backup.BackupKey(it, BackupValueType.Text) })
    val FocusModesSpec: BackupStoreSpec get() = BackupSpecs.focus

    fun stores(context: Context, customSkins: com.brightblu.parc.core.data.skins.CustomSkinStore? = null): List<BackupStore> {
        val app = context.applicationContext
        return BackupStores.core(app) + listOf(
            BackupStore(HubSpec, HubAppearanceBackupAccess(com.brightblu.parc.core.data.backup.PreferencesStoreAccess(app.hubDataStore))),
            BackupStore(BackupSpecs.notes, NotesBackupAccess(com.brightblu.parc.core.data.backup.PreferencesStoreAccess(app.notesDataStore))),
            BackupStores.of(BackupSpecs.gestures, app.gestureDataStore),
            // The Look document also carries panel opacity and wallpaper readability.
            BackupStores.of(LookSpec, app.lookDataStore),
            BackupStore(BackupSpecs.wallpaperImage, WallpaperImageBackupAccess(app)),
            BackupStores.of(BackupSpecs.feel, app.feelDataStore),
            BackupStores.of(BackupSpecs.statsAlertUi, app.statsAlertUiDataStore),
            BackupStore(
                BackupSpecs.uiPrefs,
                SharedPreferencesStoreAccess(app.getSharedPreferences(UiPrefsFile, Context.MODE_PRIVATE)),
            ),
        ) + listOfNotNull(customSkins?.let { BackupStore(CustomSkinsSpec, CustomSkinsStoreAccess(it)) })
    }

    fun install(context: Context, customSkins: com.brightblu.parc.core.data.skins.CustomSkinStore? = null) {
        com.brightblu.parc.core.data.backup.BackupRegistry.install(stores(context, customSkins))
    }

    /** Custom skins travel as a set of skin JSON texts under one key (T85 + T87). */
    val CustomSkinsSpec = BackupStoreSpec("custom_skins", listOf(
        com.brightblu.parc.core.data.backup.BackupKey("skins", BackupValueType.TextSet, 16_384)))

    /** File name of the SharedPreferences behind [ParcUiPrefs]. */
    const val UiPrefsFile = "parc_ui"
}

/** [StoreAccess] over SharedPreferences (the `parc_ui` file). Writes are committed synchronously. */
internal class SharedPreferencesStoreAccess(private val prefs: SharedPreferences) : StoreAccess {

    override suspend fun read(spec: BackupStoreSpec): Map<String, Any> {
        val all = prefs.all
        val out = LinkedHashMap<String, Any>()
        for (key in spec.keys) {
            val raw = all[key.name] ?: continue
            val value: Any = if (raw is Set<*>) raw.filterIsInstance<String>().toSet() else raw
            if (BackupValues.matches(key, value)) out[key.name] = value
        }
        return out
    }

    override suspend fun write(spec: BackupStoreSpec, values: Map<String, Any>) {
        val editor = prefs.edit()
        for ((name, value) in values) {
            val key = spec.key(name) ?: continue
            if (!BackupValues.matches(key, value)) continue
            @Suppress("UNCHECKED_CAST")
            when (key.type) {
                BackupValueType.Bool -> editor.putBoolean(name, value as Boolean)
                BackupValueType.Int -> editor.putInt(name, value as Int)
                BackupValueType.Long -> editor.putLong(name, value as Long)
                BackupValueType.Text -> editor.putString(name, value as String)
                BackupValueType.TextSet -> editor.putStringSet(name, (value as Set<String>).toSet())
            }
        }
        editor.commit()
    }
}

/** [StoreAccess] over the custom skin files: reads every valid skin, writes only valid `custom_` skins. */
internal class CustomSkinsStoreAccess(private val store: com.brightblu.parc.core.data.skins.CustomSkinStore) : StoreAccess {
    override suspend fun read(spec: BackupStoreSpec): Map<String, Any> {
        val texts = store.skins.value.take(MaxSkins).map { com.brightblu.parc.model.SkinJson.encode(it) }.toSet()
        return if (texts.isEmpty()) emptyMap() else mapOf("skins" to texts)
    }

    override suspend fun write(spec: BackupStoreSpec, values: Map<String, Any>) {
        val texts = (values["skins"] as? Set<*>)?.filterIsInstance<String>().orEmpty()
        for (text in texts.take(MaxSkins)) {
            val skin = runCatching { com.brightblu.parc.model.SkinJson.decode(text) }.getOrNull() ?: continue
            store.save(skin) // refuses non-custom ids and bad names
        }
    }

    private companion object { const val MaxSkins = 24 }
}
