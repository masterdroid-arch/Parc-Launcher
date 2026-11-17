package com.brightblu.parc.core.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.brightblu.parc.core.data.backup.BackupSpecs
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** T224: the Find books index, its cheap markers and its incremental refresh. */
class BookIndexTest {
    @get:Rule val temp = TemporaryFolder(File("build/t224-test-files").apply { mkdirs() })

    private class Memory : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private fun found(path: String, root: String = ""): FoundBook =
        FoundBook(path, File(path).name.ifBlank { "book.pdf" }, "Shared storage/Books", 10, 20,
            BookFormat.PDF, root)

    private fun folder(path: String, modified: Long = 100, count: Int = 5, scanned: Long = 10) =
        IndexedFolder(path, "Shared storage", scanned, modified, count)

    private fun observed(path: String, exists: Boolean = true, marker: FolderMarker? = FolderMarker(100, 5)) =
        FolderObservation(path, "Shared storage", exists, marker)

    @Test fun jsonRoundTripKeepsEveryFieldAndReadsOldRows() {
        val index = BookIndex(
            listOf(
                IndexedBook("/books/One.epub", "One.epub", "Shared storage/Books", BookFormat.EPUB,
                    1234, 20, "/books"),
                IndexedBook("content://provider/two.pdf", "Two.pdf", "Download", BookFormat.PDF,
                    99, 30, ""),
            ),
            listOf(IndexedFolder("/books", "Shared storage", 40, 50, 2)),
        )
        assertEquals(index, BookIndexJson.decode(BookIndexJson.encode(index)))
        for (text in listOf(null, "", "{}", "[]", "broken", "{\"files\":null}")) {
            assertEquals(BookIndex(), BookIndexJson.decode(text))
        }
        val old = """{"location":"/old.txt","name":"Old.txt","folder":"Shared","format":"TXT","sizeBytes":2,"modifiedAt":3,"future":42}"""
        val decoded = BookIndexJson.decode(
            """{"files":[$old,{},$old,{"location":"bad"}],"folders":[{"path":"/root","label":"Books","lastScanAt":1,"modifiedAt":2,"fileCount":3},{}]}""",
        )
        assertEquals(listOf(IndexedBook("/old.txt", "Old.txt", "Shared", BookFormat.TXT, 2, 3)), decoded.files)
        assertEquals("", decoded.files.single().root)
        assertEquals(1, decoded.folders.size)
    }

    @Test fun theMarkerComparesModifiedTimeAndFileCount() {
        val stored = folder("/books")
        assertFalse(BookIndexRules.markerChanged(stored, FolderMarker(100, 5)))
        assertTrue(BookIndexRules.markerChanged(stored, FolderMarker(101, 5)))
        assertTrue(BookIndexRules.markerChanged(stored, FolderMarker(100, 6)))
        assertTrue(BookIndexRules.markerChanged(null, FolderMarker(100, 5)))
        assertTrue(BookIndexRules.markerChanged(stored, null))
    }

    @Test fun firstRunEmptyIndexWalksOnlyExistingFoldersAndASecondRunSkipsUnchangedOnes() {
        val index = BookIndex()
        val observations = listOf(
            observed("/books"),
            observed("/new", marker = FolderMarker(1, 1)),
            observed("/gone", exists = false, marker = null),
        )
        assertEquals(listOf("/books", "/new"), BookIndexRules.targets(index, observations, force = false).map { it.path })
        assertEquals(listOf("/books", "/new"), BookIndexRules.targets(index, observations, force = true).map { it.path })

        val stored = BookIndex(folders = listOf(folder("/books", modified = 100, count = 5)))
        assertEquals(emptyList<String>(),
            BookIndexRules.targets(stored, listOf(observed("/books")), force = false).map { it.path })
        assertEquals(listOf("/books"),
            BookIndexRules.targets(stored, listOf(observed("/books")), force = true).map { it.path })
    }

    @Test fun incrementalMergeAddsRemovesKeepsAndDropsMissingFolders() {
        val oldIndex = BookIndex(
            files = listOf(
                IndexedBook("/a/old.pdf", "old.pdf", "A", BookFormat.PDF, 1, 1, "/a"),
                IndexedBook("/b/keep.epub", "keep.epub", "B", BookFormat.EPUB, 2, 2, "/b"),
                IndexedBook("content://picked/one.txt", "one.txt", "Download", BookFormat.TXT, 3, 3, ""),
            ),
            folders = listOf(folder("/a", scanned = 10), folder("/b", scanned = 11)),
        )
        val changedA = observed("/a", marker = FolderMarker(200, 2))
        val afterAdd = BookIndexRules.merge(oldIndex, changedA, listOf(found("/a/new.pdf", "/a")), now = 50)
        assertEquals(setOf("/a/new.pdf", "/b/keep.epub", "content://picked/one.txt"),
            afterAdd.files.map { it.location }.toSet())
        assertEquals(200, afterAdd.folder("/a")!!.modifiedAt)
        assertEquals(2, afterAdd.folder("/a")!!.fileCount)
        assertEquals(50, afterAdd.folder("/a")!!.lastScanAt)
        assertEquals(11, afterAdd.folder("/b")!!.lastScanAt)

        val afterRemove = BookIndexRules.merge(afterAdd, changedA, emptyList(), now = 60)
        assertEquals(setOf("/b/keep.epub", "content://picked/one.txt"), afterRemove.files.map { it.location }.toSet())

        val afterGone = BookIndexRules.applyObservations(afterRemove,
            listOf(observed("/a", exists = false, marker = null)))
        assertEquals(setOf("/b/keep.epub", "content://picked/one.txt"), afterGone.files.map { it.location }.toSet())
        assertEquals(null, afterGone.folder("/a"))
    }

