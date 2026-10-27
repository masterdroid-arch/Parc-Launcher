package com.brightblu.parc.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderBadgeTest {
    @Test fun aFolderShowsItsNumberFromTheFirstNotification() {
        assertNull(folderBadgeText(0))
        assertNull(folderBadgeText(-3))
        assertEquals("1", folderBadgeText(1))
        assertEquals("9", folderBadgeText(9))
        assertEquals("9+", folderBadgeText(10))
    }
}
