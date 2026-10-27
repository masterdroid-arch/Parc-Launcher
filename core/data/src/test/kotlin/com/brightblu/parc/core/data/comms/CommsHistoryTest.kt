package com.brightblu.parc.core.data.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-memory history: bounded at 100 messages, gated by the `Keep history` switch and the
 * allow-list, cleared on demand, and never written anywhere. Fake notifications go through the real
 * parser, so the conversation identity used here is the one the Comms page sees.
 */
class CommsHistoryTest {

    private val parser = CommsParser()
    private val policy = CommsPolicy()
    private var now = 10_000L

    private fun history(max: Int = CommsHistory.DefaultMaxMessages) =
        CommsHistory(clock = { now }, maxMessages = max)

    private fun parsed(
        key: String = "n1",
        app: String = CommsPackages.Telegram,
        title: String = "Sam",
        texts: List<String> = listOf("hello"),
    ): ParsedNotification {
        val messages = texts.mapIndexed { index, text ->
            rawMessage(sender = title, text = text, timeMs = 1_000L + index)
        }
        return requireNotNull(
            parser.parse(
                rawNotification(
                    packageName = app,
                    key = key,
                    title = title,
                    text = messages.last().text,
                    postTimeMs = messages.last().timeMs,
                    messages = messages,
                ),
                policy,
            ),
        )
    }

    @Test
    fun `the history is on and empty by default`() {
        val history = history()

        assertTrue(history.history.value.isEmpty())
        assertTrue(history.record(parsed()))
        assertEquals(1, history.history.value.size)
    }

    @Test
    fun `a recorded notification keeps its conversation, title, message and removal time`() {
        val history = history()

        history.record(parsed(key = "n1", title = "Sam", texts = listOf("first")))

        val entry = history.history.value.single()
        assertEquals(CommsConversationId(CommsPackages.Telegram, "Sam"), entry.conversation)
        assertEquals("Sam", entry.title)
        assertEquals("first", entry.message.text)
        assertEquals("n1", entry.message.notificationKey)
        assertEquals(10_000L, entry.removedAtMs)
    }

    @Test
    fun `every message of one notification is filed and the newest removal comes first`() {
        val history = history()
        now = 1L
        history.record(parsed(key = "n1", texts = listOf("one", "two")))
        now = 2L
        history.record(
            parsed(
                key = "n2",
                app = CommsPackages.WhatsApp,
                title = "Mum",
                texts = listOf("three"),
            ),
        )

        val texts = history.history.value.map { it.message.text }
        assertEquals(listOf("three", "two", "one"), texts)
        assertEquals(2L, history.history.value.first().removedAtMs)
    }

    @Test
    fun `the same notification reported twice is filed once`() {
        val history = history()
        val notification = parsed(key = "n1", texts = listOf("hello"))

        assertTrue(history.record(notification))
        now = 20_000L
        assertFalse(history.record(notification))

        assertEquals(1, history.history.value.size)
        assertEquals(10_000L, history.history.value.single().removedAtMs)
    }

    @Test
    fun `the last hundred messages win`() {
        val history = history()
        for (index in 1..105) {
            history.record(parsed(key = "n$index", texts = listOf("message $index")))
        }

        val texts = history.history.value.map { it.message.text }
        assertEquals(CommsHistory.DefaultMaxMessages, texts.size)
        assertEquals("message 105", texts.first())
        assertEquals("message 6", texts.last())
    }

    @Test
    fun `a small ceiling is honoured per message, not per notification`() {
        val history = history(max = 2)

        history.record(parsed(key = "n1", texts = listOf("one", "two", "three")))

        assertEquals(listOf("three", "two"), history.history.value.map { it.message.text })
    }

    @Test
    fun `turning the switch off clears what is held and ignores later removals`() {
        val history = history()
        history.record(parsed())

        assertTrue(history.setEnabled(false))
        assertTrue(history.history.value.isEmpty())
        assertFalse(history.record(parsed(key = "n2", texts = listOf("after"))))

        history.setEnabled(true)
        assertTrue(history.record(parsed(key = "n3", texts = listOf("back"))))
    }

    @Test
    fun `a package removed from the allow list loses its history`() {
        val history = history()
        history.record(parsed(key = "t1"))
        history.record(parsed(key = "w1", app = CommsPackages.WhatsApp, title = "Mum"))

        assertTrue(history.setAllowedPackages(setOf(CommsPackages.WhatsApp)))

        assertEquals(
            listOf(CommsPackages.WhatsApp),
            history.history.value.map { it.conversation.app },
        )
    }

    @Test
    fun `a notification from an app outside the allow list is never filed`() {
        val history = history()
        history.setAllowedPackages(setOf(CommsPackages.Signal))

        assertFalse(history.record(parsed(app = CommsPackages.Telegram)))

        assertTrue(history.history.value.isEmpty())
    }

    @Test
    fun `clear empties the history once`() {
        val history = history()
        history.record(parsed())

        assertTrue(history.clear())
        assertFalse(history.clear())

        assertTrue(history.history.value.isEmpty())
    }
}
