package com.brightblu.parc.core.data.lock

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.withLocked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** T98: timeout rules with a fake clock, visibility sets and authenticator availability with fakes. */
class FolderLockTest {
    private fun app(name: String, pkg: String = "pkg.$name") =
        AppEntry(AppId("$pkg/$name@0"), name, pkg, false, 0)

    private val bank = app("Bank")
    private val notes = app("Notes")
    private val mail = app("Mail")
    private val apps = listOf(bank, notes, mail)
    private val vault = Folder(FolderId("vault"), "Vault", appIds = listOf(bank.id)).withLocked(true)
    private val daily = Folder(FolderId("daily"), "Daily", appIds = listOf(notes.id, mail.id))

    @Test fun hiddenFolderProjectionNeverExposesItsNameOrMembershipButKeepsStoredChoices() {
        val shown = LockedApps.folderForDisplay(vault, true, true)
        assertEquals("Locked", shown.name)
        assertTrue(shown.appIds.isEmpty())
        assertEquals(vault, LockedApps.folderForDisplay(vault, false, true))
        assertEquals(vault.name, LockedApps.folderForDisplay(vault, true, false).name)
    }

    // ---- timeout rules -------------------------------------------------------------------

    @Test fun theDefaultIsOneMinuteAndEveryChoiceHasAStableId() {
        assertEquals(UnlockTimeout.Minute1, UnlockTimeout.Default)
        assertEquals(listOf(30_000L, 60_000L, 300_000L, null), UnlockTimeout.entries.map { it.millis })
        UnlockTimeout.entries.forEach { assertEquals(it, UnlockTimeout.fromId(it.id)) }
        assertEquals(UnlockTimeout.Minute1, UnlockTimeout.fromId("nonsense"))
        assertEquals(UnlockTimeout.Minute1, UnlockTimeout.fromId(null))
    }

    @Test fun lockedBeforeAnyUnlock() {
        assertFalse(UnlockState().isUnlocked(0))
        assertEquals(null, UnlockState().remainingMs(0))
    }

    @Test fun oneMinuteWindowEndsExactlyAtSixtySeconds() {
        val state = UnlockState().unlock(1_000, UnlockTimeout.Minute1)
        assertTrue(state.isUnlocked(1_000))
        assertTrue(state.isUnlocked(60_999))
        assertEquals(1L, state.remainingMs(60_999))
        assertFalse(state.isUnlocked(61_000))
        assertEquals(null, state.remainingMs(61_000))
    }

    @Test fun thirtySecondsAndFiveMinutes() {
        val short = UnlockState().unlock(0, UnlockTimeout.Seconds30)
        assertTrue(short.isUnlocked(29_999)); assertFalse(short.isUnlocked(30_000))
        val long = UnlockState().unlock(0, UnlockTimeout.Minutes5)
        assertTrue(long.isUnlocked(299_999)); assertFalse(long.isUnlocked(300_000))
    }

    @Test fun untilScreenOffNeverExpiresByTime() {
        val state = UnlockState().unlock(0, UnlockTimeout.UntilScreenOff)
        assertTrue(state.isUnlocked(Long.MAX_VALUE / 2))
        assertEquals(null, state.remainingMs(10))
    }

    @Test fun relockEndsEveryWindowAtOnce() {
        for (timeout in UnlockTimeout.entries) {
            val state = UnlockState().unlock(0, timeout)
            assertTrue(state.isUnlocked(1))
            assertFalse(state.relock().isUnlocked(1))
        }
    }

    @Test fun aClockThatMovesBackwardsCountsAsExpired() {
        assertFalse(UnlockState().unlock(10_000, UnlockTimeout.Minutes5).isUnlocked(9_000))
    }

    @Test fun aNewUnlockRestartsTheWindowWithTheCurrentTimeout() {
        val first = UnlockState().unlock(0, UnlockTimeout.Seconds30)
        val second = first.unlock(25_000, UnlockTimeout.Minute1)
        assertTrue(second.isUnlocked(80_000))
        assertFalse(second.isUnlocked(85_000))
    }

    // ---- visibility ----------------------------------------------------------------------

    @Test fun lockedAppsAreHiddenByPackageWhileLockedAndShownWhenUnlocked() {
        val folders = listOf(vault, daily)
        assertEquals(setOf("pkg.Bank"), LockedApps.lockedPackages(apps, folders, unlocked = false))
        assertEquals(emptySet<String>(), LockedApps.lockedPackages(apps, folders, unlocked = true))
        assertEquals(setOf(vault.id), LockedApps.lockedFoldersNow(folders, unlocked = false))
        assertEquals(emptySet<FolderId>(), LockedApps.lockedFoldersNow(folders, unlocked = true))
    }

