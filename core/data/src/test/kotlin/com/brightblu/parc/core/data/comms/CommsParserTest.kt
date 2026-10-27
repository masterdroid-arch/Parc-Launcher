package com.brightblu.parc.core.data.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every keep/drop rule of the card, plus the MessagingStyle and fallback shapes, pinned on the
 * JVM. The Android extractor is deliberately thin; this is where the behaviour lives.
 */
class CommsParserTest {

    private val parser = CommsParser()
    private val policy = CommsPolicy()

    private fun parse(raw: RawNotification, with: CommsPolicy = policy): ParsedNotification? =
        parser.parse(raw, with)

    @Test
    fun `messaging style keeps every message oldest first with its sender, time and key`() {
        val parsed = requireNotNull(
            parse(
                rawNotification(
                    key = "n1",
                    conversationTitle = "Sam and me",
                    postTimeMs = 500L,
                    messages = listOf(
                        rawMessage(sender = "Sam", text = "newer", timeMs = 2_000L),
                        rawMessage(sender = "Me", text = "older", timeMs = 1_000L),
                    ),
                ),
            ),
        )

        assertEquals("Sam and me", parsed.title)
        assertEquals("Sam and me", parsed.conversationKey)
        assertEquals(listOf("older", "newer"), parsed.messages.map { it.text })
        assertEquals(listOf("Me", "Sam"), parsed.messages.map { it.sender })
        assertEquals(listOf(1_000L, 2_000L), parsed.messages.map { it.timeMs })
        assertEquals(listOf("n1", "n1"), parsed.messages.map { it.notificationKey })
        assertEquals(2_000L, parsed.updatedAtMs)
    }

    @Test
    fun `a one to one notification uses its title as sender and conversation key`() {
        val parsed = requireNotNull(parse(rawNotification(key = "wa", title = "Mum", text = "dinner?")))

        assertEquals("Mum", parsed.title)
        assertEquals("Mum", parsed.conversationKey)
        assertEquals("Mum", parsed.messages.single().sender)
        assertEquals("dinner?", parsed.messages.single().text)
        assertEquals(1_000L, parsed.messages.single().timeMs)
    }

    @Test
    fun `big text wins over text and text is the fallback`() {
        val big = requireNotNull(
            parse(rawNotification(text = "short", bigText = "the whole long message")),
        )
        assertEquals("the whole long message", big.messages.single().text)

        val plain = requireNotNull(parse(rawNotification(text = "just text", bigText = "   ")))
        assertEquals("just text", plain.messages.single().text)
    }

    @Test
    fun `blank messages are dropped and an image-only message survives`() {
        val picture = grayImage(0xFFFFFFFF.toInt())
        val parsed = requireNotNull(
            parse(
                rawNotification(
                    messages = listOf(
                        rawMessage(text = "   ", image = null),
                        rawMessage(text = null, image = picture),
                        rawMessage(text = "real"),
                    ),
                ),
            ),
        )

        assertEquals(2, parsed.messages.size)
        assertEquals(listOf("", "real"), parsed.messages.map { it.text })
        assertEquals(picture, parsed.messages.first().image)
    }

    @Test
    fun `extra picture fills the newest message that has no image of its own`() {
        val own = grayImage(0xFF000000.toInt())
        val extra = grayImage(0xFFFFFFFF.toInt())
        val parsed = requireNotNull(
            parse(
                rawNotification(
                    picture = extra,
                    messages = listOf(
                        rawMessage(text = "old", timeMs = 10L),
                        rawMessage(text = "with photo", timeMs = 20L, image = own),
                        rawMessage(text = "newest", timeMs = 30L),
                    ),
                ),
            ),
        )

        assertEquals(own, parsed.messages[1].image)
        assertEquals(extra, parsed.messages[2].image)
        assertNull(parsed.messages[0].image)
    }

    @Test
    fun `a style message keeps its own image and time zero falls back to post time`() {
        val image = grayImage(0xFF808080.toInt(), 3, 2)
        val parsed = requireNotNull(
            parse(
                rawNotification(
                    postTimeMs = 777L,
                    messages = listOf(rawMessage(text = "photo", timeMs = 0L, image = image)),
                ),
            ),
        )

        assertEquals(image, parsed.messages.single().image)
        assertEquals(777L, parsed.messages.single().timeMs)
    }

    @Test
    fun `messages are capped to the newest ones`() {
        val small = CommsParser(maxMessagesPerNotification = 2)
        val parsed = requireNotNull(
            small.parse(
                rawNotification(
                    messages = listOf(
                        rawMessage(text = "one", timeMs = 1L),
                        rawMessage(text = "two", timeMs = 2L),
                        rawMessage(text = "three", timeMs = 3L),
                    ),
                ),
                policy,
            ),
        )

        assertEquals(listOf("two", "three"), parsed.messages.map { it.text })
    }

