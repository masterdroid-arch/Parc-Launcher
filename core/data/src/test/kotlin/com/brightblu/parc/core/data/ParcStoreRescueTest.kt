package com.brightblu.parc.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** OPUS6 (audit F6): a damaged preferences file no longer crashes Parc, and its bytes are kept. */
class ParcStoreRescueTest {
    private val key = stringPreferencesKey("skin")

    @Test fun aDamagedFileReadsAsDefaultsAndIsCopiedAside() = runBlocking {
        val dir = Files.createTempDirectory("parc-store").toFile()
        val file = java.io.File(dir, "parc_settings.preferences_pb")
        file.writeBytes(byteArrayOf(0x7F, 0x00, 0x13, 0x37, 0x42, 0x42))
        ParcStoreRescue.dataStoreDir = dir
        var rescued = ""
        ParcStoreRescue.onRescued = { rescued = it }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(corruptionHandler = ParcStoreRescue.handler("parc_settings"),
                scope = scope, produceFile = { file })
            // Without the handler this read threw CorruptionException.
            assertNull(store.data.first()[key])
            store.edit { it[key] = "midnight" }
            assertEquals("midnight", store.data.first()[key])
            val copy = java.io.File(dir, "parc_settings.preferences_pb.corrupt")
            assertTrue("damaged bytes kept", copy.isFile && copy.readBytes().contentEquals(byteArrayOf(0x7F, 0x00, 0x13, 0x37, 0x42, 0x42)))
            assertEquals("parc_settings", rescued)
        } finally {
            scope.cancel()
            ParcStoreRescue.dataStoreDir = null
            ParcStoreRescue.onRescued = {}
            dir.deleteRecursively()
        }
    }

    @Test fun theFirstCopyIsNeverOverwrittenAndAMissingFileIsFine() {
        val dir = Files.createTempDirectory("parc-store2").toFile()
        try {
            assertNull(ParcStoreRescue.keepCopy(dir, "notes"))
            assertNull(ParcStoreRescue.keepCopy(null, "notes"))
            java.io.File(dir, "notes.preferences_pb").writeText("first")
            ParcStoreRescue.keepCopy(dir, "notes")
            java.io.File(dir, "notes.preferences_pb").writeText("second")
            val copy = ParcStoreRescue.keepCopy(dir, "notes")!!
            assertEquals("first", copy.readText())
        } finally { dir.deleteRecursively() }
    }
}
