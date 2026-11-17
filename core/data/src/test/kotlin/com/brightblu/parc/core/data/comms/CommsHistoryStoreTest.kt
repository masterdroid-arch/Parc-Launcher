package com.brightblu.parc.core.data.comms

import com.brightblu.parc.core.data.lock.NotificationLockPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T214: the persistent history contract. Room's SQL runtime is not available in JVM unit tests, so
 * the store runs over [FakeCommsMessageDao], which keeps the same row shape and can be reopened by
 * a second store to prove the cross-process contract. The real exported Room schema is asserted by
 * [CommsHistoryDatabaseTest].
 */
class CommsHistoryStoreTest {

    private val parser = CommsParser()
    private var now = 10_000L

    @After
    fun resetLockPolicy() {
        NotificationLockPolicy.packages = null
    }

    private fun store(dao: CommsMessageDao, clock: () -> Long = { now }) =
        CommsHistoryStore(dao, clock)

    private fun parsed(
        key: String = "n1",
        app: String = CommsPackages.Telegram,
        title: String = "Sam",
        texts: List<String> = listOf("hello"),
        messages: List<RawMessage> = emptyList(),
        picture: CommsImage? = null,
    ): ParsedNotification = requireNotNull(
        parser.parse(
            rawNotification(
                packageName = app,
                key = key,
                title = title,
                text = texts.lastOrNull(),
                postTimeMs = 1_000L,
                messages = messages.ifEmpty {
                    texts.mapIndexed { index, text -> rawMessage(sender = title, text = text, timeMs = 1_000L + index) }
                },
                picture = picture,
            ),
            CommsPolicy(),
        ),
    )

    private fun entity(
        text: String,
        links: String,
        key: String = "n1",
        app: String = CommsPackages.Telegram,
        conversation: String = "Sam",
    ) = CommsMessageEntity(
        app = app,
        conversationKey = conversation,
        conversationTitle = conversation,
        sender = "Sam",
        notificationTitle = conversation,
        text = text,
        links = links,
        timeMs = 1_000L,
        recordedAtMs = 2_000L,
        notificationKey = key,
        identity = "identity-$text-$key",
    )

    @Test
    fun `writes survive a new store over the same database`() = runTest {
        val dao = FakeCommsMessageDao()
        val first = store(dao)
        now = 20_000L
        first.record(
            parsed(
                texts = listOf(
                    "first",
                    "second https://example.com/page",
                    "third",
                ),
            ),
            postedAtMs = now,
        )

        val reopened = store(dao)
        val rows = reopened.messages.first()

        assertEquals(3, rows.size)
        assertEquals(listOf("third", "second https://example.com/page", "first"), rows.map { it.text })
        assertEquals(CommsPackages.Telegram, rows.first().app)
        assertEquals("Sam", rows.first().conversationKey)
        assertEquals("Sam", rows.first().conversationTitle)
        assertEquals("n1", rows.first().notificationKey)
        assertEquals(now, rows.first().recordedAtMs)
        assertTrue(rows.all { it.live })
        assertTrue(rows.all { it.removedAtMs == null })
        val links = rows.single { it.text.startsWith("second") }.let { reopened.linksOf(it) }
        assertEquals(1, links.size)
        assertEquals("https://example.com/page", rows.single { it.text.startsWith("second") }
            .text.substring(links.single().start, links.single().endExclusive))
    }

    @Test
    fun `a thousand rows are never expired by a clock advance`() = runTest {
        val dao = FakeCommsMessageDao()
        val store = store(dao)
        val messages = (1..1_000).map { index ->
            CommsMessage("n", "Sam", "message $index", index.toLong())
        }
        store.record(
            ParsedNotification("n", CommsPackages.Telegram, "Sam", "Sam", messages, 1_000L),
        )
        // Ten years later, reopening the same database still sees every row.
        now += 10L * 365L * 24L * 60L * 60L * 1_000L
        val reopened = store(dao)

        assertEquals(1_000, reopened.messages.first().size)
    }

    @Test
    fun `clear delete and delete conversation are the only removals`() = runTest {
        val dao = FakeCommsMessageDao()
        val store = store(dao)
        store.record(parsed(key = "n1", title = "Sam", texts = listOf("one", "two")))
        store.record(parsed(key = "n2", title = "Mum", texts = listOf("three")))
        val before = store.messages.first()
        assertEquals(3, before.size)

        store.delete(before.first { it.text == "two" }.id)
        assertEquals(setOf("one", "three"), store.messages.first().map { it.text }.toSet())

        store.deleteConversation(CommsPackages.Telegram, "Sam")
        assertEquals(listOf("three"), store.messages.first().map { it.text })

        store.clear()
        assertTrue(store.messages.first().isEmpty())
    }

    @Test
    fun `recording off pauses writes and keeps rows until an explicit clear`() = runTest {
        val store = store(FakeCommsMessageDao())
        store.record(parsed(key = "n1", texts = listOf("kept")))
        store.setRecording(false)

        assertEquals(0, store.record(parsed(key = "n2", texts = listOf("while paused"))))
        assertEquals(listOf("kept"), store.messages.first().map { it.text })

        store.setRecording(true)
        assertEquals(1, store.record(parsed(key = "n3", texts = listOf("resumed"))))
        assertEquals(setOf("kept", "resumed"), store.messages.first().map { it.text }.toSet())
    }

