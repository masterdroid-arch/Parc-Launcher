package com.brightblu.parc.model

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T403: only a touch Android marked as obscured is dropped; every normal tap still works. */
class T403ObscuredTouchTest {
    @Test fun theMirroredFlagMatchesAndroid() {
        assertEquals(MotionEvent.FLAG_WINDOW_IS_OBSCURED, ObscuredTouch.FLAG_WINDOW_IS_OBSCURED)
    }

    @Test fun aNormalTapIsNotDropped() {
        assertFalse(ObscuredTouch.shouldDrop(0))
    }

    @Test fun anObscuredTouchIsDropped() {
        assertTrue(ObscuredTouch.shouldDrop(MotionEvent.FLAG_WINDOW_IS_OBSCURED))
        assertTrue(ObscuredTouch.shouldDrop(MotionEvent.FLAG_WINDOW_IS_OBSCURED or 0x40))
    }

    @Test fun aPartlyCoveredWindowIsNotDropped() {
        assertFalse(ObscuredTouch.shouldDrop(MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED))
    }

    @Test fun otherFlagsAreIgnored() {
        assertFalse(ObscuredTouch.shouldDrop(0x40))
        assertFalse(ObscuredTouch.shouldDrop(0x7ffffffe))
    }
}
