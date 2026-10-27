package com.brightblu.parc.core.data.notifications

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The published [com.brightblu.parc.model.NotificationState]: counts as a `StateFlow`, emitted only
 * when they change, and safe to feed from several listener threads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationUnreadStoreTest {

    private val telegram = "org.telegram.messenger"
    private val whatsapp = "com.whatsapp"
    private val store = NotificationUnreadStore(NotificationTally())

    @Test
    fun `starts empty`() {
        assertEquals(emptyMap<String, Int>(), store.unread.value)
        assertEquals(0, store.countFor(telegram))
    }

    @Test
    fun `posts and removals publish counts`() {
        store.posted(telegram, "t1")
        store.posted(telegram, "t2")
        assertEquals(mapOf(telegram to 2), store.unread.value)
        assertEquals(2, store.countFor(telegram))

        store.removed("t1")
        assertEquals(mapOf(telegram to 1), store.unread.value)

        store.removed("t2")
        assertEquals(emptyMap<String, Int>(), store.unread.value)
        assertEquals(0, store.countFor(telegram))
    }

    @Test
    fun `rebuilt resyncs and cleared forgets`() {
        store.posted(telegram, "stale")
        store.rebuilt(listOf(UnreadKey(whatsapp, "w1")))
        assertEquals(mapOf(whatsapp to 1), store.unread.value)

        store.cleared()
        assertEquals(emptyMap<String, Int>(), store.unread.value)
    }

    @Test
    fun `countFor reads the published snapshot`() {
        store.posted(whatsapp, "w1")
        store.posted(whatsapp, "w2")
        store.posted(telegram, "t1")

        assertEquals(2, store.countFor(whatsapp))
        assertEquals(1, store.countFor(telegram))
        assertEquals(0, store.countFor("com.example.nothing"))
    }

    @Test
    fun `an update emits nothing but a real change does`() = runTest {
        val seen = ArrayList<Map<String, Int>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.unread.collect { seen += it }
        }
        runCurrent()

        store.posted(telegram, "t1")
        store.posted(telegram, "t1") // same key posted again: an update
        store.removed("never-seen") // unknown key: nothing to change
        store.posted(telegram, "t2")
        runCurrent()

        assertEquals(3, seen.size)
        assertEquals(emptyMap<String, Int>(), seen.first())
        assertEquals(mapOf(telegram to 1), seen[1])
        assertEquals(mapOf(telegram to 2), seen.last())
    }

    @Test
    fun `published snapshots are never mutated in place`() {
        store.posted(telegram, "t1")
        val before = store.unread.value

        store.posted(telegram, "t2")

        assertEquals(mapOf(telegram to 1), before)
        assertEquals(mapOf(telegram to 2), store.unread.value)
    }

    @Test
    fun `concurrent listener threads do not lose counts`() {
        // Well inside the tally's per-package cap, so every key must be counted exactly once.
        val threads = 4
        val perThread = 40
        val started = java.util.concurrent.CountDownLatch(1)
        val workers = (0 until threads).map { thread ->
            Thread {
                started.await()
                for (index in 0 until perThread) store.posted(telegram, "t$thread-$index")
            }
        }
        workers.forEach { it.start() }
        started.countDown()
        workers.forEach { it.join() }

        assertEquals(threads * perThread, store.countFor(telegram))
    }

    @Test
    fun `a listener reconnect after a gap drops stale keys`() {
        store.posted(telegram, "gone-while-unbound")
        store.posted(whatsapp, "still-there")

        // The platform hands back everything currently in the shade.
        store.rebuilt(listOf(UnreadKey(whatsapp, "still-there")))

        assertEquals(mapOf(whatsapp to 1), store.unread.value)
        assertFalse(store.unread.value.containsKey(telegram))
        assertTrue(store.unread.value.containsKey(whatsapp))
    }
}
