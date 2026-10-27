package com.brightblu.parc

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** OPUS6 (audit F5, F6): safe mode can reset settings or the layout, moving files aside, never deleting. */
class SafeModeRecoveryTest {
    private fun dataDir(): File = Files.createTempDirectory("parc-data").toFile().also { dir ->
        fun put(path: String, text: String) = File(dir, path).apply { parentFile.mkdirs(); writeText(text) }
        put("files/datastore/parc_settings.preferences_pb", "settings")
        put("files/datastore/parc_look.preferences_pb", "look")
        put("files/datastore/notes.preferences_pb", "notes")
        put("files/datastore/parc_focus.preferences_pb", "focus")
        put("shared_prefs/parc_ui.xml", "ui")
        put("shared_prefs/crash_guard.xml", "crashes")
        put("databases/parc.db", "layout")
        put("databases/parc.db-wal", "wal")
        put("files/startup-snapshot.json", "snapshot")
    }

    @Test fun resettingSettingsKeepsLayoutNotesAndFocusAndKeepsACopy() {
        val dir = dataDir()
        try {
            val moved = SafeModeRecovery.resetSettings(dir, 42L)!!
            assertFalse(File(dir, "files/datastore/parc_settings.preferences_pb").exists())
            assertFalse(File(dir, "shared_prefs/parc_ui.xml").exists())
            assertEquals("settings", File(moved, "parc_settings.preferences_pb").readText())
            assertEquals("look", File(moved, "parc_look.preferences_pb").readText())
            // Untouched:
            assertEquals("notes", File(dir, "files/datastore/notes.preferences_pb").readText())
            assertEquals("focus", File(dir, "files/datastore/parc_focus.preferences_pb").readText())
            assertEquals("layout", File(dir, "databases/parc.db").readText())
            assertEquals("crashes", File(dir, "shared_prefs/crash_guard.xml").readText())
            assertNull("a second reset finds nothing to move", SafeModeRecovery.resetSettings(dir, 43L))
        } finally { dir.deleteRecursively() }
    }

    @Test fun resettingTheLayoutIsSeparateAndKeepsACopy() {
        val dir = dataDir()
        try {
            val moved = SafeModeRecovery.resetLayout(dir, 7L)!!
            assertFalse(File(dir, "databases/parc.db").exists())
            assertEquals("layout", File(moved, "parc.db").readText())
            assertEquals("wal", File(moved, "parc.db-wal").readText())
            assertEquals("snapshot", File(moved, "startup-snapshot.json").readText())
            assertEquals("settings", File(dir, "files/datastore/parc_settings.preferences_pb").readText())
        } finally { dir.deleteRecursively() }
    }
}
