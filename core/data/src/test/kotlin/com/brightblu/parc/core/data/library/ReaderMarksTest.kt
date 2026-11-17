package com.brightblu.parc.core.data.library

import org.junit.Assert.*
import org.junit.Test

class ReaderMarksTest {
    private fun highlight(id: String = "h1", start: Int = 2, end: Int = 6,
        tone: ReaderHighlightTone = ReaderHighlightTone.Sand): ReaderHighlight =
        ReaderHighlight(id, 1, start, end, tone, text = "text", createdAt = 10)

    @Test fun storedRangesClampAfterARelayoutWithoutChangingTheirChapterOrOffsets() {
        val item = highlight(start = 4, end = 9)
        assertEquals(4 until 9, ReaderMarksMath.rangeAfterLayout(item, 20))
        assertEquals(4 until 7, ReaderMarksMath.rangeAfterLayout(item, 7))
        assertNull(ReaderMarksMath.rangeAfterLayout(item, 4))
        assertNull(ReaderMarksMath.normalizedRange(0, 0, 2))
        // A re-layout never rewrites the stored range; only the rendered range is clamped.
        assertEquals(4, item.start)
        assertEquals(9, item.end)
        assertEquals(1, item.chapter)
    }

    @Test fun overlappingAndTouchingHighlightsMergeIntoOneRange() {
        val first = highlight("first", 2, 6)
        val second = highlight("second", 5, 9, ReaderHighlightTone.Sage)
        val merged = ReaderMarksMath.addHighlight(listOf(first), second)
        assertEquals(1, merged.size)
        assertEquals(2, merged.single().start)
        assertEquals(9, merged.single().end)
        assertEquals(ReaderHighlightTone.Sage, merged.single().tone)

        val touching = highlight("touching", 9, 12, ReaderHighlightTone.Rose)
        val all = ReaderMarksMath.addHighlight(merged, touching)
        assertEquals(1, all.size)
        assertEquals(2, all.single().start)
        assertEquals(12, all.single().end)

        val far = highlight("far", 20, 22, ReaderHighlightTone.Rose)
        assertEquals(2, ReaderMarksMath.addHighlight(all, far).size)
        assertEquals(listOf(2, 20), ReaderMarksMath.addHighlight(all, far).map { it.start })
    }

    @Test fun addingAnIdenticalRangeReplacesItInsteadOfNestingMarks() {
        val first = highlight("first", 2, 6)
        val same = highlight("second", 3, 5, ReaderHighlightTone.Rose)
        val merged = ReaderMarksMath.addHighlight(listOf(first), same).single()
        assertEquals(2, merged.start)
        assertEquals(6, merged.end)
        assertEquals("second", merged.id)
        assertEquals(ReaderHighlightTone.Rose, merged.tone)
    }

    @Test fun bookmarkToggleIsPerPositionAndPerPdfPage() {
        val text = ReaderBookmark("b1", chapter = 2, characterOffset = 40, page = -1, snippet = "A place")
        assertEquals(listOf(text), ReaderMarksMath.toggleBookmark(emptyList(), text))
        assertTrue(ReaderMarksMath.toggleBookmark(listOf(text), text).isEmpty())
        val other = text.copy(id = "b2", characterOffset = 41)
        assertEquals(2, ReaderMarksMath.toggleBookmark(listOf(text), other).size)
        assertTrue(ReaderMarksMath.hasBookmark(listOf(text), 2, 40, -1))

        val page = ReaderBookmark("p1", page = 7, snippet = "Page 8")
        assertEquals(listOf(page), ReaderMarksMath.toggleBookmark(emptyList(), page))
        assertTrue(ReaderMarksMath.toggleBookmark(listOf(page), page).isEmpty())
        assertTrue(ReaderMarksMath.onPage(listOf(page), 7))
        assertFalse(ReaderMarksMath.onPage(listOf(page), 8))
    }

    @Test fun quoteFieldsUseTheBookAsWorkAndAuthorAndStopAtTwoThousandCharacters() {
        val long = "  " + "a".repeat(2_500) + "  "
        val fields = ReaderMarksMath.quoteFields("  A Book  ", "  A Writer  ", long)
        assertEquals(2_000, fields.text.length)
        assertEquals("A Writer", fields.author)
        assertEquals("A Book", fields.work)
        assertFalse(fields.text.startsWith(" "))
        val small = ReaderMarksMath.quoteFields("Title", "", "  A passage\nwith lines  ")
        assertEquals("A passage\nwith lines", small.text)
        assertEquals("", small.author)
        assertEquals("Title", small.work)
    }

    @Test fun snippetsAreShortAndMarkersDoNotCrashOnShortChapters() {
        val text = "One two three four five six seven eight nine ten"
        assertEquals("three four", ReaderMarksMath.snippet(text, 8, 18))
        assertEquals("", ReaderMarksMath.snippet(text, 0, 0))
        assertEquals("", ReaderMarksMath.snippet("", 0, 1))
        assertTrue(ReaderMarksMath.bookmarkSnippet("x".repeat(1_000), 0).length <= ReaderMarksLimits.MaxSnippet)
    }

    @Test fun documentRoundTripKeepsBothKindsAndDropsEmptyBooks() {
        val doc = ReaderMarksDocument(mapOf(
            "book" to ReaderBookMarks(
                bookmarks = listOf(ReaderBookmark("b", chapter = 1, characterOffset = 2, snippet = "here")),
                highlights = listOf(highlight()),
            ),
        ))
        assertEquals(doc, ReaderMarksJson.decode(ReaderMarksJson.encode(doc)))
        assertEquals(ReaderMarksDocument(), ReaderMarksJson.decode(null))
        assertEquals(ReaderMarksDocument(), ReaderMarksJson.decode(""))
        assertEquals(ReaderMarksDocument(), ReaderMarksJson.decode("not json"))
        assertEquals(ReaderMarksDocument(), ReaderMarksJson.decode("""{"books":{"book":{"bookmarks":[]}}}"""))
    }

    @Test fun notesAreCappedAndCanBeRemoved() {
        val item = highlight()
        val withNote = ReaderMarksMath.setNote(listOf(item), item.id, "a".repeat(9_000)).single()
        assertEquals(ReaderMarksLimits.MaxNote, withNote.note.length)
        assertTrue(ReaderMarksMath.removeHighlight(listOf(withNote), item.id).isEmpty())
        assertTrue(ReaderMarksMath.removeBookmark(listOf(ReaderBookmark("b")), "b").isEmpty())
    }

    @Test fun renderPayloadCarriesOnlyRangesAndTones() {
        val json = ReaderMarksJson.renderHighlights(listOf(highlight(start = 1, end = 3, tone = ReaderHighlightTone.Rose)))
        assertEquals("""[{"c":1,"s":1,"e":3,"t":"rose"}]""", json)
        val bookmarks = ReaderMarksJson.renderBookmarks(listOf(ReaderBookmark("b", 2, 4, snippet = "secret")))
        assertEquals("""[{"c":2,"o":4,"p":-1}]""", bookmarks)
    }
}
