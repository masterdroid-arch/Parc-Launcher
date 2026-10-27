package com.brightblu.parc.core.data

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.LayoutJson
import com.brightblu.parc.model.LockedFolderTag
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.locked
import com.brightblu.parc.model.withFolderLocked
import com.brightblu.parc.model.withLocked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T98: the lock flag lives in the layout store and the backup document; old layouts migrate as unlocked. */
class LockedFolderLayoutTest {
    private val app = AppId("p.one/p.one.Main@0")
    private val vault = Folder(FolderId("vault"), "Vault", tags = setOf("work"), appIds = listOf(app))

    @Test fun anOldLayoutWithoutTheFlagDecodesAsUnlocked() {
        val old = """{"version":1,"arcs":[],"folders":[{"id":"vault","name":"Vault","tags":["work"],"appIds":["p.one/p.one.Main@0"]}],"pins":[],"hidden":[]}"""
        val layout = LayoutJson.decode(old)
        assertFalse(layout.folders.single().locked)
        assertEquals(setOf("work"), layout.folders.single().tags)
    }

    @Test fun theFlagSurvivesTheLayoutJsonUsedByBackupsAndImports() {
        val layout = ParcLayout(folders = listOf(vault.withLocked(true)))
        val back = LayoutJson.decode(LayoutJson.encode(layout))
        assertTrue(back.folders.single().locked)
        assertEquals(setOf("work", LockedFolderTag), back.folders.single().tags)
    }

    @Test fun theFlagSurvivesTheDatabaseRows() {
        val layout = ParcLayout(version = LayoutVersion, folders = listOf(vault.withLocked(true)))
        val restored = LayoutMapper.toLayout(LayoutMapper.toRows(layout), homeArcSpecs())
        assertTrue(restored.folders.single().locked)
        assertEquals(setOf("work", LockedFolderTag), restored.folders.single().tags)
        val plain = LayoutMapper.toLayout(
            LayoutMapper.toRows(ParcLayout(version = LayoutVersion, folders = listOf(vault))), homeArcSpecs())
        assertFalse(plain.folders.single().locked)
    }

    @Test fun syncingInstalledAppsKeepsTheFlag() {
        val layout = ParcLayout(version = LayoutVersion, folders = listOf(vault.withLocked(true)))
        val installed = listOf(AppEntry(app, "One", "p.one", false, 0))
        val after = LayoutSync.reconcile(layout, installed, NewFolderId, NewFolderName)
        assertTrue(after.folders.first { it.id == FolderId("vault") }.locked)
    }

    @Test fun settingAndClearingTheFlagTouchesNothingElse() {
        val layout = ParcLayout(folders = listOf(vault, Folder(FolderId("other"), "Other")))
        val locked = layout.withFolderLocked(FolderId("vault"), true)
        assertTrue(locked.folders.first().locked)
        assertFalse(locked.folders.last().locked)
        assertEquals(setOf("work", LockedFolderTag), locked.folders.first().tags)
        assertEquals(layout, locked.withFolderLocked(FolderId("vault"), false))
        assertEquals(layout, layout.withFolderLocked(FolderId("missing"), true))
    }

    @Test fun lockingTwiceChangesNothing() {
        val once = vault.withLocked(true)
        assertEquals(once, once.withLocked(true))
    }
}
