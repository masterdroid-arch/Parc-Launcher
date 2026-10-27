package com.brightblu.parc.feature.settings.folders

import com.brightblu.parc.model.fakes.FakeLayoutRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence through the frozen `LayoutRepository`: creating, renaming, adding, removing and
 * reordering, and the refusal to delete a folder the contract cannot delete.
 */
class LayoutFolderWriterTest {

    private val a = testId("a")
    private val b = testId("b")
    private val c = testId("c")
    private val d = testId("d")
    private val social = testFolder("social", "Social", listOf(a, b, c))
    private val tools = testFolder("tools", "Tools", listOf(d))

    private val repository = FakeLayoutRepository(testLayout(social))
    private val recorder = RecordingLayout(repository)
    private val writer = LayoutFolderWriter(recorder)

    private val before = FolderDocument(listOf(social))

    private fun storedFolder(id: String) = repository.layout.value.folders.first { it.id.value == id }

    @Test
    fun `an unchanged document writes nothing`() = runTest {
        assertEquals(FolderEditResult.Applied, writer.apply(before, before))
        assertTrue(recorder.calls.isEmpty())
    }

    @Test
    fun `a new folder is created with its members in order`() = runTest {
        val after = FolderDocument(listOf(social, tools))
        assertEquals(FolderEditResult.Applied, writer.apply(before, after))
        assertEquals(listOf("createFolder:tools", "addToFolder:tools:${d.value}"), recorder.calls)
        assertEquals("Tools", storedFolder("tools").name)
        assertEquals(listOf(d), storedFolder("tools").appIds)
    }

    @Test
    fun `a rename reaches the repository and is not repeated when nothing changed`() = runTest {
        val renamed = FolderDocument(listOf(social.copy(name = "Chat")))
        writer.apply(before, renamed)
        assertEquals(listOf("renameFolder:social:Chat"), recorder.calls)
        assertEquals("Chat", storedFolder("social").name)

        recorder.calls.clear()
        writer.apply(renamed, renamed)
        assertTrue(recorder.calls.isEmpty())
    }

    @Test
    fun `an appended app is added without rewriting the folder`() = runTest {
        val after = FolderDocument(listOf(social.copy(appIds = listOf(a, b, c, d))))
        writer.apply(before, after)
        assertEquals(listOf("addToFolder:social:${d.value}"), recorder.calls)
        assertEquals(listOf(a, b, c, d), storedFolder("social").appIds)
    }

    @Test
    fun `a removed app is dropped without rewriting the folder`() = runTest {
        val after = FolderDocument(listOf(social.copy(appIds = listOf(a, c))))
        writer.apply(before, after)
        assertEquals(listOf("removeFromFolder:social:${b.value}"), recorder.calls)
        assertEquals(listOf(a, c), storedFolder("social").appIds)
    }

    @Test
    fun `a reordered folder is rewritten in the new order`() = runTest {
        val after = FolderDocument(listOf(social.copy(appIds = listOf(c, a, b))))
        writer.apply(before, after)
        assertEquals(listOf(c, a, b), storedFolder("social").appIds)
        assertEquals(
            listOf(a, b, c).map { "removeFromFolder:social:${it.value}" } +
                listOf(c, a, b).map { "addToFolder:social:${it.value}" },
            recorder.calls,
        )
    }

    @Test
    fun `a drop on an empty slot persists as the packed order`() = runTest {
        val after = FolderDocument(listOf(social.copy(appIds = listOf(b, c, a))))
        writer.apply(before, after)
        assertEquals(listOf(b, c, a), storedFolder("social").appIds)
    }

    @Test
    fun `a folder missing from the next document is reported and nothing is written`() = runTest {
        val previous = FolderDocument(listOf(social, tools))
        val deleteRepository = FakeLayoutRepository(testLayout(social, tools))
        val deleteRecorder = RecordingLayout(deleteRepository)
        val deleteWriter = LayoutFolderWriter(deleteRecorder)
        val next = FolderDocument(listOf(social.copy(name = "Chat")))

        val result = deleteWriter.apply(previous, next)

        assertEquals(FolderEditResult.Unsupported("Parc cannot delete folders yet"), result)
        assertTrue(deleteRecorder.calls.isEmpty())
        assertEquals("Social", deleteRepository.layout.value.folders.first { it.id.value == "social" }.name)
        assertEquals(listOf(tools), deleteRepository.layout.value.folders.filter { it.id.value == "tools" })
    }

    @Test
    fun `pins are not written by the writer`() = runTest {
        val after = before.copy(pins = setOf(a, b))
        assertEquals(FolderEditResult.Applied, writer.apply(before, after))
        assertTrue(recorder.calls.isEmpty())
        assertTrue(repository.layout.value.pins.isEmpty())
    }
}
