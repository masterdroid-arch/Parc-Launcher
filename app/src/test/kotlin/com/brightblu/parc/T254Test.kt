package com.brightblu.parc

import com.brightblu.parc.core.data.library.ReaderBookDoc
import com.brightblu.parc.core.data.library.ReaderChapter
import com.brightblu.parc.core.data.library.ReaderSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T254: search inside a book. */
class T254Test {
    private fun doc(vararg texts: String) = ReaderBookDoc("id", "Title", "", texts.mapIndexed { i, t ->
        ReaderChapter(i, "Chapter ${i + 1}", "<p>$t</p>", t)
    }, emptyList())

    @Test
    fun findsEveryMatchCaseInsensitivelyInReadingOrderWithOffsets() {
        val d = doc("The Moon rose. A moon again.", "No match here", "MOONLIGHT")
        val hits = ReaderSearch.inDoc(d, "moon")
        assertEquals(listOf(0 to 4, 0 to 17, 2 to 0), hits.map { it.chapter to it.offset })
        assertEquals("Moon", hits[0].match)
        assertEquals("The ", hits[0].before)
        assertTrue(hits[0].after.startsWith(" rose"))
        assertTrue(ReaderSearch.where(d, hits[0]).startsWith("Chapter 1 · "))
    }

    @Test
    fun accentsMatchInBothDirectionsAndKeepTheSourceOffset() {
        val text = "A manhã chegou. O coração bateu."
        for (query in listOf("manha", "MANHÃ", "manhã")) {
            val hit = ReaderSearch.inText(text, query, 0, 0).single()
            assertEquals("manhã", hit.match)
            assertEquals(text.indexOf("manhã"), hit.offset)
        }
        // A decomposed accent in the book is still one readable word to the reader.
        val decomposed = "manha\u0303"
        val hit = ReaderSearch.inText(decomposed, "manhã", 0, 0).single()
        assertEquals(0, hit.offset)
        assertEquals("manha\u0303", hit.match)
        assertEquals(1, ReaderSearch.inText("Coração", "coracao", 0, 0).size)
        // Offsets after an accented word still point at the exact character on screen.
        val second = ReaderSearch.inText("ação e coração", "coracao", 0, 0).single()
        assertEquals("ação e coração".indexOf("coração"), second.offset)
    }

    @Test
    fun shortQueriesAndMissesGiveNothingAndHitsAreCapped() {
        val d = doc("a b c")
        assertFalse(ReaderSearch.isSearchable(" a "))
        assertTrue(ReaderSearch.inDoc(d, "a").isEmpty())
        assertTrue(ReaderSearch.inDoc(d, "zz").isEmpty())
        val many = doc("ab ".repeat(1000))
        assertEquals(ReaderSearch.MaxResults, ReaderSearch.inDoc(many, "ab").size)
        assertEquals(5, ReaderSearch.inText("ab ".repeat(100), "ab", 0, 7, limit = 5).size)
    }

    @Test
    fun snippetsAreOneLineAndPdfHitsCarryThePage() {
        val hits = ReaderSearch.inText("line one\n\nline   two with needle inside\nmore", "needle", 0, 11)
        assertEquals(1, hits.size)
        assertFalse(hits[0].before.contains('\n'))
        assertFalse(hits[0].after.contains('\n'))
        assertEquals(11, hits[0].page)
        assertEquals("Page 12", ReaderSearch.where(null, hits[0]))
        // Whitespace in the query is tidied to single spaces before searching.
        assertEquals("quiet night", ReaderSearch.clean("  quiet   night "))
        assertEquals(1, ReaderSearch.inText("a quiet night", "quiet   night", 0, 0).size)
    }
}
