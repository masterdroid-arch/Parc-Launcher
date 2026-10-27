package com.brightblu.parc.core.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File

/**
 * OPUS6 (audit F6): what every Parc preferences file does when it is damaged.
 *
 * Without a handler, DataStore throws [CorruptionException] on every read and edit; Parc's process
 * scopes do not catch it, so a damaged settings file crashed Parc at every start (and then safe mode
 * had no way out). Now a damaged file is first copied next to itself
 * (`<name>.preferences_pb.corrupt`, the first copy is never overwritten), and the store starts again
 * from its defaults, so Parc keeps working and the damaged bytes are not lost.
 */
object ParcStoreRescue {
    /** The `datastore` directory of the context the stores are created from; set at app start. */
    @Volatile var dataStoreDir: File? = null

    /** Called with the store name after a damaged file was kept aside; for a log line. */
    @Volatile var onRescued: (String) -> Unit = {}

    fun handler(name: String): ReplaceFileCorruptionHandler<Preferences> = ReplaceFileCorruptionHandler { _ ->
        try { keepCopy(dataStoreDir, name) } catch (_: Exception) { /* never block the recovery */ }
        try { onRescued(name) } catch (_: Exception) { }
        emptyPreferences()
    }

    /** Copies `<dir>/<name>.preferences_pb` to `<name>.preferences_pb.corrupt` once. Returns the copy. */
    fun keepCopy(dir: File?, name: String): File? {
        val source = File(dir ?: return null, "$name.preferences_pb")
        if (!source.isFile) return null
        val target = File(source.parentFile, source.name + ".corrupt")
        if (target.exists()) return target
        source.copyTo(target, overwrite = false)
        return target
    }
}