    @Test fun noLockedFolderMeansNothingIsHiddenAndTheSameListIsReturned() {
        assertEquals(emptySet<String>(), LockedApps.lockedPackages(apps, listOf(daily), false))
        assertSame(apps, LockedApps.visibleApps(apps, emptySet()))
    }

    @Test fun visibleAppsDropEveryLockedPackageEvenWhenTheAppIsAlsoInAnotherFolder() {
        val both = daily.copy(appIds = daily.appIds + bank.id)
        val hidden = LockedApps.lockedPackages(apps, listOf(vault, both), false)
        assertEquals(listOf(notes, mail), LockedApps.visibleApps(apps, hidden))
    }

    @Test fun aSecondActivityOrProfileCopyOfALockedPackageIsHiddenToo() {
        val copy = AppEntry(AppId("pkg.Bank/other.Activity@10"), "Bank", "pkg.Bank", false, 0)
        val hidden = LockedApps.lockedPackages(apps + copy, listOf(vault), false)
        assertEquals(listOf(notes, mail), LockedApps.visibleApps(apps + copy, hidden))
    }

    @Test fun badgeAndMenuCountsLoseHiddenPackages() {
        val counts = mapOf("pkg.Bank" to 4, "pkg.Mail" to 1)
        assertEquals(mapOf("pkg.Mail" to 1), LockedApps.visibleCounts(counts, setOf("pkg.Bank")))
        assertSame(counts, LockedApps.visibleCounts(counts, emptySet()))
    }

    @Test fun aRememberedSearchThatWouldRevealALockedAppIsRecognised() {
        val labels = listOf("Bank")
        assertTrue(LockedApps.queryRevealsLockedApp("bank", labels))
        assertTrue(LockedApps.queryRevealsLockedApp("  BAN ", labels))
        assertTrue(LockedApps.queryRevealsLockedApp("my bank app", labels))
        assertFalse(LockedApps.queryRevealsLockedApp("mail", labels))
        assertFalse(LockedApps.queryRevealsLockedApp("", labels))
        assertFalse(LockedApps.queryRevealsLockedApp("bank", emptyList()))
    }

    // ---- authenticator availability ------------------------------------------------------

    private class FakeProbe(val code: Int, val secure: Boolean) {
        fun availability() = AuthenticatorRules.availability(code, secure)
    }

    @Test fun lockingIsOfferedOnlyWithASecureScreenLockOrBiometric() {
        assertEquals(AuthAvailability.Available, FakeProbe(AuthenticatorRules.Success, true).availability())
        assertTrue(FakeProbe(AuthenticatorRules.Success, true).availability().usable)
    }

    @Test fun noSecureLockScreenIsRefusedWithAnExplanation() {
        val noLock = FakeProbe(AuthenticatorRules.ErrorNoneEnrolled, false).availability()
        assertEquals(AuthAvailability.NoSecureLock, noLock)
        assertFalse(noLock.usable)
        assertTrue(noLock.message.contains("screen lock", ignoreCase = true))
        assertEquals(AuthAvailability.NoSecureLock, FakeProbe(AuthenticatorRules.Success, false).availability())
        assertEquals(AuthAvailability.NoSecureLock, FakeProbe(AuthenticatorRules.ErrorNoHardware, false).availability())
    }

    @Test fun otherPlatformAnswersMapToTheirOwnMessages() {
        assertEquals(AuthAvailability.NoHardware, FakeProbe(AuthenticatorRules.ErrorNoHardware, true).availability())
        assertEquals(AuthAvailability.NoHardware, FakeProbe(AuthenticatorRules.ErrorUnsupported, true).availability())
        assertEquals(AuthAvailability.TemporarilyUnavailable, FakeProbe(AuthenticatorRules.ErrorHwUnavailable, true).availability())
        assertEquals(AuthAvailability.UpdateRequired, FakeProbe(AuthenticatorRules.ErrorSecurityUpdateRequired, true).availability())
        assertEquals(AuthAvailability.TemporarilyUnavailable, FakeProbe(AuthenticatorRules.StatusUnknown, true).availability())
        AuthAvailability.entries.filter { it != AuthAvailability.Available }.forEach {
            assertFalse(it.usable); assertTrue(it.message.isNotBlank())
        }
    }

    @Test fun summaryTextAndHonestyLine() {
        assertEquals("None locked", LockedFolderText.summary(0))
        assertEquals("1 locked", LockedFolderText.summary(1))
        assertEquals("3 locked", LockedFolderText.summary(3))
        assertTrue(LockedFolderText.Honesty.contains("Nothing is encrypted"))
        assertTrue(LockedFolderText.Honesty.contains("other launchers"))
    }
}
