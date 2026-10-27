package com.brightblu.parc.core.data.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.brightblu.parc.core.data.focus.parcFocusStore
import com.brightblu.parc.core.data.icons.parcIconPackStore
import com.brightblu.parc.core.data.parcSettingsStore
import com.brightblu.parc.core.data.search.parcSearchStore
import com.brightblu.parc.core.data.stats.parcStatsAlertStore

/** The `core/data` stores that the backup covers (T87), over the same process-wide DataStores. */
object BackupStores {
    fun core(context: Context): List<BackupStore> {
        val app = context.applicationContext
        return listOf(
            of(BackupSpecs.settings, app.parcSettingsStore),
            of(BackupSpecs.search, app.parcSearchStore),
            of(BackupSpecs.focus, app.parcFocusStore),
            of(BackupSpecs.iconPack, app.parcIconPackStore),
            of(BackupSpecs.statsAlerts, app.parcStatsAlertStore),
        )
    }

    /** Wraps any preferences DataStore; used by `:app` for its app-local stores and by tests. */
    fun of(spec: BackupStoreSpec, store: DataStore<Preferences>): BackupStore =
        BackupStore(spec, PreferencesStoreAccess(store))
}

/**
 * Process-wide list of the stores a backup covers. `:app` installs the full list once at start-up;
 * the Backup screen's service reads it when it runs, so no screen needs to know about individual
 * stores. Empty until installed, in which case a backup carries the layout only.
 */
object BackupRegistry {
    @Volatile
    var stores: List<BackupStore> = emptyList()
        private set

    fun install(stores: List<BackupStore>) {
        this.stores = stores.toList()
    }
}
