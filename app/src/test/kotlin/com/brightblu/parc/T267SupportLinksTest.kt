package com.brightblu.parc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T267: the Support Parc row appears only when a donation link has been set. */
class T267SupportLinksTest {
    @Test fun emptyLinkHidesTheRow() {
        assertFalse(SupportLinks.isVisible(""))
        assertFalse(SupportLinks.isVisible("   "))
        assertFalse(SupportLinks.isVisible("\n\t"))
    }

    @Test fun aSetLinkShowsTheRow() {
        assertTrue(SupportLinks.isVisible("https://example.org/donate"))
    }
}