    @Test
    fun `removing an app from the allow list keeps its rows and stops new ones`() = runTest {
        val store = store(FakeCommsMessageDao())
        store.record(parsed(key = "t1", app = CommsPackages.Telegram, texts = listOf("telegram")))
        store.record(parsed(key = "w1", app = CommsPackages.WhatsApp, title = "Mum", texts = listOf("whatsapp")))

        store.setAllowedPackages(setOf(CommsPackages.WhatsApp))

        assertEquals(2, store.messages.first().size)
        assertEquals(0, store.record(parsed(key = "t2", app = CommsPackages.Telegram, texts = listOf("later"))))
        assertEquals(1, store.record(parsed(key = "w2", app = CommsPackages.WhatsApp, title = "Mum", texts = listOf("more"))))
        assertEquals(3, store.messages.first().size)
    }

    @Test
    fun `only Whatsapp Telegram and Instagram are recorded even if live-allowed`() = runTest {
        val store = store(FakeCommsMessageDao())
        fun direct(app: String) = ParsedNotification(
            notificationKey = app,
            app = app,
            conversationKey = app,
            title = app,
            messages = listOf(CommsMessage(app, "Sam", app, 1_000L)),
            updatedAtMs = 1_000L,
        )

        store.setAllowedPackages(CommsPackages.Known)
        for (app in listOf(CommsPackages.Signal, CommsPackages.Messages, CommsPackages.Discord, CommsPackages.Imo)) {
            assertEquals(0, store.record(direct(app)))
        }
        for (app in CommsHistoryStore.RecordedPackages) {
            assertEquals(1, store.record(direct(app)))
        }
        assertEquals(
            CommsHistoryStore.RecordedPackages,
            store.messages.first().map { it.app }.toSet(),
        )
    }

    @Test
    fun `a repost with a new notification key updates one row instead of adding one`() = runTest {
        val store = store(FakeCommsMessageDao())
        val original = parsed(key = "n1", texts = listOf("hello"))
        store.record(original)
        store.record(original)
        assertEquals(1, store.messages.first().size)

        store.markRemoved("n1")
        assertFalse(store.messages.first().single().live)

        val repost = parsed(key = "n2", texts = listOf("hello"))
        store.record(repost)

        val row = store.messages.first().single()
        assertEquals(1, store.messages.first().size)
        assertEquals("n2", row.notificationKey)
        assertTrue(row.live)
        assertEquals(null, row.removedAtMs)
    }

    @Test
    fun `removal and connect reconciliation keep the row and only change live`() = runTest {
        val store = store(FakeCommsMessageDao())
        store.record(parsed(key = "n1", texts = listOf("one", "two")))
        assertEquals(2, store.messages.first().size)

        now = 20_000L
        store.markRemoved("n1")

        assertEquals(2, store.messages.first().size)
        assertTrue(store.messages.first().none { it.live })
        assertTrue(store.messages.first().all { it.removedAtMs == 20_000L })
        assertTrue(store.messages.first().all { it.notificationKey.isEmpty() })

        // connect: everything starts stale, then the accepted active notification is live again.
        now = 30_000L
        store.markAllNotLive()
        store.record(parsed(key = "n1", texts = listOf("one", "two")))
        assertTrue(store.messages.first().all { it.live })
        assertTrue(store.messages.first().all { it.removedAtMs == null })
    }

    @Test
    fun `an image-only message stores nothing and a captioned image stores only text`() = runTest {
        val image = grayImage(0xFF00FF00.toInt())
        val store = store(FakeCommsMessageDao())

        store.record(
            parsed(
                key = "image",
                messages = listOf(rawMessage(sender = "Sam", text = null, timeMs = 1_000L, image = image)),
            ),
        )
        assertTrue(store.messages.first().isEmpty())

        store.record(
            parsed(
                key = "caption",
                messages = listOf(rawMessage(sender = "Sam", text = "look at this", timeMs = 1_000L, image = image)),
            ),
        )
        val row = store.messages.first().single()
        assertEquals("look at this", row.text)
        assertEquals("[]", row.links)
    }

    @Test
    fun `invalid stored links are dropped and the text is kept`() = runTest {
        val dao = FakeCommsMessageDao(
            listOf(
                entity(text = "hello", links = "[[0,99]]"),
                entity(text = "hello again", links = "not json", key = "n2"),
            ),
        )
        val store = store(dao)

        val rows = store.messages.first()
        assertEquals(setOf("hello", "hello again"), rows.map { it.text }.toSet())
        assertTrue(rows.all { store.linksOf(it).isEmpty() })
    }

    @Test
    fun `valid links round trip as spans into the same text`() = runTest {
        val store = store(FakeCommsMessageDao())
        store.record(parsed(texts = listOf("go to https://example.com now")))
        val row = store.messages.first().single()

        val link = store.linksOf(row).single()
        assertEquals("https://example.com", row.text.substring(link.start, link.endExclusive))
    }

    @Test
    fun `identity is stable lowercase sha-256 over app conversation sender time and text`() {
        val first = CommsHistoryIdentity.of("com.whatsapp", "Sam", "Sam", 1_000L, "hello")
        val second = CommsHistoryIdentity.of("com.whatsapp", "Sam", "Sam", 1_000L, "hello")
        val edited = CommsHistoryIdentity.of("com.whatsapp", "Sam", "Sam", 1_000L, "hello!")

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(first.all { it in "0123456789abcdef" })
        assertFalse(first == edited)
    }

    @Test
    fun `a locked app cannot add a row but its stored rows are kept`() = runTest {
        val store = store(FakeCommsMessageDao())
        store.record(parsed(key = "n1", texts = listOf("before lock")))
        NotificationLockPolicy.packages = { setOf(CommsPackages.Telegram) }

        assertEquals(0, store.record(parsed(key = "n2", texts = listOf("while locked"))))
        assertEquals(listOf("before lock"), store.messages.first().map { it.text })
    }
}
