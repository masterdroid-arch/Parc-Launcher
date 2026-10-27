package com.brightblu.parc

import com.brightblu.parc.core.data.lock.*
import com.brightblu.parc.model.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class T105Test {
    @Test fun unlockingOneFolderLeavesTheOtherLockedAndTimersAreIndependent() = runTest {
        val a = FolderId("a"); val b = FolderId("b")
        val lock = FolderLock(backgroundScope, { testScheduler.currentTime }, { UnlockTimeout.Seconds30 })
        lock.unlock(setOf(a))
        assertTrue(lock.isUnlocked(a)); assertFalse(lock.isUnlocked(b))
        advanceTimeBy(10_000); lock.unlock(setOf(b))
        advanceTimeBy(20_000); runCurrent()
        assertFalse(lock.isUnlocked(a)); assertTrue(lock.isUnlocked(b))
        advanceTimeBy(10_000); runCurrent()
        assertFalse(lock.isUnlocked(b))
        lock.unlock(setOf(a,b)); lock.onScreenOff()
        assertTrue(lock.windows.value.isEmpty())
        lock.unlock(setOf(a,b)); lock.onHostLeft()
        assertTrue(lock.windows.value.isEmpty())
    }
    @Test fun authenticationForOneFolderDoesNotOpenAnotherOrAnUnknownFolder() = runTest {
        val a = FolderId("a"); val b = FolderId("b")
        val folders = listOf(Folder(a,"Private A").withLocked(true), Folder(b,"Private B").withLocked(true))
        val lock = FolderLock(backgroundScope, { 0L }, { UnlockTimeout.Minute1 })
        val auth = object : FolderAuthenticator {
            override fun availability() = AuthAvailability.Available
            override fun authenticate(title: String, subtitle: String, onResult: (AuthOutcome) -> Unit) { onResult(AuthOutcome.Success) }
            override fun cancel() {}
        }
        val gate = ServicesFolderLockGate(lock, {folders}, {emptyList()}, {true}, auth, {})
        var opened = false
        gate.authenticateFolder(a) { opened = true }
        assertTrue(opened); assertFalse(gate.isFolderLockedNow(a)); assertTrue(gate.isFolderLockedNow(b))
        gate.authenticateFolder(FolderId("missing")) { fail("unknown folder") }
    }
    @Test fun lockedPageStatesTheLimitsWithoutPromisingEncryption() {
        assertTrue(LockedFolderText.Honesty.contains("not encrypted"))
        assertTrue(LockedFolderText.Honesty.contains("Recents"))
        assertTrue(LockedFolderText.Honesty.contains("notification shade"))
        assertTrue(LockedFolderText.Honesty.contains("outside Parc"))
    }
}
