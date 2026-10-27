package com.brightblu.parc

import java.io.File

/**
 * OPUS6 (audit F5, F6): the two ways out of safe mode short of clearing Parc's data.
 *
 * Nothing is deleted. Files are MOVED into `files/recovery/<what>-<time>/`, so the previous state can
 * be put back by hand (or by a later restore) and Parc starts with defaults for exactly what was
 * reset.
 *
 * - [resetSettings]: every settings file (look, skin choice, hub pages, gestures, haptics, search,
 *   icon pack, stats alerts and the app-local UI preferences). The layout, notes, Focus modes,
 *   running timers, stats history, custom skins and the crash counter are left alone.
 * - [resetLayout]: the layout database and the cached startup layout, only when the user asks for it
 *   separately. Parc then starts with the starter layout.
 */
internal object SafeModeRecovery {
    /** DataStore files that only hold settings. */
    val settingsStores = listOf("parc_settings", "parc_look", "stats_hub", "gestures", "feel",
        "stats_alert_ui", "parc_search", "parc_icon_packs", "parc_stats_alerts", "scene_diagnostics",
        "parc_thermal_memory")

    /** SharedPreferences files that only hold settings or one-time hints. */
    val settingsPrefs = listOf("parc_ui", "release_seen")

    /** Layout database files (Room) and the cached startup layout. */
    val layoutFiles = listOf("parc.db", "parc.db-wal", "parc.db-shm", "parc.db-journal")

    /** Moves the settings files of [dataDir] (the app's data directory). Returns the folder used, or null when nothing was there. */
    fun resetSettings(dataDir: File, now: Long): File? {
        val target = File(dataDir, "files/recovery/settings-$now")
        val sources = settingsStores.map { File(dataDir, "files/datastore/$it.preferences_pb") } +
            settingsPrefs.map { File(dataDir, "shared_prefs/$it.xml") }
        return moveAll(sources, target)
    }

    /** Moves the layout database and the startup snapshot of [dataDir]. Returns the folder used, or null. */
    fun resetLayout(dataDir: File, now: Long): File? {
        val target = File(dataDir, "files/recovery/layout-$now")
        val sources = layoutFiles.map { File(dataDir, "databases/$it") } + File(dataDir, "files/startup-snapshot.json")
        return moveAll(sources, target)
    }

    private fun moveAll(sources: List<File>, target: File): File? {
        val present = sources.filter { it.isFile }
        if (present.isEmpty()) return null
        if (!target.isDirectory && !target.mkdirs()) throw java.io.IOException("cannot create ${target.name}")
        for (file in present) {
            val destination = File(target, file.name)
            if (!file.renameTo(destination)) {
                file.copyTo(destination, overwrite = true)
                if (!file.delete()) throw java.io.IOException("cannot move ${file.name}")
            }
        }
        return target
    }
}
