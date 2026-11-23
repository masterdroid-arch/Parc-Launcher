package com.brightblu.parc.feature.settings.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T379: the restore confirmation names what it replaces and keeps Cancel beside the action. */
class RestoreCopyTest {
    @Test fun theRestoreConfirmationNamesBothSides() {
        assertEquals("Restore this backup?", RestoreCopy.Title)
        val consequence = RestoreCopy.consequence("3 folders and 2 pins")
        assertTrue(consequence, consequence.contains("current layout and settings"))
        assertTrue(consequence, consequence.contains("3 folders and 2 pins"))
        assertEquals("Restore", RestoreCopy.Confirm)
        assertEquals("Cancel", RestoreCopy.Cancel)
    }
}
