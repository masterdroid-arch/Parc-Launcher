package com.brightblu.parc.feature.settings.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Settings-link decision. The Android halves of the helper (`of()` reading the platform grant,
 * `settingsIntent()`) are platform calls and are not asserted here: a JVM unit test of this module
 * cannot execute them, and faking a `Context` would test the fake.
 */
class NotificationAccessLinkTest {

    private val parc = "com.brightblu.parc"

    @Test
    fun `access not granted means the row must offer the page`() {
        val link = NotificationAccessLink(parc) { false }

        assertEquals(NotificationAccessStatus.NeedsAccess, link.status)
    }

    @Test
    fun `access granted means Parc is counting`() {
        val link = NotificationAccessLink(parc) { true }

        assertEquals(NotificationAccessStatus.Granted, link.status)
    }

    @Test
    fun `the state is re-read on every access, never cached`() {
        var granted = false
        val link = NotificationAccessLink(parc) { granted }

        assertEquals(NotificationAccessStatus.NeedsAccess, link.status)
        granted = true
        assertEquals(NotificationAccessStatus.Granted, link.status)
    }

    @Test
    fun `the link needs no context and carries the listener package`() {
        var probes = 0
        val link = NotificationAccessLink(parc) {
            probes++
            false
        }

        assertEquals(NotificationAccessStatus.NeedsAccess, link.status)
        assertEquals(NotificationAccessStatus.NeedsAccess, link.status)
        assertEquals(2, probes)
    }
}
