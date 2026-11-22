package com.brightblu.parc

import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T247: app icons and the long-press "Add to arc" actions in search. */
class T247Test {
    private fun source(path: String) = File(path).readText()

    @Test fun theAppMenuOffersTheRowsInTheCardsOrder() {
        assertEquals(
            listOf("Add to arc", "Add to folder", "App info", "Set alias", "Uninstall", "Hide"),
            SearchAppMenu.actions(canUninstall = true).map { it.label },
        )
        // A preinstalled system app that cannot be uninstalled keeps the other rows.
        assertEquals(
            listOf("Add to arc", "Add to folder", "App info", "Set alias", "Hide"),
            SearchAppMenu.actions(canUninstall = false).map { it.label },
        )
    }

    @Test fun aHiddenFolderIsNotOfferedForAddToFolder() {
        val folders = listOf(Folder(FolderId("one"), "One"), Folder(FolderId("two"), "Two"))
        assertEquals(
            listOf("One"),
            SearchAppMenu.folders(folders, hiddenFolderIds = setOf(FolderId("two"))).map { it.name },
        )
    }

    @Test fun theSearchSheetDrawsTheCachedArcIconAndHomeWiresTheActions() {
        val search = source("../feature/search/src/main/kotlin/com/brightblu/parc/feature/search/SearchSheet.kt")
        assertTrue(search.contains("combinedClickable"))
        assertTrue(search.contains("icon(row.appId)"))
        assertTrue(search.contains("iconRevision"))

        val home = source("src/main/kotlin/com/brightblu/parc/LauncherHome.kt")
        assertTrue(home.contains("icon = sceneIcon"))
        assertTrue(home.contains("iconRevision = revision"))
        assertTrue(home.contains("onAppLongPress ="))
        assertTrue(home.contains("ArcChooserSheet("))
    }
}
