package com.brightblu.parc.ring

import org.junit.Assert.*
import org.junit.Test

class FolderTransitionTest {
    @Test fun emphasisIsClampedProgress() {
        assertEquals(0f, FolderTransition.emphasis(-0.2f), 0f)
        assertEquals(0.4f, FolderTransition.emphasis(0.4f), 0f)
        assertEquals(1f, FolderTransition.emphasis(1.08f), 0f)
    }
}
