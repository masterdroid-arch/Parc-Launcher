package com.brightblu.parc.feature.search

import com.brightblu.parc.feature.search.extras.ExtraResults
import com.brightblu.parc.feature.search.extras.PhoneSettingHit
import com.brightblu.parc.model.AppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPUS3: the result list starts at the best match, right above the search field. */
class SearchSheetItemsTest {
    private fun row(label: String) = SearchRow(AppId("p.$label/Main@0"), label)

    @Test fun theBestMatchIsTheFirstItemSoItSitsNextToTheField() {
        val rows = listOf(row("Calculator"), row("Calendar"), row("Local News"))
        val items = SearchSheetItems.build("cal", emptyList(), null, rows, emptyList(), ExtraResults.Empty)
        assertEquals(listOf("Calculator", "Calendar", "Local News"),
            items.filterIsInstance<SheetItem.App>().map { it.row.label })
        assertEquals("header-${SearchSheetItems.AppsSection}", items.last().key)
    }

    @Test fun theTopHitsShortcutsFollowItSoTheAppNameStaysNextToTheField() {
        val clock = row("Clock")
        val top = clock.copy(shortcuts = listOf(com.brightblu.parc.model.AppShortcut("alarm", "New alarm", clock.appId),
            com.brightblu.parc.model.AppShortcut("timer", "New timer", clock.appId)))
        val items = SearchSheetItems.build("c", emptyList(), null, listOf(top, row("Calendar")), emptyList(), ExtraResults.Empty)
        assertEquals(listOf(top.appId.value, "shortcut-${top.appId.value}-alarm", "shortcut-${top.appId.value}-timer",
            row("Calendar").appId.value, "header-${SearchSheetItems.AppsSection}"), items.map { it.key })
    }

    @Test fun sectionCaptionsComeAfterTheirRowsSoTheyAreDrawnAboveThem() {
        val extras = ExtraResults(phoneSettings = listOf(PhoneSettingHit("Calls", "android.settings.X", 1.0)))
        val items = SearchSheetItems.build("cal", emptyList(), null, listOf(row("Calendar")), emptyList(), extras)
        assertEquals(listOf(row("Calendar").appId.value, "header-${SearchSheetItems.AppsSection}",
            "extra-setting-android.settings.X", "header-${SearchSheetItems.ActionsSection}"),
            items.map { it.key })
    }

    @Test fun aBlankQueryShowsTheAppSuggestionNextToTheFieldThenTheRememberedSearches() {
        val items = SearchSheetItems.build("", listOf("maps", "cal"), null, listOf(row("Notes")), emptyList(), ExtraResults.Empty)
        assertEquals(listOf(row("Notes").appId.value, "recent-search-maps", "recent-search-cal", "recent-searches-header"),
            items.map { it.key })
        // A typed query hides the remembered searches.
        assertTrue(SearchSheetItems.build("c", listOf("maps"), null, emptyList(), emptyList(), ExtraResults.Empty).isEmpty())
    }

    @Test fun T248TheEmptyFieldGroupsMostUsedThenRecentAppsNearestTheField() {
        val used = row("Maps").copy(isMostUsed = true)
        val recent = row("Music").copy(isRecent = true)
        val items = SearchSheetItems.build("", listOf("cal"), null, listOf(used, recent), emptyList(), ExtraResults.Empty)
        assertEquals(
            listOf(
                used.appId.value,
                "header-${SearchSheetItems.MostUsedSection}",
                recent.appId.value,
                "header-${SearchSheetItems.RecentAppsSection}",
                "recent-search-cal",
                "recent-searches-header",
            ),
            items.map { it.key },
        )
    }

    @Test fun T259TypedResultsAreGroupedAppsNotesFilesActions() {
        val extras = ExtraResults(
            phoneSettings = listOf(PhoneSettingHit("Calls", "android.settings.X", 1.0)),
            files = listOf(com.brightblu.parc.feature.search.extras.FileHit(
                com.brightblu.parc.feature.search.extras.FileRecord(1, "notes.txt", "content://1", null,
                    com.brightblu.parc.feature.search.extras.FileKind.Other), 0.9)),
        )
        val items = SearchSheetItems.build("cal", emptyList(), null, listOf(row("Calendar")), emptyList(), extras,
            notes = listOf(SearchNote("n1", "Call list", "buy milk")))
        assertEquals(
            listOf(
                row("Calendar").appId.value,
                "header-${SearchSheetItems.AppsSection}",
                "note-n1",
                "header-${SearchSheetItems.NotesSection}",
                "extra-file-Other-1",
                "header-${SearchSheetItems.FilesSection}",
                "extra-setting-android.settings.X",
                "header-${SearchSheetItems.ActionsSection}",
            ),
            items.map { it.key },
        )
    }

    @Test fun T259AnEmptyQueryNeverShowsTypedSectionsOrNotes() {
        val items = SearchSheetItems.build("", emptyList(), null, listOf(row("Notes")), emptyList(), ExtraResults.Empty,
            notes = listOf(SearchNote("n1", "Call list", "buy milk")))
        assertEquals(listOf(row("Notes").appId.value), items.map { it.key })
    }

    @Test fun theListJumpsBackToTheBestMatchWhenTheQueryOrTheBestMatchChanges() {
        // the owner's case: the blank sheet started on a recent app; typing must not keep that row in view.
        assertTrue(SearchSheetItems.resetScroll("", "p.Citymapper/Main@0", "c", "p.Calculator/Main@0"))
        assertTrue(SearchSheetItems.resetScroll("ca", "p.Calculator/Main@0", "cal", "p.Calculator/Main@0"))
        assertTrue(SearchSheetItems.resetScroll("cal", "p.Clock/Main@0", "cal", "p.Calendar/Main@0"))
        assertFalse("scrolling by hand is kept while nothing changed",
            SearchSheetItems.resetScroll("cal", "p.Calendar/Main@0", "cal", "p.Calendar/Main@0"))
        assertTrue("the first frame always starts at the top", SearchSheetItems.resetScroll(null, null, "", null))
    }

    @Test fun moreResultsAreCountedAboveTheLastVisibleRow() {
        assertEquals(0, SearchSheetItems.hiddenAbove(0, -1))
        assertEquals(0, SearchSheetItems.hiddenAbove(4, 3))
        assertEquals(6, SearchSheetItems.hiddenAbove(10, 3))
        assertEquals("nothing measured yet: all but the first count", 9, SearchSheetItems.hiddenAbove(10, -1))
    }
}
