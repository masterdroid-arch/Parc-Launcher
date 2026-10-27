package com.brightblu.parc.core.data.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The counting rule of the listener: what a package's unread count is after posts, updates,
 * removals, resyncs and a runaway app. Pure, so every acceptance-relevant case is pinned here
 * instead of on a device.
 */
class NotificationTallyTest {

    private val telegram = "org.telegram.messenger"
    private val whatsapp = "com.whatsapp"
    private val tally = NotificationTally()

    @Test
    fun `counts one notification per package`() {
        tally.post(telegram, "t1", unread = true)
        tally.post(telegram, "t2", unread = true)
        tally.post(whatsapp, "w1", unread = true)

        assertEquals(mapOf(telegram to 2, whatsapp to 1), tally.counts())
    }

    @Test
    fun `re-posting the same key is an update, not a second notification`() {
        assertTrue(tally.post(telegram, "t1", unread = true))
        assertFalse(tally.post(telegram, "t1", unread = true))
        tally.post(telegram, "t1", unread = true)

        assertEquals(mapOf(telegram to 1), tally.counts())
    }

    @Test
    fun `removal decrements and drops an empty package`() {
        tally.post(telegram, "t1", unread = true)
        tally.post(telegram, "t2", unread = true)
        tally.post(whatsapp, "w1", unread = true)

        assertTrue(tally.remove("t1"))
        assertEquals(mapOf(telegram to 1, whatsapp to 1), tally.counts())

        assertTrue(tally.remove("t2"))
        assertEquals(mapOf(whatsapp to 1), tally.counts())
    }

    @Test
    fun `removal is idempotent and never underflows`() {
        tally.post(telegram, "t1", unread = true)

        assertTrue(tally.remove("t1"))
        assertFalse(tally.remove("t1"))
        assertFalse(tally.remove("never-seen"))

        assertEquals(emptyMap<String, Int>(), tally.counts())
        assertEquals(0, tally.size())
    }

    @Test
    fun `a group summary and an ongoing notification do not count`() {
        tally.post(telegram, "summary", unread = false)
        tally.post("com.android.systemui", "music", unread = false)
        tally.post(telegram, "child", unread = true)

        assertEquals(mapOf(telegram to 1), tally.counts())
    }

    @Test
    fun `an update that turns a notification non-unread un-counts it`() {
        tally.post(telegram, "t1", unread = true)
        tally.post(telegram, "t2", unread = true)

        assertTrue(tally.post(telegram, "t1", unread = false))
        assertEquals(mapOf(telegram to 1), tally.counts())

        // Removing a notification that was already un-counted is a no-op.
        assertFalse(tally.remove("t1"))
        assertEquals(mapOf(telegram to 1), tally.counts())
    }

    @Test
    fun `the same key under another package moves instead of double counting`() {
        tally.post(telegram, "shared", unread = true)
        tally.post(whatsapp, "shared", unread = true)

        assertEquals(mapOf(whatsapp to 1), tally.counts())
    }

    @Test
    fun `replaceAll drops keys that disappeared while unbound`() {
        tally.post(telegram, "stale", unread = true)
        tally.post(whatsapp, "stale-too", unread = true)

        tally.replaceAll(
            listOf(UnreadKey(telegram, "fresh"), UnreadKey(telegram, "fresh-2")),
        )

        assertEquals(mapOf(telegram to 2), tally.counts())
        assertFalse(tally.remove("stale"))
        assertFalse(tally.remove("stale-too"))
    }

    @Test
    fun `clear forgets everything`() {
        tally.post(telegram, "t1", unread = true)
        tally.clear()

        assertEquals(emptyMap<String, Int>(), tally.counts())
        assertEquals(0, tally.size())
    }

    @Test
    fun `a package is capped and stops growing`() {
        val small = NotificationTally(maxPerPackage = 3)
        for (index in 1..10) small.post(telegram, "t$index", unread = true)

        assertEquals(mapOf(telegram to 3), small.counts())
        assertEquals(3, small.size())

        // Keys already counted stay removable, so the count can still go down.
        assertTrue(small.remove("t1"))
        assertEquals(mapOf(telegram to 2), small.counts())
    }

    @Test
    fun `the snapshot is a copy`() {
        tally.post(telegram, "t1", unread = true)
        val snapshot = tally.counts() as MutableMap

        tally.post(whatsapp, "w1", unread = true)
        snapshot["injected"] = 99

        assertEquals(mapOf(telegram to 1, whatsapp to 1), tally.counts())
    }

    @Test
    fun `an empty key is ignored`() {
        assertFalse(tally.post(telegram, "", unread = true))
        assertEquals(emptyMap<String, Int>(), tally.counts())
    }

    @Test
    fun `package order follows first appearance`() {
        tally.post(whatsapp, "w1", unread = true)
        tally.post(telegram, "t1", unread = true)
        tally.post(whatsapp, "w2", unread = true)

        assertEquals(listOf(whatsapp, telegram), tally.counts().keys.toList())
    }
}
