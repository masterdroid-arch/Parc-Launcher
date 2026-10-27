package com.brightblu.parc.core.data.comms

import org.junit.Assert.assertEquals
import org.junit.Test

/** The card's four defaults and the one sanitising rule shared by store, repository and editor. */
class CommsCannedRepliesTest {

    @Test
    fun `the card defaults are the four named replies in order`() {
        assertEquals(
            listOf("On my way", "Call you later", "Thanks", "OK"),
            CommsCannedReplies.Default,
        )
        assertEquals(8, CommsCannedReplies.Max)
    }

    @Test
    fun `sanitize trims, drops blanks and keeps the given order`() {
        val cleaned = CommsCannedReplies.sanitize(
            listOf("  On my way  ", "", "   ", "Thanks"),
        )

        assertEquals(listOf("On my way", "Thanks"), cleaned)
    }

    @Test
    fun `exact duplicates are dropped but the first occurrence keeps its place`() {
        val cleaned = CommsCannedReplies.sanitize(listOf("OK", "On my way", "OK", "Thanks"))

        assertEquals(listOf("OK", "On my way", "Thanks"), cleaned)
    }

    @Test
    fun `at most eight replies survive`() {
        val cleaned = CommsCannedReplies.sanitize((1..12).map { "reply $it" })

        assertEquals(8, cleaned.size)
        assertEquals("reply 1", cleaned.first())
        assertEquals("reply 8", cleaned.last())
    }

    @Test
    fun `a line break is collapsed so one reply stays one stored line`() {
        val cleaned = CommsCannedReplies.sanitize(listOf("On my\r\nway\nnow"))

        assertEquals(listOf("On my  way now"), cleaned)
    }

    @Test
    fun `an empty list is a valid choice and stays empty`() {
        assertEquals(emptyList<String>(), CommsCannedReplies.sanitize(emptyList()))
        assertEquals(emptyList<String>(), CommsCannedReplies.sanitize(listOf("  ", "")))
    }
}
