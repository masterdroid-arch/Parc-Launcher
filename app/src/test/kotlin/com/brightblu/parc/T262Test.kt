package com.brightblu.parc

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T262: the small regressions that have pure decisions, plus guards on the wiring they sit in. */
class T262Test {
    private fun source(path: String) = File("src/main/kotlin/com/brightblu/parc/$path").readText()

    @Test fun clockKeepsAlarmsAndTheFocusChipMovesElsewhere() {
        assertEquals(HubGripStart.Alarms, HubGripPriority.start(TodayPage.Id, focusVisible = true))
        assertEquals(HubGripStart.Alarms, HubGripPriority.start(TodayPage.Id, focusVisible = false))
        assertEquals(HubGripStart.Alarms, HubGripPriority.start("clock", focusVisible = true))
        assertEquals(HubGripStart.Focus, HubGripPriority.start(DevicePage.Id, focusVisible = true))
        assertEquals(HubGripStart.None, HubGripPriority.start(DevicePage.Id, focusVisible = false))
        assertEquals(HubGripStart.None, HubGripPriority.start(null, focusVisible = false))
    }

    @Test fun startupSnapshotAppsWinUntilTheLiveIndexArrives() {
        val restored = listOf(AppEntry(AppId("a/.Main"), "A", "a", false, 0))
        val indexed = listOf(AppEntry(AppId("b/.Main"), "B", "b", false, 0))
        assertEquals(restored, StartupPresentation.apps(restored, indexed))
        assertEquals(indexed, StartupPresentation.apps(emptyList(), indexed))
        assertTrue(StartupPresentation.apps(emptyList(), emptyList()).isEmpty())
        val graph = source("AppGraph.kt")
        assertTrue(graph.contains("StartupPresentation.apps(restored?.apps.orEmpty(), appIndex.apps.value)"))
        assertTrue(graph.contains("appIndex.preload(seed.map { it.id }, Metrics.iconBitmapPx)"))
    }

    @Test fun scrollHintFollowsTheBottomFade() {
        assertTrue(HubPageScroll.scrollHintVisible(0, 100))
        assertTrue(HubPageScroll.scrollHintVisible(99, 100))
        assertFalse(HubPageScroll.scrollHintVisible(100, 100))
        assertFalse(HubPageScroll.scrollHintVisible(0, 0))
    }

    @Test fun demoFilesListInventedFiles() {
        assertTrue(FilesSearchAccess.initialGranted(demo = true))
        assertTrue(source("FilesSearchMode.kt").contains("FilesSearchAccess.initialGranted(DemoMode.enabled)"))
    }

    @Test fun messagesFooterCallsKeptHistoryStored() {
        val footer = CommsPresentation.footer(null, accessOn = true, live = 1, stored = 3)
        assertTrue(footer.endsWith("1 live · 3 stored"))
        assertFalse(footer.contains("dismissed"))
    }

    @Test fun searchKeyboardUsesTheImeInsetAndHidingItDoesNotCloseTheSheet() {
        val sheet = File("../feature/search/src/main/kotlin/com/brightblu/parc/feature/search/SearchSheet.kt").readText()
        assertFalse(sheet.contains("if (imeVisible) Modifier.imePadding() else Modifier"))
        assertTrue(sheet.contains(".imePadding()"))
        val session = source("LauncherSession.kt")
        assertTrue(session.contains("navigation.show(current.copy(keyboardOpen = visible"))
        assertFalse(session.contains("if (!visible && current.keyboardOpen && search.isOpen"))
    }

    @Test fun searchSheetAndMessagesCardsFollowTheGlassRule() {
        val home = source("LauncherHome.kt")
        assertTrue(home.contains("val sheetSkin = remember(hubCards.stats)"))
        val comms = source("CommsPage.kt")
        assertTrue(comms.contains("commsCardSurface(skin)"))
        assertTrue(comms.contains("frostedBackground(skin, skin.shape.surfaceCornerDp)"))
    }

    @Test fun hubsSettingsScrollLivesBeyondThePageSwitch() {
        val settings = source("ParcSettings.kt")
        val state = settings.indexOf("val hubPagesScroll = rememberScrollState()")
        val page = settings.indexOf("SettingsPage.HubPages ->")
        assertTrue(state > 0)
        assertTrue(page > state)
        assertFalse(settings.contains("val statsScroll = rememberScrollState()"))
    }

    @Test fun findBooksPickerIsStillTheFirstStepWithoutAccess() {
        val library = source("LibraryPage.kt")
        assertTrue(library.contains("if (saved.isEmpty()) showFolders = true"))
        // The all-files settings screen is reachable only from the chooser's explicit action row.
        val chooser = library.substringAfter("private fun FolderChooserDialog(")
        assertTrue(chooser.contains("LibraryAction(\"Allow file access\""))
        assertFalse(library.substringAfter("internal fun FindBooksDialog(").substringBefore("private fun FindBooksBody")
            .contains("ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION"))
    }
}
