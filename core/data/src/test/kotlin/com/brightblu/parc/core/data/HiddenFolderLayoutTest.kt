package com.brightblu.parc.core.data

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.HiddenFolderTag
import com.brightblu.parc.model.LayoutJson
import com.brightblu.parc.model.LockedFolderTag
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.hidden
import com.brightblu.parc.model.locked
import com.brightblu.parc.model.withFolderHidden
import com.brightblu.parc.model.withHidden
import com.brightblu.parc.model.withLocked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T218: the hide flag lives in the existing folder tags, so backups and the database carry it. */
class HiddenFolderLayoutTest {
    private val app = AppId("p.one/p.one.Main@0")
    private val social = Folder(FolderId("social"), "Social", tags = setOf("work"), appIds = listOf(app))

    @Test fun anOldLayoutWithoutTheFlagDecodesAsVisible() {
        val old = """{"version":1,"arcs":[],"folders":[{"id":"social","name":"Social","tags":["work"],"appIds":["p.one/p.one.Main@0"]}],"pins":[],"hidden":[]}"""
        val layout = LayoutJson.decode(old)
        assertFalse(layout.folders.single().hidden)
        assertEquals(setOf("work"), layout.folders.single().tags)
    }

    @Test fun theFlagSurvivesTheLayoutJsonUsedByBackupsAndImports() {
        val layout = ParcLayout(folders = listOf(social.withLocked(true).withHidden(true)))
        val back = LayoutJson.decode(LayoutJson.encode(layout))
        assertTrue(back.folders.single().hidden)
        assertTrue(back.folders.single().locked)
        assertEquals(setOf("work", LockedFolderTag, HiddenFolderTag), back.folders.single().tags)
    }

    @Test fun theFlagSurvivesTheDatabaseRows() {
        val layout = ParcLayout(version = LayoutVersion,
            folders = listOf(social.withHidden(true)))
        val restored = LayoutMapper.toLayout(LayoutMapper.toRows(layout), homeArcSpecs())
        assertTrue(restored.folders.single().hidden)
        assertEquals(setOf("work", HiddenFolderTag), restored.folders.single().tags)
    }

    @Test fun syncingInstalledAppsKeepsTheFlagMembersAndDoesNotMoveThemToNew() {
        val layout = ParcLayout(version = LayoutVersion, folders = listOf(social.withHidden(true)))
        val installed = listOf(AppEntry(app, "One", "p.one", false, 0))
        val after = LayoutSync.reconcile(layout, installed, NewFolderId, NewFolderName)
        val folder = after.folders.single { it.id == FolderId("social") }
        assertTrue(folder.hidden)
        assertEquals(listOf(app), folder.appIds)
        assertTrue(after.folders.none { it.id == NewFolderId })
    }

    @Test fun settingAndClearingTheFlagTouchesNothingElse() {
        val layout = ParcLayout(folders = listOf(social.withLocked(true), Folder(FolderId("other"), "Other")))
        val hidden = layout.withFolderHidden(FolderId("social"), true)
        assertTrue(hidden.folders.first().hidden)
        assertTrue(hidden.folders.first().locked)
        assertFalse(hidden.folders.last().hidden)
        assertEquals(layout, hidden.withFolderHidden(FolderId("social"), false))
        assertEquals(layout, layout.withFolderHidden(FolderId("missing"), true))
    }
}
