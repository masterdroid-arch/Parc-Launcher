package com.brightblu.parc

import com.brightblu.parc.core.data.comms.CommsConversation
import com.brightblu.parc.core.data.comms.CommsConversationId
import com.brightblu.parc.core.data.focus.FocusJson
import com.brightblu.parc.core.data.focus.FocusMode
import com.brightblu.parc.core.data.focus.FocusState
import com.brightblu.parc.core.data.media.MediaPlayState
import com.brightblu.parc.core.data.media.MediaRead
import com.brightblu.parc.core.data.media.MediaRules
import com.brightblu.parc.core.data.media.MediaSessionInfo
import com.brightblu.parc.core.data.notifications.PeekEntry
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.AppIndex
import com.brightblu.parc.model.AppShortcut
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.ParcLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T217: one visibility set, the Focus message precedence and the surfaces that consume them. */
class VisibilityTest {
    private fun app(label: String, packageName: String) =
        AppEntry(AppId("$packageName/$label"), label, packageName, isSystem = false, installTime = 0L)

    private val visible = app("Visible", "test.visible")
    private val permanent = app("Permanent", "test.permanent")
    private val focused = app("Focused", "test.focused")
    private val locked = app("Locked", "test.locked")
    private val folderHidden = app("Folder", "test.folder")
    private val apps = listOf(visible, permanent, focused, locked, folderHidden)
    private val hiddenFolder = Folder(FolderId("quiet"), "Quiet", tags = setOf(Visibility.HiddenFolderTag),
        appIds = listOf(folderHidden.id))
    private val layout = ParcLayout(hidden = setOf(permanent.id), folders = listOf(hiddenFolder))
    private val focusMode = FocusMode("Work", hiddenPackages = setOf(focused.packageName))

    @Test fun hiddenCombinesEverySourceAndKeepsTheStoredLayoutUntouched() {
        val lockedNow = setOf(locked.packageName)
        val before = layout
        val visibility = Visibility.of(apps, layout, focusMode, lockedNow)
        assertEquals(setOf(permanent.packageName), visibility.permanentHidden)
        assertEquals(setOf(focused.packageName), visibility.focusBlocked)
        assertEquals(lockedNow, visibility.lockedNow)
        assertEquals(setOf(folderHidden.packageName), visibility.hiddenFolderApps)
        assertEquals(setOf(permanent.packageName, focused.packageName, locked.packageName, folderHidden.packageName),
            visibility.hidden)
        assertTrue(visibility.isHidden(focused.packageName))
        assertFalse(visibility.isHidden(visible.packageName))
        assertEquals(before, layout)
    }

    @Test fun hidingEndsWhenEachSourceEndsWithoutAStoredLayoutChange() {
        val normal = Visibility.of(apps, layout.copy(hidden = emptySet(), folders = emptyList()),
            null, emptySet())
        assertTrue(normal.hidden.isEmpty())
        assertTrue(normal.messageBlocked.isEmpty())
        assertEquals(layout.copy(hidden = setOf(permanent.id), folders = listOf(hiddenFolder)), layout)
    }

    @Test fun focusHidesMessagesByDefaultAndAlwaysShowIsTheOnlyFocusException() {
        val blocked = Visibility.of(apps, layout, focusMode, emptySet())
        assertTrue(focused.packageName in blocked.messageBlocked)
        assertFalse(visible.packageName in blocked.messageBlocked)

        val exception = focusMode.copy(alwaysShowMessagePackages = setOf(focused.packageName))
        val allowed = Visibility.of(apps, layout, exception, emptySet())
        assertFalse(focused.packageName in allowed.messageBlocked)
        // The app itself is still hidden; only its messages are shown.
        assertTrue(focused.packageName in allowed.hidden)
    }

    @Test fun permanentLockAndHiddenFolderOutrankTheAlwaysShowException() {
        val exception = focusMode.copy(alwaysShowMessagePackages =
            setOf(focused.packageName, permanent.packageName, locked.packageName, folderHidden.packageName))

        val permanentHidden = Visibility.of(apps, layout, exception, emptySet())
        assertTrue(permanent.packageName in permanentHidden.messageBlocked)

        val lockedNow = Visibility.of(apps, layout.copy(hidden = emptySet(), folders = emptyList()), exception,
            setOf(locked.packageName))
        assertTrue(locked.packageName in lockedNow.messageBlocked)

        val hiddenFolderLayout = layout.copy(hidden = emptySet())
        val hiddenFolder = Visibility.of(apps, hiddenFolderLayout, exception, emptySet())
        assertTrue(folderHidden.packageName in hiddenFolder.messageBlocked)
    }

