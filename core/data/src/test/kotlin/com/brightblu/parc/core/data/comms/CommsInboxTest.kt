package com.brightblu.parc.core.data.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CommsInboxTest {

    private val telegram = CommsPackages.Telegram

    private fun message(key: String, text: String, time: Long, sender: String = "Sam") =
        rawNotification(
            key = key,
            title = sender,
            text = text,
            postTimeMs = time,
        )

    @Test
    fun `two notifications with the same conversation key become one conversation`() {
        val inbox = CommsInbox()
        inbox.post(message("n1", "first", 1_000L))
        inbox.post(message("n2", "second", 2_000L))

        val conversation = inbox.conversations.value.single()
        assertEquals(CommsConversationId(telegram, "Sam"), conversation.id)
        assertEquals(listOf("first", "second"), conversation.messages.map { it.text })
        assertEquals(2_000L, conversation.updatedAtMs)
    }

    @Test
    fun `updating a notification key replaces its contribution instead of duplicating it`() {
        val inbox = CommsInbox()
        inbox.post(message("n1", "old", 1_000L))
        inbox.post(message("n1", "new", 2_000L))

        val conversation = inbox.conversations.value.single()
        assertEquals(listOf("new"), conversation.messages.map { it.text })
    }

    @Test
    fun `removing a notification drops only its messages`() {
        val inbox = CommsInbox()
        inbox.post(message("n1", "first", 1_000L))
        inbox.post(message("n2", "second", 2_000L))

        assertTrue(inbox.remove("n1"))
        assertFalse(inbox.remove("n1"))
        assertEquals(listOf("second"), inbox.conversations.value.single().messages.map { it.text })
    }

    @Test
    fun `conversations are newest first`() {
        val inbox = CommsInbox()
        inbox.post(message("a", "from a", 1_000L, sender = "Ana"))
        inbox.post(message("b", "from b", 5_000L, sender = "Ben"))
        inbox.post(message("c", "from a again", 3_000L, sender = "Ana"))

        assertEquals(listOf("Ben", "Ana"), inbox.conversations.value.map { it.title })
    }

    @Test
    fun `rebuild replaces the whole inbox and returns the accepted keys`() {
        val inbox = CommsInbox()
        inbox.post(message("old", "stale", 1_000L))
        val accepted = inbox.rebuild(listOf(message("new", "fresh", 2_000L)))

        assertEquals(setOf("new"), accepted)
        assertEquals(listOf("fresh"), inbox.conversations.value.single().messages.map { it.text })
    }

    @Test
    fun `clear empties the snapshot`() {
        val inbox = CommsInbox()
        inbox.post(message("n1", "hello", 1_000L))
        assertTrue(inbox.clear())
        assertFalse(inbox.clear())
        assertTrue(inbox.conversations.value.isEmpty())
    }

    @Test
    fun `removing a package from the allow list drops its conversations`() {
        val inbox = CommsInbox()
        inbox.post(message("t1", "telegram", 1_000L))
        inbox.post(
            rawNotification(
                packageName = CommsPackages.WhatsApp,
                key = "w1",
                title = "Mum",
                text = "whatsapp",
            ),
        )
        assertEquals(2, inbox.conversations.value.size)

        inbox.setPolicy(CommsPolicy(allowedPackages = setOf(CommsPackages.WhatsApp)))
        assertEquals(1, inbox.conversations.value.size)
        assertEquals(CommsPackages.WhatsApp, inbox.conversations.value.single().app)
    }

    @Test
    fun `disabling clears everything and rejects new posts`() {
        val inbox = CommsInbox()
        inbox.post(message("n1", "hello", 1_000L))
        inbox.setPolicy(CommsPolicy(enabled = false))

        assertTrue(inbox.conversations.value.isEmpty())
        assertFalse(inbox.shouldParse(telegram))
        assertFalse(inbox.post(message("n2", "after off", 2_000L)))
    }

    @Test
    fun `shouldParse follows the switch and the allow list`() {
        val inbox = CommsInbox()
        assertTrue(inbox.shouldParse(telegram))
        assertFalse(inbox.shouldParse("com.example.other"))

        inbox.setPolicy(CommsPolicy(allowedPackages = setOf(CommsPackages.Signal)))
        assertFalse(inbox.shouldParse(telegram))
        assertTrue(inbox.shouldParse(CommsPackages.Signal))
    }

    @Test
    fun `the notification ceiling keeps the newest notifications`() {
        val inbox = CommsInbox(maxNotifications = 2)
        inbox.post(message("old", "one", 1_000L, sender = "Ana"))
        inbox.post(message("middle", "two", 2_000L, sender = "Ben"))
        inbox.post(message("new", "three", 3_000L, sender = "Cara"))

        assertEquals(setOf("Ben", "Cara"), inbox.conversations.value.map { it.title }.toSet())
    }

    @Test
    fun `the per conversation ceiling keeps the newest messages`() {
        val inbox = CommsInbox(maxMessagesPerConversation = 2)
        inbox.post(message("n1", "one", 1_000L))
        inbox.post(message("n2", "two", 2_000L))
        inbox.post(message("n3", "three", 3_000L))

        assertEquals(listOf("two", "three"), inbox.conversations.value.single().messages.map { it.text })
    }

    @Test
    fun `a rejected update removes an earlier contribution with the same key`() {
        val inbox = CommsInbox()
        inbox.post(message("n1", "was a message", 1_000L))
        inbox.post(message("n1", "still here", 2_000L).copy(flags = 512))

        assertTrue(inbox.conversations.value.isEmpty())
    }

    @Test
    fun `canReply is true when any surviving message can reply`() {
        val inbox = CommsInbox()
        inbox.post(message("n1", "plain", 1_000L))
        inbox.post(
            rawNotification(
                key = "n2",
                title = "Sam",
                text = "reply here",
                actions = listOf(replyAction()),
                postTimeMs = 2_000L,
            ),
        )

        assertTrue(inbox.conversations.value.single().canReply)
    }

    @Test
    fun `canMarkRead is true when any surviving message has the action`() {
        val inbox = CommsInbox()
        inbox.post(message("n1", "plain", 1_000L))
        assertFalse(inbox.conversations.value.single().canMarkRead)

        inbox.post(
            rawNotification(
                key = "n2",
                title = "Sam",
                text = "read me",
                actions = listOf(markReadAction()),
                postTimeMs = 2_000L,
            ),
        )

        assertTrue(inbox.conversations.value.single().canMarkRead)
    }

    @Test
    fun `entryFor exposes a parsed notification until the inbox drops it`() {
        val inbox = CommsInbox()
        inbox.post(message("n1", "kept for history", 1_000L))

        val entry = requireNotNull(inbox.entryFor("n1"))
        assertEquals(CommsPackages.Telegram, entry.app)
        assertEquals("Sam", entry.title)
        assertEquals(listOf("kept for history"), entry.messages.map { it.text })

        inbox.remove("n1")
        assertNull(inbox.entryFor("n1"))
        assertNull(inbox.entryFor("unknown"))
    }

    @Test
    fun `an identical repost keeps the published snapshot instance`() {
        val inbox = CommsInbox()
        inbox.post(message("n1", "hello", 1_000L))
        val before = inbox.conversations.value

        inbox.post(message("n1", "hello", 1_000L))

        assertSame(before, inbox.conversations.value)
        assertEquals("hello", inbox.conversations.value.single().messages.single().text)
    }
}
