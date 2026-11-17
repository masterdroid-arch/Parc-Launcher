package com.brightblu.parc

import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.BookFormat
import com.brightblu.parc.core.data.library.FoundBook
import com.brightblu.parc.model.Metrics
import java.io.File
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T226: the pure Find books rules and the calm screen's source-level guards. */
class T226Test {
    private fun book(id: String, uri: String, format: BookFormat = BookFormat.EPUB) =
        Book(id, uri, "Title $id", format = format, sizeBytes = 1_000, addedAt = 1_000)

    private fun found(name: String, folder: String, format: BookFormat = BookFormat.EPUB,
        size: Long = 1_000, modified: Long = 1_000) =
        FoundBook("content://found/$name", name, folder, size, modified, format)

    @Test fun theFilterIsOneCompactFourSegmentControl() {
        assertEquals(listOf("PDF", "EPUB", "Text", "Comics"), LibraryFind.types.map(LibraryFind::typeLabel))
        assertEquals(listOf("PDF", "EPUB", "TXT", "CBZ"), LibraryFind.types.map(LibraryFind::badgeLabel))
        assertEquals(setOf(BookFormat.PDF), LibraryFind.toggleType(setOf(BookFormat.PDF, BookFormat.EPUB), BookFormat.EPUB))
        assertEquals(setOf(BookFormat.PDF, BookFormat.EPUB), LibraryFind.toggleType(setOf(BookFormat.PDF), BookFormat.EPUB))
        assertTrue(Metrics.bookFindSegmentHeightDp < Metrics.bookFindChipHeightDp)
    }

    @Test fun resultsAreGroupedUnderFolderHeadings() {
        val rows = LibraryFind.rows(
            listOf(
                found("Zeta.pdf", "Загрузки", BookFormat.PDF),
                found("Alpha.epub", "Books"),
                found("Beta.txt", "Books", BookFormat.TXT),
                found("Gamma.cbz", "Comics", BookFormat.CBZ),
            ),
            emptyList(), LibraryFind.types.toSet(), "", LibrarySort.Name,
        )
        val groups = LibraryFind.groups(rows)
        assertEquals(listOf("Books", "Comics", "Загрузки"), groups.map { it.folder })
        assertEquals(listOf("Alpha.epub", "Beta.txt"), groups.first().rows.map { it.found.name })
        assertEquals(rows.size, groups.sumOf { it.rows.size })
        assertEquals(setOf("Books"), LibraryFind.toggleFolder(emptySet(), "Books"))
        assertEquals(emptySet<String>(), LibraryFind.toggleFolder(setOf("Books"), "Books"))
    }

    @Test fun selectedFilesStayVisibleWhenFiltersAndSearchChange() {
        val pdf = found("Alpha.pdf", "Books", BookFormat.PDF)
        val epub = found("Beta.epub", "Books", BookFormat.EPUB)
        val files = listOf(pdf, epub)
        val onlyPdf = LibraryFind.rows(files, emptyList(), setOf(BookFormat.PDF), "", LibrarySort.Newest)
        assertEquals(listOf("Alpha.pdf"), onlyPdf.map { it.found.name })

        // The selected EPUB is still shown after PDF is the only format left on.
        val kept = LibraryFind.rows(files, emptyList(), setOf(BookFormat.PDF), "", LibrarySort.Newest,
            keepSelected = setOf(epub.uri))
        assertEquals(setOf("Alpha.pdf", "Beta.epub"), kept.map { it.found.name }.toSet())

        // The same applies to a search that no longer matches the selected row.
        val searched = LibraryFind.rows(files, emptyList(), LibraryFind.types.toSet(), "alpha", LibrarySort.Newest,
            keepSelected = setOf(epub.uri))
        assertEquals(setOf("Alpha.pdf", "Beta.epub"), searched.map { it.found.name }.toSet())
    }

