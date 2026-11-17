package com.brightblu.parc.feature.settings.hidden

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.HiddenFolderTag
import com.brightblu.parc.model.LockedFolderTag
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import com.brightblu.parc.model.hidden
import com.brightblu.parc.model.locked
import com.brightblu.parc.model.withHidden
import com.brightblu.parc.model.withLocked
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T218: the hidden-folder list and its unhide/unhide-all writes. */
@OptIn(ExperimentalCoroutinesApi::class)
class HiddenFoldersTest {
    private val a = AppId("com.test.a/com.test.a.Main@0")
    private val b = AppId("com.test.b/com.test.b.Main@0")
    private val chat = Folder(FolderId("chat"), "Chat", appIds = listOf(a, b)).withHidden(true)
    private val tools = Folder(FolderId("tools"), "Tools", appIds = listOf(a))
    private val vault = Folder(FolderId("vault"), "Vault", appIds = listOf(b))
        .withLocked(true).withHidden(true)

    @Test fun onlyHiddenFoldersAreListedWithTheirMemberCountSortedByName() {
        val rows = HiddenFoldersProjection.rows(listOf(vault, tools, chat))
        assertEquals(listOf("Chat", "Vault"), rows.map { it.name })
        assertEquals(listOf(2, 1), rows.map { it.appCount })
        assertEquals(listOf(FolderId("chat"), FolderId("vault")), rows.map { it.folderId })
        assertTrue(HiddenFoldersProjection.state(emptyList()).rows.isEmpty())
        assertTrue(HiddenFoldersProjection.state(listOf(chat)).canUnhideAll)
        assertTrue(HiddenFoldersProjection.state(listOf(chat, vault)).canUnhideAll)
    }

    @Test fun unhideRemovesOnlyTheTagAndKeepsMembersAndTheLock() = runTest {
        val harness = harness()
        runCurrent()
        harness.controller.unhide(FolderId("vault"))
        runCurrent()

        val folder = harness.repository.layout.value.folders.first { it.id == FolderId("vault") }
        assertFalse(folder.hidden)
        assertTrue(folder.locked)
        assertEquals(listOf(b), folder.appIds)
        assertEquals(setOf(LockedFolderTag), folder.tags)
        assertEquals(listOf("Chat"), harness.controller.state.value.rows.map { it.name })
    }

    @Test fun unhideAllClearsEveryHideTagInOneWriteAndLeavesVisibleFoldersAlone() = runTest {
        val harness = harness()
        runCurrent()
        harness.controller.unhideAll()
        runCurrent()

        val folders = harness.repository.layout.value.folders
        assertTrue(folders.none { HiddenFolderTag in it.tags })
        assertEquals(listOf("Chat", "Tools", "Vault"), folders.map { it.name }.sorted())
        assertEquals(listOf(b), folders.first { it.id == FolderId("vault") }.appIds)
        assertTrue(folders.first { it.id == FolderId("vault") }.locked)
        assertTrue(harness.controller.state.value.rows.isEmpty())
    }

    private class Harness(scope: TestScope) {
        val repository = FakeLayoutRepository(ParcLayout(version = 1))
        val controller = HiddenFoldersController(repository, scope.backgroundScope)
    }

    private suspend fun TestScope.harness(): Harness {
        val harness = Harness(this)
        val folders = listOf(
            Folder(FolderId("chat"), "Chat", appIds = listOf(a, b)).withHidden(true),
            Folder(FolderId("tools"), "Tools", appIds = listOf(a)),
            Folder(FolderId("vault"), "Vault", appIds = listOf(b)).withLocked(true).withHidden(true),
        )
        harness.repository.importLayout(com.brightblu.parc.model.LayoutJson.encode(ParcLayout(version = 1, folders = folders)))
        runCurrent()
        return harness
    }
}
