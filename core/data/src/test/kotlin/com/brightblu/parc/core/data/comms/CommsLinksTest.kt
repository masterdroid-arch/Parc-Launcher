package com.brightblu.parc.core.data.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommsLinksTest {

    private fun spans(text: String): List<String> =
        CommsLinks.extract(text).map { text.substring(it.start, it.endExclusive) }

    @Test
    fun `http https and www links are found in order`() {
        val text = "http://a.test then https://b.test/x and www.c.test/y"
        assertEquals(
            listOf("http://a.test", "https://b.test/x", "www.c.test/y"),
            spans(text),
        )
    }

    @Test
    fun `sentence punctuation is not part of the link`() {
        val text = "Look at https://example.com/page, then go. Or www.example.org!"
        assertEquals(
            listOf("https://example.com/page", "www.example.org"),
            spans(text),
        )
    }

    @Test
    fun `a balanced bracket inside a URL is kept while an unmatched closing bracket is trimmed`() {
        assertEquals(
            listOf("https://en.wikipedia.org/wiki/Parc_(launcher)"),
            spans("see https://en.wikipedia.org/wiki/Parc_(launcher)"),
        )
        assertEquals(
            listOf("https://example.com/a"),
            spans("(https://example.com/a)"),
        )
    }

    @Test
    fun `uppercase schemes match and ranges are exact`() {
        val text = "HTTPS://EXAMPLE.COM/X"
        val span = CommsLinks.extract(text).single()
        assertEquals(0, span.start)
        assertEquals(text.length, span.endExclusive)
        assertEquals(text, text.substring(span.start, span.endExclusive))
    }

    @Test
    fun `offsets stay correct after surrogate pairs`() {
        val text = "😀 https://example.com/a"
        val span = CommsLinks.extract(text).single()
        assertEquals(3, span.start)
        assertEquals("https://example.com/a", text.substring(span.start, span.endExclusive))
    }

    @Test
    fun `a bare domain or a word starting with www without a dot is not a link`() {
        assertTrue(CommsLinks.extract("example.com and www and http://").isEmpty())
    }

    @Test
    fun `a message with no link returns no spans`() {
        assertTrue(CommsLinks.extract("").isEmpty())
        assertTrue(CommsLinks.extract("hello there").isEmpty())
    }

    @Test
    fun `multiple links on one line keep separate spans`() {
        val text = "a https://one.test b www.two.test c"
        val found = CommsLinks.extract(text)
        assertEquals(2, found.size)
        assertEquals("https://one.test", text.substring(found[0].start, found[0].endExclusive))
        assertEquals("www.two.test", text.substring(found[1].start, found[1].endExclusive))
        assertTrue(found[0].endExclusive <= found[1].start)
    }
}