    @Test fun selectedAddsAreCountedAndRemovalsStillOnlyTouchShownRows() {
        val inLibrary = book("one", "content://found/One.epub")
        val outside = book("outside", "content://outside")
        val files = listOf(
            found("One.epub", "Books").copy(uri = inLibrary.uri),
            found("Two.epub", "Books"),
            found("Three.pdf", "Books", BookFormat.PDF),
        )
        val selected = setOf(files[1].uri, files[2].uri)
        val plan = LibraryFind.plan(files, listOf(inLibrary, outside), setOf(BookFormat.EPUB), "", LibrarySort.Newest,
            selected, keepSelected = selected)
        assertEquals(setOf("Two.epub", "Three.pdf"), plan.add.map { it.name }.toSet())
        assertEquals(listOf("one"), plan.remove.map { it.id })
        assertFalse(plan.remove.any { it.id == outside.id })
    }

    @Test fun labelsAndEmptyStatesArePlainWords() {
        assertEquals("Add", LibraryFind.addLabel(0))
        assertEquals("Add 3", LibraryFind.addLabel(3))
        assertEquals("Nothing selected", LibraryFind.selectedLabel(0))
        assertEquals("1 selected", LibraryFind.selectedLabel(1))
        assertEquals("4 selected", LibraryFind.selectedLabel(4))
        assertEquals("Pick a format to see your books.",
            LibraryFind.emptyMessage(10, 0, emptySet(), ""))
        assertEquals("No books match “Verão”.",
            LibraryFind.emptyMessage(10, 0, LibraryFind.types.toSet(), "Verão"))
        assertEquals("No books here yet. Choose a folder or scan again.",
            LibraryFind.emptyMessage(0, 0, LibraryFind.types.toSet(), ""))
    }

    @Test fun aRowShowsSizeAndDateUnderTheTitle() {
        val row = found("Преступление и наказание. Том первый.pdf", "Books", BookFormat.PDF,
            size = 1_500_000, modified = 1_794_268_800_000)
        assertEquals("1.5 MB · 10 Nov 2026", LibraryFind.secondaryLine(row, ZoneOffset.UTC))
    }

    @Test fun theDemoFindBooksHaveInventedPlainEnglishNames() {
        assertTrue(DemoFoundBooks.isNotEmpty())
        assertTrue(DemoFoundBooks.all { it.uri.startsWith("demo://") })
        assertEquals(LibraryFind.types.toSet(), DemoFoundBooks.map { it.format }.toSet())
        // T262: invented, plain English demo rows; no real title and no language hint.
        assertTrue(DemoFoundBooks.all { found -> found.name.all { it.code < 128 } })
        assertTrue(DemoFoundBooks.any { it.name.length > 35 })
        assertTrue(DemoFindLibrary.all { demo -> DemoFoundBooks.any { it.uri == demo.uri } })
        assertTrue(DemoFindLibrary.all { demo -> demo.title.all { it.code < 128 } && demo.author.all { it.code < 128 } })
        assertTrue(DemoFindLibrary.none { demo -> demo.title in DemoLibraryBooks.map { it.title } })
        assertTrue(DemoLibraryBooks.all { it.uri.startsWith("demo://") })
    }

    @Test fun theFindBooksScreenUsesSpringsAndHasNoBlur() {
        val source = File("src/main/kotlin/com/brightblu/parc/LibraryPage.kt").readText()
        assertTrue(source.contains("slideInVertically"))
        assertTrue(source.contains("Springs.sheet"))
        assertTrue(source.contains("LineBreak.Paragraph"))
        assertTrue(source.contains("heightIn(min = Metrics.minTouchDp.dp)"))
        assertFalse(source.contains("RenderEffect"))
        assertFalse(source.contains("BlurMaskFilter"))
        assertTrue(Metrics.bookFindSelectedTintAlpha > 0f)
        assertTrue(Metrics.bookFindStickyBarClearanceDp >= Metrics.minTouchDp)
    }
}
