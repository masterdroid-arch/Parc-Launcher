package com.brightblu.parc.core.data.comms

import androidx.datastore.preferences.core.preferencesOf
import com.brightblu.parc.core.data.FakePreferencesStore
import com.brightblu.parc.core.data.ParcPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CommsRepositoryTest {

    /** A scope on the test scheduler that the repository may keep collecting after the test body. */
    private fun linkScope(test: TestScope): CoroutineScope =
        CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(test.testScheduler))

    private fun conversation(vararg messages: CommsMessage): CommsConversation = CommsConversation(
        id = CommsConversationId(CommsPackages.Telegram, "Sam"),
        title = "Sam",
        messages = messages.toList(),
        canReply = messages.any { it.canReply },
        updatedAtMs = messages.maxOfOrNull { it.timeMs } ?: 0L,
    )

    private fun message(
        key: String,
        text: String,
        timeMs: Long,
        canReply: Boolean = false,
    ): CommsMessage = CommsMessage(
        notificationKey = key,
        sender = "Sam",
        text = text,
        timeMs = timeMs,
        canReply = canReply,
    )

    @Test
    fun `a fresh repository starts on the card defaults`() = runTest {
        val repo = CommsRepositoryImpl(
            inbox = CommsInbox(),
            prefs = ParcPrefs(FakePreferencesStore()),
            actions = FakeCommsActions(),
            io = Dispatchers.Unconfined,
            scope = linkScope(this),
            historyStore = historyStore(),
        )
        advanceUntilIdle()

        assertEquals(CommsPackages.Default, repo.allowedApps.value)
        assertTrue(repo.enabled.value)
        assertTrue(repo.conversations.value.isEmpty())
    }

    @Test
    fun `stored settings are applied to the inbox`() = runTest {
        val inbox = CommsInbox()
        inbox.post(rawNotification())
        val store = FakePreferencesStore(
            preferencesOf(
                ParcPrefs.KeyCommsApps to setOf(CommsPackages.Signal),
                ParcPrefs.KeyCommsEnabled to false,
            ),
        )
        val repo = CommsRepositoryImpl(
            inbox = inbox,
            prefs = ParcPrefs(store),
            actions = FakeCommsActions(),
            io = Dispatchers.Unconfined,
            scope = linkScope(this),
            historyStore = historyStore(),
        )
        advanceUntilIdle()

        assertFalse(repo.enabled.value)
        assertEquals(setOf(CommsPackages.Signal), repo.allowedApps.value)
        assertTrue(repo.conversations.value.isEmpty())
    }

    @Test
    fun `the allow list is normalised, applied to the inbox and stored`() = runTest {
        val inbox = CommsInbox()
        val store = FakePreferencesStore()
        val repo = CommsRepositoryImpl(
            inbox = inbox,
            prefs = ParcPrefs(store),
            actions = FakeCommsActions(),
            io = Dispatchers.Unconfined,
            scope = linkScope(this),
            historyStore = historyStore(),
        )
        repo.setAllowedApps(setOf("  ${CommsPackages.WhatsApp}  ", "", "   "))
        advanceUntilIdle()

        assertEquals(setOf(CommsPackages.WhatsApp), repo.allowedApps.value)
        assertTrue(inbox.shouldParse(CommsPackages.WhatsApp))
        assertFalse(inbox.shouldParse(CommsPackages.Telegram))
        assertEquals(setOf(CommsPackages.WhatsApp), ParcPrefs(store).commsApps.first())
    }

    @Test
    fun `an explicitly empty allow list is kept empty`() = runTest {
        val inbox = CommsInbox()
        inbox.post(rawNotification())
        val repo = CommsRepositoryImpl(
            inbox = inbox,
            prefs = ParcPrefs(FakePreferencesStore()),
            actions = FakeCommsActions(),
            io = Dispatchers.Unconfined,
            scope = linkScope(this),
            historyStore = historyStore(),
        )
        repo.setAllowedApps(emptySet())
        advanceUntilIdle()

        assertTrue(repo.allowedApps.value.isEmpty())
        assertFalse(inbox.shouldParse(CommsPackages.Telegram))
        assertTrue(repo.conversations.value.isEmpty())
    }

    @Test
    fun `switching off clears held conversations and switching on accepts again`() = runTest {
        val inbox = CommsInbox()
        inbox.post(rawNotification())
        val repo = CommsRepositoryImpl(
            inbox = inbox,
            prefs = ParcPrefs(FakePreferencesStore()),
            actions = FakeCommsActions(),
            io = Dispatchers.Unconfined,
            scope = linkScope(this),
            historyStore = historyStore(),
        )
        advanceUntilIdle()
        assertEquals(1, repo.conversations.value.size)

        repo.setEnabled(false)
        advanceUntilIdle()
        assertFalse(repo.enabled.value)
        assertTrue(repo.conversations.value.isEmpty())

        repo.setEnabled(true)
        advanceUntilIdle()
        assertTrue(repo.enabled.value)
        assertTrue(inbox.shouldParse(CommsPackages.Telegram))
    }

    @Test
    fun `switching Messages off keeps stored rows and pauses history writes`() = runTest {
        val inbox = CommsInbox()
        val history = historyStore()
        val repo = extrasRepo(this, inbox = inbox, history = history)
        advanceUntilIdle()
        inbox.post(rawNotification(key = "n1", text = "kept"))
        history.record(inbox.entryFor("n1")!!)
        advanceUntilIdle()

        repo.setEnabled(false)
        advanceUntilIdle()

        assertEquals(1, repo.storedMessages.value.size)
        val later = CommsParser().parse(
            rawNotification(key = "n2", text = "while messages are off"),
            CommsPolicy(),
        )!!
        assertEquals(0, history.record(later))
        assertEquals(1, repo.storedMessages.value.size)
    }

    @Test
    fun `reply goes through the newest message that still has a reply action`() = runTest {
        val actions = FakeCommsActions()
        val repo = CommsRepositoryImpl(
            inbox = CommsInbox(),
            prefs = ParcPrefs(FakePreferencesStore()),
            actions = actions,
            io = Dispatchers.Unconfined,
            scope = linkScope(this),
            historyStore = historyStore(),
        )
        val target = conversation(
            message("n1", "old", 1_000L, canReply = true),
            message("n2", "new but no action", 2_000L, canReply = false),
        )

        val result = repo.reply(target, "  on my way  ")

        assertEquals(ReplyResult.Sent, result)
        assertEquals(listOf("n1" to "on my way"), actions.replies)
    }

    @Test
    fun `blank text fails without touching the platform`() = runTest {
        val actions = FakeCommsActions()
        val repo = CommsRepositoryImpl(
            inbox = CommsInbox(),
            prefs = ParcPrefs(FakePreferencesStore()),
            actions = actions,
            io = Dispatchers.Unconfined,
            scope = linkScope(this),
            historyStore = historyStore(),
        )

        assertEquals(ReplyResult.Failed, repo.reply(conversation(message("n1", "x", 1L, true)), "   "))
        assertTrue(actions.replies.isEmpty())
    }

    @Test
    fun `a conversation without a reply action reports NoReplyAction`() = runTest {
        val actions = FakeCommsActions()
        val repo = CommsRepositoryImpl(
            inbox = CommsInbox(),
            prefs = ParcPrefs(FakePreferencesStore()),
            actions = actions,
            io = Dispatchers.Unconfined,
            scope = linkScope(this),
            historyStore = historyStore(),
        )

        assertEquals(
            ReplyResult.NoReplyAction,
            repo.reply(conversation(message("n1", "x", 1L, canReply = false)), "hello"),
        )
        assertTrue(actions.replies.isEmpty())
    }

    @Test
    fun `the platform result is passed through`() = runTest {
        val actions = FakeCommsActions().apply { result = ReplyResult.NoReplyAction }
        val repo = CommsRepositoryImpl(
            inbox = CommsInbox(),
            prefs = ParcPrefs(FakePreferencesStore()),
            actions = actions,
            io = Dispatchers.Unconfined,
            scope = linkScope(this),
            historyStore = historyStore(),
        )

        assertEquals(
            ReplyResult.NoReplyAction,
            repo.reply(conversation(message("n1", "x", 1L, canReply = true)), "hello"),
        )
        assertEquals(1, actions.replies.size)
    }

    @Test
    fun `a platform failure is passed through`() = runTest {
        val actions = FakeCommsActions().apply { result = ReplyResult.Failed }
        val repo = CommsRepositoryImpl(
            inbox = CommsInbox(),
            prefs = ParcPrefs(FakePreferencesStore()),
            actions = actions,
            io = Dispatchers.Unconfined,
            scope = linkScope(this),
            historyStore = historyStore(),
        )

        assertEquals(
            ReplyResult.Failed,
            repo.reply(conversation(message("n1", "x", 1L, canReply = true)), "hello"),
        )
    }

    @Test
    fun `dismissing a message cancels exactly its notification`() = runTest {
        val actions = FakeCommsActions()
        val repo = CommsRepositoryImpl(
            inbox = CommsInbox(),
            prefs = ParcPrefs(FakePreferencesStore()),
            actions = actions,
            io = Dispatchers.Unconfined,
            scope = linkScope(this),
            historyStore = historyStore(),
        )

        repo.dismiss(message("n7", "x", 1L))

        assertEquals(listOf("n7"), actions.dismissed)
    }

    @Test
    fun `dismissing a conversation cancels every distinct notification once`() = runTest {
        val actions = FakeCommsActions()
        val repo = CommsRepositoryImpl(
            inbox = CommsInbox(),
            prefs = ParcPrefs(FakePreferencesStore()),
            actions = actions,
            io = Dispatchers.Unconfined,
            scope = linkScope(this),
            historyStore = historyStore(),
        )
        val target = conversation(
            message("n1", "one", 1L),
            message("n2", "two", 2L),
            message("n1", "one again", 3L),
        )

        repo.dismiss(target)

        assertEquals(listOf("n1", "n2"), actions.dismissed)
    }

    // ---- T76 extras -------------------------------------------------------------------------

    private fun historyStore(): CommsHistoryStore = CommsHistoryStore(FakeCommsMessageDao())

    private fun extrasRepo(
        test: TestScope,
        inbox: CommsInbox = CommsInbox(),
        actions: FakeCommsActions = FakeCommsActions(),
        history: CommsHistoryStore = historyStore(),
        store: FakePreferencesStore = FakePreferencesStore(),
    ): CommsRepositoryImpl = CommsRepositoryImpl(
        inbox = inbox,
        prefs = ParcPrefs(store),
        actions = actions,
        io = Dispatchers.Unconfined,
        scope = linkScope(test),
        historyStore = history,
    )

    @Test
    fun `a fresh repository starts on the card canned replies and keeps history`() = runTest {
        val repo = extrasRepo(this)
        advanceUntilIdle()

        assertEquals(CommsCannedReplies.Default, repo.cannedReplies.value)
        assertTrue(repo.keepHistory.value)
        assertTrue(repo.history.value.isEmpty())
        assertTrue(repo.storedMessages.value.isEmpty())
    }

    @Test
    fun `stored canned replies and the history switch are applied`() = runTest {
        val store = FakePreferencesStore(
            preferencesOf(
                ParcPrefs.KeyCommsCannedReplies to "alpha\nbeta",
                ParcPrefs.KeyCommsKeepHistory to false,
            ),
        )
        val repo = extrasRepo(this, store = store)
        advanceUntilIdle()

        assertEquals(listOf("alpha", "beta"), repo.cannedReplies.value)
        assertFalse(repo.keepHistory.value)
    }

    @Test
    fun `setting canned replies sanitises, caps and persists them`() = runTest {
        val store = FakePreferencesStore()
        val repo = extrasRepo(this, store = store)
        advanceUntilIdle()

        repo.setCannedReplies(listOf("  Thanks  ", "", "OK", "Thanks") + (1..9).map { "r$it" })

        assertEquals(8, repo.cannedReplies.value.size)
        assertEquals(
            listOf("Thanks", "OK", "r1", "r2", "r3", "r4", "r5", "r6"),
            repo.cannedReplies.value,
        )
        assertEquals(repo.cannedReplies.value, ParcPrefs(store).commsCannedReplies.first())
    }

    @Test
    fun `turning keep history off pauses recording and keeps the stored rows`() = runTest {
        val inbox = CommsInbox()
        val history = historyStore()
        val actions = FakeCommsActions()
        val repo = extrasRepo(this, inbox = inbox, actions = actions, history = history)
        advanceUntilIdle()
        inbox.post(rawNotification(key = "n1", text = "hello"))
        history.record(inbox.entryFor("n1")!!)
        advanceUntilIdle()
        assertEquals(1, repo.history.value.size)

        repo.setKeepHistory(false)
        advanceUntilIdle()

        assertEquals(1, repo.history.value.size)
        inbox.post(rawNotification(key = "n2", text = "later"))
        assertEquals(0, history.record(inbox.entryFor("n2")!!))
        assertEquals(1, repo.storedMessages.value.size)

        repo.setKeepHistory(true)
        advanceUntilIdle()
        assertEquals(1, history.record(inbox.entryFor("n2")!!))
        advanceUntilIdle()
        assertEquals(2, repo.history.value.size)
    }

    @Test
    fun `dismissing a message does not duplicate or delete its stored row`() = runTest {
        val inbox = CommsInbox()
        val history = historyStore()
        val repo = extrasRepo(this, inbox = inbox, history = history)
        advanceUntilIdle()
        inbox.post(rawNotification(key = "n7", title = "Sam", text = "bye"))
        history.record(inbox.entryFor("n7")!!)
        advanceUntilIdle()

        repo.dismiss(message("n7", "bye", 1_000L))
        advanceUntilIdle()

        val entry = repo.history.value.single()
        assertEquals(CommsConversationId(CommsPackages.Telegram, "Sam"), entry.conversation)
        assertEquals("bye", entry.message.text)
        assertEquals("n7", entry.message.notificationKey)
        assertEquals(1, repo.storedMessages.value.size)
    }

    @Test
    fun `mark as read asks the newest notification that offers the action`() = runTest {
        val actions = FakeCommsActions()
        val repo = extrasRepo(this, actions = actions)
        advanceUntilIdle()
        val target = conversation(
            message("old", "one", 1_000L).copy(canMarkRead = true),
            message("new", "two", 2_000L).copy(canMarkRead = true),
        )

        assertEquals(MarkReadResult.Marked, repo.markAsRead(target))

        assertEquals(listOf("new"), actions.markedRead)
        assertTrue(actions.dismissed.isEmpty())
    }

    @Test
    fun `without the platform action mark as read clears notifications and keeps history`() = runTest {
        val inbox = CommsInbox()
        val history = historyStore()
        val actions = FakeCommsActions()
        val repo = extrasRepo(this, inbox = inbox, actions = actions, history = history)
        advanceUntilIdle()
        inbox.post(rawNotification(key = "n1", title = "Sam", text = "unread"))
        history.record(inbox.entryFor("n1")!!)
        advanceUntilIdle()

        assertEquals(
            MarkReadResult.Dismissed,
            repo.markAsRead(conversation(message("n1", "unread", 1_000L))),
        )

        assertTrue(actions.markedRead.isEmpty())
        assertEquals(listOf("n1"), actions.dismissed)
        assertEquals(1, repo.history.value.size)
    }

    @Test
    fun `mark as read with nothing left reports Unavailable`() = runTest {
        val actions = FakeCommsActions()
        val repo = extrasRepo(this, actions = actions)
        advanceUntilIdle()

        assertEquals(MarkReadResult.Unavailable, repo.markAsRead(conversation()))

        assertTrue(actions.markedRead.isEmpty())
        assertTrue(actions.dismissed.isEmpty())
    }

    @Test
    fun `a failed mark as read is reported and nothing is dismissed`() = runTest {
        val actions = FakeCommsActions().apply { markReadResult = MarkReadResult.Failed }
        val repo = extrasRepo(this, actions = actions)
        advanceUntilIdle()

        assertEquals(
            MarkReadResult.Failed,
            repo.markAsRead(conversation(message("n1", "x", 1L).copy(canMarkRead = true))),
        )

        assertTrue(actions.dismissed.isEmpty())
    }

    @Test
    fun `clear history empties the stored rows without changing the switch`() = runTest {
        val inbox = CommsInbox()
        val history = historyStore()
        val repo = extrasRepo(this, inbox = inbox, history = history)
        advanceUntilIdle()
        inbox.post(rawNotification(key = "n1", text = "hello"))
        history.record(inbox.entryFor("n1")!!)
        advanceUntilIdle()
        assertEquals(1, repo.history.value.size)

        repo.clearHistory()
        advanceUntilIdle()

        assertTrue(repo.history.value.isEmpty())
        assertTrue(repo.storedMessages.value.isEmpty())
        assertTrue(repo.keepHistory.value)
    }

    @Test
    fun `deleting one row, one conversation or all follows the stored count`() = runTest {
        val inbox = CommsInbox()
        val history = historyStore()
        val repo = extrasRepo(this, inbox = inbox, history = history)
        advanceUntilIdle()
        inbox.post(rawNotification(key = "t1", title = "Sam", text = "one", postTimeMs = 1_000L))
        inbox.post(rawNotification(key = "w1", packageName = CommsPackages.WhatsApp,
            title = "Alex", text = "two", postTimeMs = 2_000L))
        history.record(inbox.entryFor("t1")!!)
        history.record(inbox.entryFor("w1")!!)
        advanceUntilIdle()
        assertEquals(2, repo.storedMessages.value.size)

        repo.deleteHistoryMessage(repo.storedMessages.value.first { it.text == "one" }.id)
        advanceUntilIdle()
        assertEquals(1, repo.storedMessages.value.size)
        assertEquals("two", repo.storedMessages.value.single().text)

        repo.deleteHistoryConversation(CommsPackages.WhatsApp, "Alex")
        advanceUntilIdle()
        assertTrue(repo.storedMessages.value.isEmpty())

        inbox.post(rawNotification(key = "t2", text = "three"))
        history.record(inbox.entryFor("t2")!!)
        advanceUntilIdle()
        assertEquals(1, repo.storedMessages.value.size)
        repo.clearHistory()
        advanceUntilIdle()
        assertTrue(repo.storedMessages.value.isEmpty())
    }

    @Test
    fun `removing an app from the allow list keeps its stored rows and pauses its writes`() = runTest {
        val inbox = CommsInbox()
        val history = historyStore()
        val repo = extrasRepo(this, inbox = inbox, history = history)
        advanceUntilIdle()
        inbox.post(rawNotification(key = "t1", packageName = CommsPackages.Telegram, text = "kept"))
        val parsed = inbox.entryFor("t1")!!
        history.record(parsed)
        advanceUntilIdle()

        repo.setAllowedApps(setOf(CommsPackages.WhatsApp))
        advanceUntilIdle()

        assertEquals(1, repo.storedMessages.value.size)
        assertEquals(0, history.record(parsed))
        assertEquals(1, repo.storedMessages.value.size)
    }
}
