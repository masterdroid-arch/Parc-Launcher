package com.brightblu.parc

import com.brightblu.parc.feature.search.LabelSearchEngine
import com.brightblu.parc.feature.search.SearchSheetState
import com.brightblu.parc.model.*
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Test

/**
 * T324: Home menu > "Wallpaper and look" opens the picker sheet over Home, while Settings and
 * Settings search still open the full page. The sheet carries the three choices, Dim and the note.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class T324Test {
    private val app = AppId("test/App@0")
    private val alpha = AppEntry(app, "Alpha", "test.alpha", false, 0)

    private class Index(entries: List<AppEntry>) : AppIndex {
        override val apps = MutableStateFlow(entries)
        override fun icon(id: AppId, sizePx: Int): android.graphics.Bitmap? = null
        override fun launch(id: AppId) = Unit
        override fun shortcuts(id: AppId): List<AppShortcut> = emptyList()
    }

    private class Usage : UsageRepository {
        override fun recordLaunch(id: AppId) = Unit
        override fun recents(limit: Int): Flow<List<AppId>> = flowOf(emptyList())
        override fun rank(folderId: FolderId): List<AppId> = emptyList()
    }

    private class Shortcuts : ShortcutHost {
        override fun isDefaultHome(): Boolean = true
        override fun launchShortcut(appId: AppId, shortcutId: String) = Unit
    }

    private inner class Harness(withSheet: Boolean) {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val navigation = ShellNavigation()
        val search = SearchSheetState(LabelSearchEngine(), Index(listOf(alpha)), Usage(), scope)
        val opened = mutableListOf<SettingsTarget>()
        var sheets = 0
        val session = LauncherSession(navigation, Index(listOf(alpha)), Usage(), FakeLayoutRepository(ParcLayout()),
            search, {}, {}, Shortcuts(), scope,
            openSettingsAt = { opened += it },
            onWallpaperSheet = if (withSheet) ({ sheets++ }) else null)
    }

    // ---- The Home menu row --------------------------------------------------------------------

    @Test fun theLookRowOpensTheSheetAndNotTheSettingsPage() {
        val h = Harness(withSheet = true)
        h.session.onEmptyLongPress(120f, 480f)
        h.session.homeMenuAction(HomeMenuRow.Look)
        assertEquals(1, h.sheets)
        assertTrue(h.opened.isEmpty())
        assertNull(h.session.homeMenu.value)
    }

    @Test fun withoutASheetHostTheRowStillOpensTheWallpaperSettingsPage() {
        val h = Harness(withSheet = false)
        h.session.onEmptyLongPress(1f, 1f)
        h.session.homeMenuAction(HomeMenuRow.Look)
        assertEquals(0, h.sheets)
        assertEquals(listOf(SettingsTarget(SettingsPage.AppearanceWallpaper, "WallpaperImage")), h.opened)
    }

    @Test fun theOtherRowsAreUnchanged() {
        val h = Harness(withSheet = true)
        h.session.onEmptyLongPress(1f, 1f)
        h.session.homeMenuAction(HomeMenuRow.Settings)
        assertEquals(0, h.sheets)
        assertEquals(listOf(SettingsTarget(SettingsPage.Root)), h.opened)
    }

    @Test fun settingsSearchStillTargetsThePageRow() {
        // A search result is not the Home menu: it must keep opening the page at the row.
        assertEquals(SettingsTarget(SettingsPage.AppearanceWallpaper, "WallpaperImage"),
            SettingsSearchEntries.target("setting:WallpaperImage"))
    }

    // ---- What the sheet says and shows --------------------------------------------------------

    @Test fun theSheetOffersTheThreeSettingsChoicesInTheSameOrder() {
        assertEquals(listOf(WallpaperStyle.System, WallpaperStyle.Image, WallpaperStyle.Solid),
            WallpaperSheetRules.choices)
        assertEquals("Wallpaper and look", WallpaperSheetRules.Title)
        assertEquals(listOf("My phone’s wallpaper", "A picture I choose", "Just the skin’s colour"),
            WallpaperSheetRules.choices.map(::wallpaperChoiceLabel))
    }

    @Test fun dimIsOfferedOnlyWhenSomethingSitsBehindHome() {
        assertTrue(WallpaperSheetRules.showsDim(WallpaperStyle.System))
        assertTrue(WallpaperSheetRules.showsDim(WallpaperStyle.Image))
        assertFalse(WallpaperSheetRules.showsDim(WallpaperStyle.Solid))
        assertFalse(WallpaperSheetRules.showsDim(WallpaperStyle.Black))
    }

    @Test fun theSystemNoteIsOneCompleteSentenceAndOnlySystemHasOne() {
        val note = WallpaperSheetRules.note(WallpaperStyle.System)
        assertEquals("Parc cannot read the phone wallpaper's picture, so glass frosts its main colour.", note)
        assertTrue(note.endsWith("."))
        assertEquals("", WallpaperSheetRules.note(WallpaperStyle.Image))
        assertEquals("", WallpaperSheetRules.note(WallpaperStyle.Solid))
    }
}
