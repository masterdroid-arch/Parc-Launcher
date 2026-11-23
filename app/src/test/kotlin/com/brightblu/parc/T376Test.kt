package com.brightblu.parc

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T376: one page sheet combining the tab menu and page options, shared with Wallpaper and Add to arc. */
class T376Test {
    private fun source(path: String) = File(path).readText()

    @Test fun thePageSheetCombinesShowSizeMoveUndoAndDoneInOneSurface() {
        assertEquals("Page settings", HubPageSheet.Subtitle)
        val options = source("src/main/kotlin/com/brightblu/parc/HubPageOptions.kt")
        // One sheet keeps Show page, inherited-or-local size, move, Undo and Done together.
        assertTrue(options.contains("SettingsSheet(hubPageName(canonical)"))
        assertTrue(options.contains("subtitle = HubPageSheet.Subtitle"))
        listOf("Show on Home", "Undo", "Move earlier", "Move later", "HubPageSizeChoices").forEach {
            assertTrue(it, options.contains(it))
        }
        // The tab menu and the full-screen page dialog are gone; both routes open the same sheet.
        assertTrue(!options.contains("HubPageQuickSheet"))
        assertTrue(!options.contains("ParcDialogArea"))
    }

    @Test fun addToArcAndWallpaperShareTheSameSheetScaffold() {
        val arc = source("src/main/kotlin/com/brightblu/parc/ArcChooserSheet.kt")
        assertTrue(arc.contains("SettingsSheet("))
        assertTrue(arc.contains("ArcChooser.fullSubtitle"))
        assertTrue(arc.contains("\"Full\""))

        val wallpaper = source("src/main/kotlin/com/brightblu/parc/WallpaperSheet.kt")
        assertTrue(wallpaper.contains("SettingsSheet("))
    }

    @Test fun theQuickSheetIsGoneAndTheHoldOpensTheCombinedSheet() {
        val home = source("src/main/kotlin/com/brightblu/parc/HubPages.kt")
        assertTrue(!home.contains("HubPageQuickSheet"))
        assertTrue(home.contains("onHold = if (demo) null else { id -> optionsPage = id }"))
        assertTrue(!home.contains("quickPage"))
    }
}
