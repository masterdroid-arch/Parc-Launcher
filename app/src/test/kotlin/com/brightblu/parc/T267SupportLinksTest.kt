package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T267 + T309: the Support Parc row appears only when a donation link has been set. */
class T267SupportLinksTest {
    @Test fun emptyLinkHidesTheRow() {
        assertFalse(SupportLinks.isVisible(""))
        assertFalse(SupportLinks.isVisible("   "))
        assertFalse(SupportLinks.isVisible("\n\t"))
    }

    @Test fun aSetLinkShowsTheRow() {
        assertTrue(SupportLinks.isVisible("https://example.org/donate"))
        assertTrue(SupportLinks.isVisible(SupportLinks.donate))
    }

    @Test fun donateLinkIsTheKoFiPageOverHttps() {
        assertEquals("https://ko-fi.com/parcapp", SupportLinks.donate)
        assertTrue(SupportLinks.donate.startsWith("https://"))
    }

    @Test fun donateNoteIsThePlainHonestWording() {
        assertEquals(
            "Parc is free and always will be. If it helps you, you can leave a tip on Ko-fi. Nothing is locked behind it.",
            SupportLinks.donateNote
        )
    }
}
