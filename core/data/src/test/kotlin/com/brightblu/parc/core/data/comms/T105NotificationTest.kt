package com.brightblu.parc.core.data.comms

import com.brightblu.parc.core.data.lock.NotificationLockPolicy
import com.brightblu.parc.core.data.notifications.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class T105NotificationTest {
    @After fun reset() { NotificationLockPolicy.packages = null }

    @Test fun relockPurgesActivePeekTextAndStopsNewHistoryWritesWithoutDeletingRows() = runTest {
        var hidden = emptySet<String>()
        NotificationLockPolicy.packages = { hidden }
        val inbox = CommsInbox()
        val history = CommsHistoryStore(FakeCommsMessageDao())
        val peek = NotificationPeekStore().also { it.collecting = true }
        val raw = rawNotification(key = "private", title = "Private title", text = "Private text", postTimeMs = 1)
        assertTrue(inbox.post(raw))
        val parsed = inbox.entryFor(raw.key)!!
        history.record(parsed)
        peek.post(PeekEntry(raw.key,raw.packageName,"Private title","Private text",1,PeekVisibility.Public,false,false))
        assertFalse(history.messages.first().isEmpty()); assertFalse(peek.snapshot().isEmpty())
        hidden = setOf(raw.packageName)
        inbox.refilterLocks(); peek.refilterLocks()
        assertTrue(inbox.conversations.value.isEmpty()); assertTrue(peek.snapshot().isEmpty())
        // T214: locking never deletes stored rows, but a locked app cannot add a new one.
        assertEquals(1, history.messages.first().size)
        assertFalse(inbox.post(raw))
        assertEquals(0, history.record(parsed))
        peek.post(PeekEntry(raw.key,raw.packageName,"Private title","Private text",1,PeekVisibility.Public,false,false))
        assertTrue(peek.snapshot().isEmpty())
        hidden = emptySet()
        assertTrue(inbox.conversations.value.isEmpty())
        assertEquals(1, history.messages.first().size)
        assertTrue(peek.snapshot().isEmpty())
    }

    @Test fun listenerCannotReadContentBeforeLockPolicyIsKnown() {
        assertFalse(NotificationLockPolicy.mayRead("any.package"))
    }
}