    @Test
    fun `ongoing notifications are ignored both ways`() {
        assertNull(parse(rawNotification(isOngoing = true)))
        assertNull(parse(rawNotification(flags = 2)))
        assertNotNull(parse(rawNotification(flags = 0)))
    }

    @Test
    fun `group summaries are ignored`() {
        assertNull(parse(rawNotification(flags = 512)))
    }

    @Test
    fun `secret visibility is ignored`() {
        assertNull(parse(rawNotification(visibility = -1)))
    }

    @Test
    fun `only message social and email categories pass, and an absent category is allowed`() {
        assertEquals("msg", CommsCategories.Message)
        assertEquals("social", CommsCategories.Social)
        assertEquals("email", CommsCategories.Email)

        assertNotNull(parse(rawNotification(category = CommsCategories.Message)))
        assertNotNull(parse(rawNotification(category = CommsCategories.Social)))
        assertNotNull(parse(rawNotification(category = CommsCategories.Email)))
        assertNotNull(parse(rawNotification(category = null)))

        assertNull(parse(rawNotification(category = "call")))
        assertNull(parse(rawNotification(category = "alarm")))
        assertNull(parse(rawNotification(category = "sys")))
    }

    @Test
    fun `a group conversation is not a group summary`() {
        val parsed = requireNotNull(
            parse(
                rawNotification(
                    title = "Family",
                    conversationTitle = "Family",
                    isGroupConversation = true,
                    messages = listOf(rawMessage(sender = "Ana", text = "hi")),
                ),
            ),
        )
        assertEquals("Family", parsed.title)
        assertEquals("Ana", parsed.messages.single().sender)
    }

    @Test
    fun `an app outside the allow list contributes nothing`() {
        val custom = CommsPolicy(allowedPackages = setOf(CommsPackages.WhatsApp))
        assertNull(parse(rawNotification(packageName = CommsPackages.Telegram), custom))
        assertNotNull(parse(rawNotification(packageName = CommsPackages.WhatsApp), custom))
    }

    @Test
    fun `an empty allow list shows nothing and a disabled policy shows nothing`() {
        assertNull(parse(rawNotification(), CommsPolicy(allowedPackages = emptySet())))
        assertNull(parse(rawNotification(), CommsPolicy(enabled = false)))
    }

    @Test
    fun `reply capability comes from any free-form action`() {
        val withReply = requireNotNull(parse(rawNotification(actions = listOf(RawAction(false), replyAction()))))
        assertTrue(withReply.messages.single().canReply)

        val without = requireNotNull(parse(rawNotification(actions = listOf(RawAction(false)))))
        assertFalse(without.messages.single().canReply)
    }

    @Test
    fun `mark-as-read capability comes from the platform semantic action`() {
        val withRead = requireNotNull(parse(rawNotification(actions = listOf(markReadAction()))))
        assertTrue(withRead.messages.single().canMarkRead)
        assertFalse(withRead.messages.single().canReply)

        val plain = requireNotNull(parse(rawNotification(actions = listOf(RawAction(false)))))
        assertFalse(plain.messages.single().canMarkRead)

        val otherSemantic = requireNotNull(
            parse(rawNotification(actions = listOf(RawAction(false, semanticAction = 1)))),
        )
        assertFalse(otherSemantic.messages.single().canMarkRead)
    }

    @Test
    fun `reply and mark-as-read are independent capabilities`() {
        val both = requireNotNull(
            parse(rawNotification(actions = listOf(replyAction(), markReadAction()))),
        )
        assertTrue(both.messages.single().canReply)
        assertTrue(both.messages.single().canMarkRead)
    }

    @Test
    fun `links are extracted from every message and from the fallback`() {
        val styled = requireNotNull(
            parse(
                rawNotification(
                    messages = listOf(
                        rawMessage(text = "see https://example.com/a", timeMs = 1L),
                        rawMessage(text = "and www.example.org", timeMs = 2L),
                    ),
                ),
            ),
        )
        val firstLink = styled.messages[0].links.single()
        assertEquals(
            "https://example.com/a",
            styled.messages[0].text.substring(firstLink.start, firstLink.endExclusive),
        )
        assertEquals("www.example.org", styled.messages[1].text.substring(
            styled.messages[1].links.single().start,
            styled.messages[1].links.single().endExclusive,
        ))

        val fallback = requireNotNull(parse(rawNotification(text = "read http://x.test")))
        assertEquals("http://x.test", fallback.messages.single().text.substring(
            fallback.messages.single().links.single().start,
            fallback.messages.single().links.single().endExclusive,
        ))
    }

    @Test
    fun `a notification with neither text nor picture is ignored`() {
        assertNull(parse(rawNotification(text = "   ", bigText = null)))
    }

    @Test
    fun `senderless style messages fall back to the notification title`() {
        val parsed = requireNotNull(
            parse(
                rawNotification(
                    title = "The group",
                    messages = listOf(rawMessage(sender = null, text = "hello")),
                ),
            ),
        )
        assertEquals("The group", parsed.messages.single().sender)
    }
}
