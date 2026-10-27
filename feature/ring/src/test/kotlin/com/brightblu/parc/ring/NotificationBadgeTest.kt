package com.brightblu.parc.ring

import org.junit.Assert.*
import org.junit.Test

class NotificationBadgeTest {
    @Test fun countsUseDotDigitsOrCappedLabel() {
        assertNull(notificationBadgeText(0))
        assertNull(notificationBadgeText(1))
        for (count in 2..9) assertEquals(count.toString(), notificationBadgeText(count))
        assertEquals("9+", notificationBadgeText(10))
        assertEquals("9+", notificationBadgeText(Int.MAX_VALUE))
    }
}
