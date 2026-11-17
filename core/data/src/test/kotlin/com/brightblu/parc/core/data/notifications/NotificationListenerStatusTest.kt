package com.brightblu.parc.core.data.notifications

import com.brightblu.parc.core.data.comms.CommsDropReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** T213: the in-memory, package-only listener status behind the Messages footer and Why sheet. */
class NotificationListenerStatusTest {

    @Before fun reset() = NotificationListenerStatus.clear()
    @After fun clean() = NotificationListenerStatus.clear()

    @Test
    fun `connect records the time and disconnect forgets it`() {
        NotificationListenerStatus.onConnected(nowMs = 1_720_000_000_000L)
        assertTrue(NotificationListenerStatus.connected)
        assertEquals(1_720_000_000_000L, NotificationListenerStatus.connectedAtMs)

        NotificationListenerStatus.onDisconnected()
        assertFalse(NotificationListenerStatus.connected)
        assertNull(NotificationListenerStatus.connectedAtMs)
    }

    @Test
    fun `the drop ring keeps the last five newest first and only package plus reason`() {
        for (index in 1..6) {
            NotificationListenerStatus.recordDrop("com.example.$index", CommsDropReason.Category)
        }

        val drops = NotificationListenerStatus.lastDropReasons
        assertEquals(NotificationListenerStatus.MaxDropReasons, drops.size)
        assertEquals(
            listOf("com.example.6", "com.example.5", "com.example.4", "com.example.3", "com.example.2"),
            drops.map { it.packageName },
        )
        assertTrue(drops.all { it.reason == CommsDropReason.Category })
    }

    @Test
    fun `blank packages are ignored and clear removes everything`() {
        NotificationListenerStatus.recordDrop("   ", CommsDropReason.Blank)
        assertTrue(NotificationListenerStatus.lastDropReasons.isEmpty())

        NotificationListenerStatus.recordDrop("com.example.chat", CommsDropReason.NotAllowed)
        NotificationListenerStatus.clear()
        assertTrue(NotificationListenerStatus.lastDropReasons.isEmpty())
        assertFalse(NotificationListenerStatus.connected)
    }
}
