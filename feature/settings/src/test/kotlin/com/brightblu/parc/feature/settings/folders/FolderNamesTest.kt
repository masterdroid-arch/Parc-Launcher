package com.brightblu.parc.feature.settings.folders

import com.brightblu.parc.model.FolderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Name and key rules. Faces and keys are pure, so the editor never mints a folder twice. */
class FolderNamesTest {

    @Test
    fun `a blank name is refused and a long name is refused at the caption limit`() {
        assertEquals("Name is empty", FolderNames.issue("   "))
        assertEquals("At most ${FolderNames.MaxLength} characters", FolderNames.issue("abcdefghi"))
        assertNull(FolderNames.issue("12345678"))
    }

    @Test
    fun `a name already used is refused, ignoring case and surrounding spaces`() {
        assertEquals("That name is already used", FolderNames.issue(" social ", listOf("Social")))
        assertNull(FolderNames.issue("Social", listOf("Tools")))
    }

    @Test
    fun `normalise trims only`() {
        assertEquals("Back to work", FolderNames.normalise("  Back to work  "))
    }

    @Test
    fun `a key is a lowercase slug of the name`() {
        assertEquals(FolderId("social"), FolderIds.fromName("Social", emptySet()))
        assertEquals(FolderId("back-to-work"), FolderIds.fromName("Back to work", emptySet()))
        assertEquals(FolderId("ai-2"), FolderIds.fromName("AI 2", emptySet()))
    }

    @Test
    fun `a taken key gets a numeric suffix until it is unique`() {
        val taken = setOf(FolderId("social"), FolderId("social-2"))
        assertEquals(FolderId("social-3"), FolderIds.fromName("Social", taken))
    }

    @Test
    fun `a name with no usable characters still gets a key`() {
        assertEquals(FolderId("folder"), FolderIds.fromName("...", emptySet()))
        assertEquals(FolderId("folder"), FolderIds.fromName("", emptySet()))
    }

    @Test
    fun `a key is truncated to the slug length`() {
        val key = FolderIds.fromName("a very long folder name indeed", emptySet(), slugLength = 5)
        assertEquals(FolderId("a-ver"), key)
    }

    @Test
    fun `document keeps one entry per id and appends or replaces`() {
        val social = testFolder("social", "Social", listOf(testId("a")))
        val document = FolderDocument(listOf(social))
        val tools = testFolder("tools", "Tools", emptyList())
        assertEquals(listOf(social, tools), document.withFolder(tools).folders)
        val renamed = social.copy(name = "Chat")
        assertEquals(listOf(renamed), document.withFolder(renamed).folders)
        assertEquals(social, document.folder(FolderId("social")))
        assertNull(document.folder(FolderId("nope")))
        assertNull(document.folder(null))
    }

    @Test
    fun `document reads the folder part of a layout`() {
        val social = testFolder("social", "Social", listOf(testId("a")))
        val layout = testLayout(social, pins = setOf(testId("a")))
        val document = FolderDocument.from(layout)
        assertEquals(listOf(social), document.folders)
        assertEquals(setOf(testId("a")), document.pins)
        assertTrue(document.hidden.isEmpty())
    }

    @Test
    fun `a duplicated folder id is rejected`() {
        val folder = testFolder("social", "Social", emptyList())
        val other = folder.copy(name = "Other")
        assertThrows(IllegalArgumentException::class.java) {
            FolderDocument(listOf(folder, other))
        }
    }
}
