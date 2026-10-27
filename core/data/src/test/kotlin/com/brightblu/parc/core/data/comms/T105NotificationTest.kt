package com.brightblu.parc.core.data.comms

import com.brightblu.parc.core.data.lock.NotificationLockPolicy
import com.brightblu.parc.core.data.notifications.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class T105NotificationTest {
    @After fun reset() { NotificationLockPolicy.packages = null }
    @Test fun relockPurgesActiveHistoryAndPeekTextAndSubsequentPostsCannotReintroduceIt() {
        var hidden = emptySet<String>()
        NotificationLockPolicy.packages = { hidden }
        val inbox = CommsInbox(); val history = CommsHistory(); val peek = NotificationPeekStore().also { it.collecting = true }
        val raw = rawNotification(key = "private", title = "Private title", text = "Private text", postTimeMs = 1)
        assertTrue(inbox.post(raw))
        val parsed = inbox.entryFor(raw.key)!!
        history.record(parsed)
        peek.post(PeekEntry(raw.key,raw.packageName,"Private title","Private text",1,PeekVisibility.Public,false,false))
        assertFalse(history.history.value.isEmpty()); assertFalse(peek.snapshot().isEmpty())
        hidden = setOf(raw.packageName)
        inbox.refilterLocks(); history.refilterLocks(); peek.refilterLocks()
        assertTrue(inbox.conversations.value.isEmpty()); assertTrue(history.history.value.isEmpty()); assertTrue(peek.snapshot().isEmpty())
        assertFalse(inbox.post(raw)); assertFalse(history.record(parsed))
        peek.post(PeekEntry(raw.key,raw.packageName,"Private title","Private text",1,PeekVisibility.Public,false,false))
        assertTrue(peek.snapshot().isEmpty())
        hidden = emptySet()
        assertTrue(inbox.conversations.value.isEmpty()); assertTrue(history.history.value.isEmpty()); assertTrue(peek.snapshot().isEmpty())
    }
    @Test fun listenerCannotReadContentBeforeLockPolicyIsKnown() {
        assertFalse(NotificationLockPolicy.mayRead("any.package"))
    }
}