    @Test fun hiddenCommsAlwaysWinsOverTheAlwaysShowList() {
        val mode = focusMode.copy(hiddenPackages = emptySet(),
            hiddenComms = setOf(focused.packageName),
            alwaysShowMessagePackages = setOf(focused.packageName))
        val visibility = Visibility.of(apps, layout, mode, emptySet())
        assertTrue(focused.packageName in visibility.messageBlocked)
    }

    @Test fun messagesAndMediaUseTheComputedSet() {
        val visibility = Visibility.of(apps, layout, focusMode, setOf(locked.packageName))
        val conversations = apps.map { entry ->
            CommsConversation(CommsConversationId(entry.packageName, "key"), entry.label, emptyList(), true, 1L)
        }
        val live = conversations.filterNot { it.app in visibility.messageBlocked }
        assertEquals(listOf(visible.packageName), live.map { it.app })

        val sessions = apps.map { entry ->
            MediaSessionInfo(entry.packageName, entry.packageName, entry.label, null, null,
                MediaPlayState.Playing, 0L, 0L, 1f, 10_000L)
        }
        val shown = MediaRules.withoutHidden(MediaRead.Sessions(sessions), visibility.hidden) as MediaRead.Sessions
        assertEquals(listOf(visible.packageName), shown.sessions.map { it.packageName })
        assertEquals("Nothing playing",
            MediaRules.emptyLine(MediaRules.withoutHidden(MediaRead.Sessions(emptyList()), visibility.hidden)))
    }

    @Test fun notificationRowsAndFolderCountsAreEmptyForAHiddenPackage() {
        val entries = listOf(PeekEntry("k", focused.packageName, "Title", "Body", 1L),
            PeekEntry("v", visible.packageName, "Title", "Body", 2L))
        assertTrue(menuLines(focused.packageName, entries, enabled = true, showText = true,
            blocked = setOf(focused.packageName)).isEmpty())
        assertEquals(listOf("k"), menuLines(focused.packageName, entries, enabled = true, showText = true,
            blocked = emptySet()).map { it.key })
        assertEquals(setOf(visible.packageName), menuCounts(entries, enabled = true,
            blocked = setOf(focused.packageName)).keys)
        assertTrue(menuCounts(entries, enabled = false, blocked = emptySet()).isEmpty())
    }

    @Test fun alwaysShowRoundTripsAndAnOldFocusJsonDecodesToEmpty() {
        val mode = FocusMode("Work", alwaysShowMessagePackages = setOf(focused.packageName))
        assertEquals(listOf(mode), FocusJson.decodeModes(FocusJson.encodeModes(listOf(mode))))
        val old = FocusJson.decodeModes("""[{"name":"Old"}]""").single()
        assertEquals(emptySet<String>(), old.alwaysShowMessagePackages)
    }

    @Test fun modeSentenceNamesBothTheHideListAndTheException() {
        val mode = FocusMode("Work", hiddenPackages = setOf(focused.packageName),
            hiddenComms = setOf(locked.packageName),
            alwaysShowMessagePackages = setOf(focused.packageName))
        val sentence = focusModeSentence(mode)
        assertTrue(sentence.contains("messages from 1 app"))
        assertTrue(sentence.contains("messages always shown from 1 app"))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun searchAndShortcutsExcludePermanentFocusLockAndHiddenFolderApps() = runTest {
        val delegate = FakeAppIndex(apps)
        val state = MutableStateFlow(FocusState(active = focusMode))
        val lockedNow = MutableStateFlow(setOf(locked.packageName))
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        try {
            val view = FocusAppIndex(delegate, state, scope, MutableStateFlow(layout), lockedNow)
            runCurrent()
            assertEquals(listOf(visible), view.apps.value)
            listOf(permanent, focused, locked, folderHidden).forEach { entry ->
                assertTrue("${entry.label} shortcut leaked", view.shortcuts(entry.id).isEmpty())
            }
            assertEquals(1, view.shortcuts(visible.id).size)
        } finally { scope.cancel() }
    }

    private class FakeAppIndex(entries: List<AppEntry>) : AppIndex {
        override val apps: StateFlow<List<AppEntry>> = MutableStateFlow(entries)
        override fun icon(id: AppId, sizePx: Int): android.graphics.Bitmap? = null
        override fun launch(id: AppId) = Unit
        override fun shortcuts(id: AppId): List<AppShortcut> = listOf(AppShortcut("s", "Shortcut", id))
    }
}
