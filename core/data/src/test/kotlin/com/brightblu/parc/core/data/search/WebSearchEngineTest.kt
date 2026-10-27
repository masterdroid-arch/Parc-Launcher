package com.brightblu.parc.core.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebSearchEngineTest {

    @Test
    fun `google is the default for an unknown or missing id`() {
        assertEquals(WebSearchEngine.Google, WebSearchEngine.fromId(null))
        assertEquals(WebSearchEngine.Google, WebSearchEngine.fromId(""))
        assertEquals(WebSearchEngine.Google, WebSearchEngine.fromId("ask-jeeves"))
    }

    @Test
    fun `a stored id is matched case-insensitively`() {
        assertEquals(WebSearchEngine.DuckDuckGo, WebSearchEngine.fromId("duckduckgo"))
        assertEquals(WebSearchEngine.Startpage, WebSearchEngine.fromId("STARTPAGE"))
    }

    @Test
    fun `every engine builds an https url with the encoded query`() {
        assertEquals(
            "https://www.google.com/search?q=parc%20launcher",
            WebSearchEngine.Google.searchUrl("parc launcher"),
        )
        assertEquals(
            "https://duckduckgo.com/?q=parc%20launcher",
            WebSearchEngine.DuckDuckGo.searchUrl("parc launcher"),
        )
        assertEquals(
            "https://search.brave.com/search?q=parc%20launcher",
            WebSearchEngine.Brave.searchUrl("parc launcher"),
        )
        assertEquals(
            "https://www.startpage.com/sp/search?query=parc%20launcher",
            WebSearchEngine.Startpage.searchUrl("parc launcher"),
        )
    }

    @Test
    fun `reserved characters cannot break out of the query parameter`() {
        val url = WebSearchEngine.Google.searchUrl("a&b=c/d?#")!!
        assertEquals("https://www.google.com/search?q=a%26b%3Dc%2Fd%3F%23", url)
    }

    @Test
    fun `surrounding whitespace is trimmed and a blank query has no url`() {
        assertEquals(
            "https://www.google.com/search?q=abc",
            WebSearchEngine.Google.searchUrl("  abc  "),
        )
        assertNull(WebSearchEngine.Google.searchUrl(""))
        assertNull(WebSearchEngine.Google.searchUrl("   "))
    }

    @Test
    fun `non-latin queries are percent encoded as utf-8`() {
        assertEquals(
            "https://www.google.com/search?q=%D0%BF%D0%B0%D1%80%D0%BA",
            WebSearchEngine.Google.searchUrl("парк"),
        )
    }
}
