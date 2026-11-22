package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T314: a screen-level test counting editable fields, at the JVM level. The repo has no Compose UI
 * test harness (adding one needs a library outside PROJECT.md §6.1), so the screen's editable-field
 * count is the pure [searchEditableFieldCount] and the pull bar's visibility is
 * [pullPreviewProgress]. These are the exact functions the UI calls, so the test walks the same
 * transitions a screen test would and asserts the invariant: the pull bar is never an editable
 * field, and the search sheet owns the only one.
 */
class SearchFieldCountTest {

    /** The states the card names, in order, plus the pull progress each transition leaves behind. */
    @Test fun slowPullReleaseTypingAllAppsBackAndReopen() {
        // Slow pull: finger mid-drag, search not yet open. Preview visible, no editable field.
        val pullProgress = 0.5f
        assertTrue(pullPreviewProgress(searchOpen = false, pullProgress) > 0f)
        assertEquals(0, searchEditableFieldCount(searchOpen = false, abcListOpen = false))

        // Release (committed): search opens, the sheet owns the field, the preview is gone.
        assertTrue(pullPreviewProgress(searchOpen = true, 1f) == 0f)
        assertEquals(1, searchEditableFieldCount(searchOpen = true, abcListOpen = false))

        // Typing: still one field.
        assertEquals(1, searchEditableFieldCount(searchOpen = true, abcListOpen = false))

        // Alphabetical All apps: the sheet is replaced by the list, which has no field.
        assertEquals(0, searchEditableFieldCount(searchOpen = true, abcListOpen = true))

        // Back: search closes; no field, no preview.
        assertEquals(0, searchEditableFieldCount(searchOpen = false, abcListOpen = false))
        assertTrue(pullPreviewProgress(searchOpen = false, 0f) == 0f)

        // Reopening at once: one field again.
        assertEquals(1, searchEditableFieldCount(searchOpen = true, abcListOpen = false))
    }

    @Test fun pullPreviewIsOnlyShownOutsideSearch() {
        // During the pull and its cancelled return the preview follows progress...
        assertEquals(0.3f, pullPreviewProgress(false, 0.3f), 0f)
        assertEquals(0.9f, pullPreviewProgress(false, 0.9f), 0f)
        // ...but the moment search opens it is gone, even at full progress.
        assertEquals(0f, pullPreviewProgress(true, 1f), 0f)
        assertEquals(0f, pullPreviewProgress(true, 0.5f), 0f)
    }

    @Test fun previewIsNeverAnEditableField() {
        // The pull bar renders a plain label in every state; it never contributes a text field.
        assertEquals(0, searchEditableFieldCount(searchOpen = false, abcListOpen = false))
        assertEquals(1, searchEditableFieldCount(searchOpen = true, abcListOpen = false))
        // There is no state in which the pull bar adds a field on top of the sheet's one.
        assertEquals(0, searchEditableFieldCount(searchOpen = true, abcListOpen = true))
    }
}