    @Test fun aFullScanReplacesEveryChosenRootAndPickedFilesSurvive() {
        val index = BookIndex(
            files = listOf(
                IndexedBook("/a/old.pdf", "old.pdf", "A", BookFormat.PDF, 1, 1, "/a"),
                IndexedBook("/b/old.epub", "old.epub", "B", BookFormat.EPUB, 2, 2, "/b"),
                IndexedBook("content://picked/one.txt", "one.txt", "Download", BookFormat.TXT, 3, 3, ""),
            ),
            folders = listOf(folder("/a"), folder("/b")),
        )
        val observations = listOf(observed("/a", marker = FolderMarker(300, 1)),
            observed("/b", marker = FolderMarker(400, 1)))
        val found = listOf(found("/a/new.pdf", "/a"), found("/b/new.epub", "/b"))
        val after = BookIndexRules.mergeFull(index, observations, found, now = 90)
        assertEquals(setOf("/a/new.pdf", "/b/new.epub", "content://picked/one.txt"),
            after.files.map { it.location }.toSet())
        assertEquals(90, after.folder("/a")!!.lastScanAt)
        assertEquals(90, after.folder("/b")!!.lastScanAt)
    }

    @Test fun storeRefreshWalksChangedFoldersOnlyAndPersistsTheIndex() = runTest {
        val memory = Memory()
        val rootA = temp.newFolder("A").canonicalFile
        val rootB = temp.newFolder("B").canonicalFile
        File(rootA, "one.pdf").writeText("one")
        File(rootB, "keep.epub").writeText("keep")
        val folders = listOf(ScanFolder(rootA.path, "A"), ScanFolder(rootB.path, "B"))
        var now = 100L
        val store = BookIndexStore(memory, StandardTestDispatcher(testScheduler), clock = { now })
        val scanner = BookScanner({ listOf(rootA, rootB) }, { true }, StandardTestDispatcher(testScheduler))
        assertEquals(BookIndex(), store.snapshot())

        val first = store.refresh(scanner, folders, BookFormat.entries.toSet(), force = false)
        assertEquals(setOf("one.pdf", "keep.epub"), first.files.map { it.name }.toSet())
        assertEquals(2, first.folders.size)
        assertTrue(first.folders.all { it.lastScanAt == 100L })

        now = 200L
        File(rootA, "two.pdf").writeText("two")
        val added = store.refresh(scanner, folders, BookFormat.entries.toSet(), force = false)
        assertEquals(setOf("one.pdf", "two.pdf", "keep.epub"), added.files.map { it.name }.toSet())
        assertEquals(200L, added.folder(rootA.path)!!.lastScanAt)
        assertEquals("B was not re-walked", 100L, added.folder(rootB.path)!!.lastScanAt)

        now = 300L
        File(rootA, "one.pdf").delete()
        val removed = store.refresh(scanner, folders, BookFormat.entries.toSet(), force = false)
        assertEquals(setOf("two.pdf", "keep.epub"), removed.files.map { it.name }.toSet())
        assertEquals(removed, store.snapshot())
        assertEquals(300L, removed.folder(rootA.path)!!.lastScanAt)

        now = 400L
        val forced = store.refresh(scanner, folders, BookFormat.entries.toSet(), force = true)
        assertEquals(400L, forced.folder(rootA.path)!!.lastScanAt)
        assertEquals(400L, forced.folder(rootB.path)!!.lastScanAt)
    }

    @Test fun vanishedPickedFilesAreDroppedButRootFilesAreLeftToTheirMarker() = runTest {
        val memory = Memory()
        val store = BookIndexStore(memory, StandardTestDispatcher(testScheduler))
        store.save(BookIndex(files = listOf(
            IndexedBook("content://picker/present.pdf", "present.pdf", "Download", BookFormat.PDF, 1, 1, ""),
            IndexedBook("content://picker/gone.pdf", "gone.pdf", "Download", BookFormat.PDF, 1, 1, ""),
            IndexedBook("/books/root.pdf", "root.pdf", "Books", BookFormat.PDF, 1, 1, "/books"),
        )))
        val pruned = store.prunePicked { location -> location.contains("present") || location.startsWith("/books/") }
        assertEquals(setOf("content://picker/present.pdf", "/books/root.pdf"), pruned.files.map { it.location }.toSet())
        assertEquals(pruned, store.snapshot())
    }

    @Test fun theIndexIsNotInAnyBackupAndPickedFilesHaveNoRoot() {
        assertTrue(BackupSpecs.all.none { it.id == "book_index" })
        assertTrue(BackupSpecs.all.none { spec -> spec.keys.any { it.name == BookIndexStore.KeyName } })
        val picked = BookIndexRules.addPicked(BookIndex(), listOf(found("content://provider/picked.pdf", "/ignored")))
        assertEquals("", picked.files.single().root)
        assertNotEquals("", picked.files.single().location)
    }
}
