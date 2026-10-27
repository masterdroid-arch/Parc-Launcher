package com.brightblu.parc.core.data

import com.brightblu.parc.model.*
import org.junit.Assert.*
import org.junit.Test

class T105StorageTest {
    private val id = AppId("example.private/example.private.Main@0")
    private val folder = Folder(FolderId("vault"), "Private", appIds = listOf(id)).withLocked(true)
    private val layout = ParcLayout(folders = listOf(folder))
    @Test fun layoutExportAndLegacyMigrationPreserveLockedMembersWithoutPlainListing() {
        val encoded = LayoutJson.encode(layout)
        assertFalse(encoded.contains(id.value)); assertFalse(encoded.contains("example.private"))
        assertEquals(layout, LayoutJson.decode(encoded))
        val old = """{"version":1,"folders":[{"id":"vault","name":"Private","tags":["parc:locked"],"appIds":["${id.value}"]}]}"""
        assertEquals(layout, LayoutJson.decode(old))
        assertFalse(LayoutJson.encode(LayoutJson.decode(old)).contains(id.value))
    }
    @Test fun fullBackupRestoresObfuscatedMembersAndAcceptsLegacyLayoutSection() {
        val encoded = com.brightblu.parc.core.data.backup.BackupCodec.encode(layout, emptyMap())
        assertFalse(encoded.contains("example.private"))
        val restored = com.brightblu.parc.core.data.backup.BackupCodec.parse(encoded, emptyList())
        assertEquals(layout, LayoutJson.decode(restored.layoutJson!!))
    }
    @Test fun duplicateMemberReferencesInBackedUpPickerAndFocusChoicesAreObfuscatedAndRestore() {
        val specs = com.brightblu.parc.core.data.backup.BackupSpecs
        val values: Map<String, Map<String, Any>> = mapOf(
            specs.Settings to mapOf("main_apps" to "[\"${id.value}\"]"),
            specs.Focus to mapOf(specs.FocusModesKey to "{\"hiddenPackages\":[\"example.private\"]}"),
        )
        val codec = com.brightblu.parc.core.data.backup.BackupCodec
        val encoded = codec.encode(layout, values)
        assertFalse(encoded.contains("example.private"))
        assertEquals(values, codec.parse(encoded, specs.all).values)
        val legacy = codec.encode(ParcLayout(), values)
        assertTrue(legacy.contains("example.private"))
        assertEquals(values, codec.parse(legacy, specs.all).values)
    }
    @Test fun startupSnapshotObfuscatesAppMetadataAndRestoresIt() {
        val snapshot = StartupSnapshot(layout, listOf(AppEntry(id,"Secret label","example.private",false,0)))
        val encoded = StartupSnapshotJson.encode(snapshot)
        assertFalse(encoded.contains("Secret label")); assertFalse(encoded.contains("example.private"))
        assertEquals(snapshot, StartupSnapshotJson.decode(encoded))
    }
    @Test fun databaseRowsObfuscateMembersAndReadLegacyPlainRows() {
        val rows = LayoutMapper.toRows(layout)
        assertFalse(rows.folderApps.single().appId.contains("example.private"))
        assertEquals(layout, LayoutMapper.toLayout(rows, emptyList()))
        val legacy = rows.copy(folderApps = listOf(FolderAppEntity("vault",id.value,0)))
        assertEquals(layout, LayoutMapper.toLayout(legacy, emptyList()))
    }
}
