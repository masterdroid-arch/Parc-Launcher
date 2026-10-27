package com.brightblu.parc

import org.junit.Assert.*
import org.junit.Test

class T38Test {
    @Test fun hubPushCannotBecomeSearchButOrdinaryDownwardSwipeStillCan() {
        val swipe = SearchSwipe()
        swipe.start(336f, 773f, fromHub = true)
        assertFalse(swipe.move(336f, 840f, false))
        assertFalse(swipe.scroll(80f, 0f))
        swipe.start(200f, 300f)
        assertTrue(swipe.move(200f, 370f, false))
    }
}
