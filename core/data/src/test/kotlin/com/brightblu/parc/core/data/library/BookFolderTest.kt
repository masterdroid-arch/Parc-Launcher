package com.brightblu.parc.core.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.brightblu.parc.core.data.backup.BackupSpecs
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** T225: chosen-folder storage, scan exclusions and the limit to the roots the user ticked. */
class BookFolderTest {
    @get:Rule val temp = TemporaryFolder(File("build/t225-test-files").apply { mkdirs() })

    private class Memory : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    @Test fun folderJsonRoundTripsPlainAndTreeFoldersAndRecoversFromBadData() {
        val folders = listOf(
            BookFolder("/storage/emulated/0/Download", "Shared storage/Download"),
            BookFolder("content://com.android.providers.downloads.documents/tree/downloads", "Downloads", tree = true),
        )
        assertEquals(folders, BookFolderJson.decode(BookFolderJson.encode(folders)))
        for (text in listOf(null, "", "{}", "[]", "broken", "{\"folders\":null}")) {
            assertEquals(emptyList<BookFolder>(), BookFolderJson.decode(text))
        }
        val mixed = """{"folders":[{"location":"/a","label":"A"},{"location":"bad"},{"location":"/a","label":"Again"}]}"""
        assertEquals(listOf(BookFolder("/a", "A")), BookFolderJson.decode(mixed))
        val oldTree = """{"folders":[{"location":"content://provider/tree/books","label":"Books"}]}"""
        assertEquals(listOf(BookFolder("content://provider/tree/books", "Books", tree = true)),
            BookFolderJson.decode(oldTree))
    }

    @Test fun chosenFoldersAreStoredOnThePhoneOnly() = runTest {
        val memory = Memory()
        val store = BookFolderStore(memory, StandardTestDispatcher(testScheduler))
        val first = BookFolder("/storage/emulated/0/Books", "Shared storage/Books")
        val second = BookFolder("content://tree/documents", "Documents", tree = true)
        assertEquals(emptyList<BookFolder>(), store.snapshot())
        store.save(listOf(first, second, first))
        assertEquals(listOf(first, second), store.snapshot())
        assertEquals(listOf(first, second), store.folders.first())
        assertTrue(BackupSpecs.all.none { spec -> spec.keys.any { it.name == BookFolderStore.KeyName } })
    }

    @Test fun appPrivateFoldersAreNeverScannedAndWhatsAppMediaIsNotSuggested() {
        assertTrue(BookFolderRules.isAppPrivate("/storage/emulated/0/Android/data/com.example"))
        assertTrue(BookFolderRules.isAppPrivate("/storage/emulated/0/ANDROID/OBB/com.example"))
        assertTrue(BookFolderRules.isAppPrivate("/storage/emulated/0/Android/data"))
        assertTrue(BookFolderRules.isAppPrivate("primary:Android/data/com.example"))
        assertFalse(BookFolderRules.isAppPrivate("/storage/emulated/0/Android/media/com.example"))
        assertFalse(BookFolderRules.isAppPrivate("/storage/emulated/0/Download"))
        assertTrue(BookFolderRules.isWhatsAppMedia("/storage/emulated/0/WhatsApp/Media/WhatsApp Images"))
        assertTrue(BookFolderRules.isWhatsAppMedia("/storage/emulated/0/WhatsApp Business/Media"))
        assertFalse(BookFolderRules.isWhatsAppMedia("/storage/emulated/0/WhatsApp/Databases"))
        assertFalse(BookFolderRules.isWhatsAppMedia("/storage/emulated/0/WhatsApp/MediaFoo"))
        assertFalse(BookFolderRules.isSuggestable("/storage/emulated/0/WhatsApp/Media"))
        assertTrue(BookFolderRules.isSuggestable("/storage/emulated/0/Download"))
        val index = BookIndex(
            files = listOf(
                IndexedBook("/chosen/One.pdf", "One.pdf", "Chosen", BookFormat.PDF, 1, 1, "/chosen"),
                IndexedBook("/other/Two.pdf", "Two.pdf", "Other", BookFormat.PDF, 2, 2, "/other"),
                IndexedBook("content://picked/Three.txt", "Three.txt", "Download", BookFormat.TXT, 3, 3, ""),
            ),
            folders = listOf(IndexedFolder("/chosen", "Chosen", 1, 1, 1), IndexedFolder("/other", "Other", 2, 2, 1)),
        )
        val retained = BookFolderRules.retainFolders(index, setOf("/chosen"))
        assertEquals(setOf("/chosen/One.pdf", "content://picked/Three.txt"), retained.files.map { it.location }.toSet())
        assertEquals(listOf("/chosen"), retained.folders.map { it.path })
    }

    @Test fun scanUsesOnlyTheChosenRootsAndSkipsAndroidData() = runTest {
        val chosen = temp.newFolder("Chosen").canonicalFile
        val other = temp.newFolder("Other").canonicalFile
        File(chosen, "One.pdf").writeText("one")
        File(chosen, "Nested").mkdirs()
        File(chosen, "Nested/Two.epub").writeText("two")
        File(chosen, "Android/data/com.example/Hidden.pdf").apply { parentFile.mkdirs() }.writeText("hidden")
        File(other, "Outside.pdf").writeText("outside")
        val scanner = BookScanner({ listOf(chosen, other) }, { true }, StandardTestDispatcher(testScheduler))

        val onlyChosen = scanner.find(BookFormat.entries.toSet(), listOf(chosen)) as BookScanResult.Files
        assertEquals(setOf("One.pdf", "Two.epub"), onlyChosen.files.map { it.name }.toSet())
        assertTrue(onlyChosen.files.all { it.root == chosen.absolutePath })
        assertFalse(onlyChosen.files.any { it.name == "Hidden.pdf" })
        assertEquals(2, onlyChosen.files.size)

        val both = scanner.find(BookFormat.entries.toSet(), listOf(chosen, other)) as BookScanResult.Files
        assertEquals(setOf("One.pdf", "Two.epub", "Outside.pdf"), both.files.map { it.name }.toSet())
    }
}
