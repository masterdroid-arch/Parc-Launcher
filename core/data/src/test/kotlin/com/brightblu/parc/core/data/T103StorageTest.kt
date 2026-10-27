package com.brightblu.parc.core.data

import com.brightblu.parc.model.*
import com.brightblu.parc.core.data.backup.*
import org.junit.Assert.*
import org.junit.Test

class T103StorageTest {
    @Test fun shortcutRowRoundTripsIdentityLabelProfileAndFolder() {
        val specs = homeArcSpecs()
        val shortcut = Slot.Shortcut("com.example.notes", "new/note?", 10, "New note", FolderId("tools"))
        val layout = ParcLayout(arcs = specs.map { ArcAssignment(it, if (it.id == "A3") mapOf(20 to shortcut) else emptyMap()) },
            folders = listOf(Folder(FolderId("tools"), "Tools")))
        assertEquals(layout, LayoutMapper.toLayout(LayoutMapper.toRows(layout), specs))
        assertEquals(layout, LayoutJson.decode(LayoutJson.encode(layout)))
    }
    @Test fun layoutCountsValuesRoundTripBackupCodec() {
        val spec = BackupSpecs.uiPrefs
        val values = mapOf<String, Any>("arcs_shown" to 3, "icons_per_arc" to 5)
        values.forEach { (name, value) -> assertTrue(BackupValues.matches(requireNotNull(spec.key(name)), value)) }
        val text = BackupCodec.encode(ParcLayout(), mapOf(spec.id to values))
        val restored = BackupCodec.parse(text, listOf(spec))
        assertEquals(values, restored.values[spec.id])
        assertFalse(BackupValues.matches(requireNotNull(spec.key("arcs_shown")), "3"))
    }
}
