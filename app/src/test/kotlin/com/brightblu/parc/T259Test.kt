package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.model.AppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T259: note rows in Apps search and the alias backup key. */
class T259Test {

    private fun note(id: String, title: String, body: String, updated: Long = 0, pinned: Boolean = false) =
        Note(id = id, text = body, pinned = pinned, updatedAt = updated, title = title)

    @Test fun aTypedSearchFindsNotesByTitleAndByBodyWithoutMarks() {
        val notes = listOf(
            note("a", "Garden", "# Buy **milk** and bread", updated = 20),
            note("b", "Work", "call the bank", updated = 10),
        )
        val hits = NoteSearchHits.of(notes, "milk")
        assertEquals(listOf("a"), hits.map { it.id })
        assertEquals("Garden", hits.single().title)
        assertEquals("Buy milk and bread", hits.single().detail)
    }

    @Test fun theNoteRowDoesNotRepeatItsTitleAsTheDetail() {
        val hits = NoteSearchHits.of(listOf(note("a", "", "buy milk\nsecond line")), "milk")
        assertEquals("buy milk", hits.single().title)
        assertEquals("", hits.single().detail)
    }

    @Test fun atMostThreeNoteRowsAreShown() {
        val notes = (1..5).map { note("n$it", "Note $it", "match $it", updated = it.toLong()) }
        assertEquals(3, NoteSearchHits.of(notes, "match").size)
        assertTrue(NoteSearchHits.of(notes, "  ").isEmpty())
    }

    @Test fun aLongBodyLineIsShortenedForTheCaption() {
        val hits = NoteSearchHits.of(listOf(note("a", "Title", "x".repeat(200))), "x")
        assertTrue(hits.single().detail.length <= NoteSearchHits.MaxDetailChars)
        assertTrue(hits.single().detail.endsWith("…"))
    }

    @Test fun theAliasBackupKeyIsListed() {
        assertNotNull(BackupSpecs.search.key("aliases"))
        assertTrue(BackupSpecs.search.key("aliases")!!.maxLength >= 1_024)
    }

    @Test fun theSearchMenuOffersAnAliasAndCarriesItsCurrentValue() {
        assertEquals(
            listOf("Add to arc", "Add to folder", "App info", "Set alias", "Uninstall", "Hide"),
            SearchAppMenu.actions(canUninstall = true).map { it.label },
        )
        assertEquals("money", SearchAppTarget(AppId("p/Bank@0"), "Bank", true, "money").alias)
    }
}
