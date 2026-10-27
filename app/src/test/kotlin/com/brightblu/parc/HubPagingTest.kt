package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** OPUS1: hub pages loop when swiped past either end. */
class HubPagingTest {
    private val three = listOf("clock", "system", "power")

    @Test fun forwardMovesToTheNextPage() {
        assertEquals("system", hubPageAfterSwipe(three, "clock", forward = true))
        assertEquals("power", hubPageAfterSwipe(three, "system", forward = true))
    }

    @Test fun forwardFromTheLastPageWrapsToTheFirst() {
        assertEquals("clock", hubPageAfterSwipe(three, "power", forward = true))
    }

    @Test fun backFromTheFirstPageWrapsToTheLast() {
        assertEquals("power", hubPageAfterSwipe(three, "clock", forward = false))
        assertEquals("clock", hubPageAfterSwipe(three, "system", forward = false))
    }

    @Test fun onePageStaysPut() {
        assertEquals("clock", hubPageAfterSwipe(listOf("clock"), "clock", forward = true))
        assertEquals("clock", hubPageAfterSwipe(listOf("clock"), "clock", forward = false))
    }

    @Test fun twoPagesAlternateBothWays() {
        val two = listOf("clock", "power")
        assertEquals("power", hubPageAfterSwipe(two, "clock", forward = true))
        assertEquals("clock", hubPageAfterSwipe(two, "power", forward = true))
        assertEquals("power", hubPageAfterSwipe(two, "clock", forward = false))
        assertEquals("clock", hubPageAfterSwipe(two, "power", forward = false))
    }

    @Test fun noPagesGivesNothingAndUnknownStartsAtTheFirst() {
        assertNull(hubPageAfterSwipe(emptyList(), "clock", forward = true))
        assertEquals("clock", hubPageAfterSwipe(three, "notes", forward = false))
        assertEquals("clock", hubPageAfterSwipe(three, null, forward = true))
    }

    @Test fun followsTheSavedOrderAndAFullLoopReturnsHome() {
        val reordered = HubConfig().movePage("power", -99).pages
        var page: String? = reordered.first()
        repeat(reordered.size) { page = hubPageAfterSwipe(reordered, page, forward = true) }
        assertEquals(reordered.first(), page)
        assertEquals(reordered.last(), hubPageAfterSwipe(reordered, reordered.first(), forward = false))
    }
}
