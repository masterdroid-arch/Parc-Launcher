package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.core.data.library.BookFormat
import com.brightblu.parc.core.data.library.BookPosition
import com.brightblu.parc.core.data.library.LibraryStore
import com.brightblu.parc.core.data.library.ReaderBookmark
import com.brightblu.parc.core.data.library.ReaderBookDoc
import com.brightblu.parc.core.data.library.ReaderHighlight
import com.brightblu.parc.core.data.library.ReaderHighlightTone
import com.brightblu.parc.core.data.library.ReaderMarksMath
import com.brightblu.parc.core.data.library.ReaderPageColor
import com.brightblu.parc.core.data.library.ReaderSettings
import com.brightblu.parc.core.data.skins.ColorMath
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.reader.ReaderSelection
import com.brightblu.parc.reader.ReaderWebDocument
import com.brightblu.parc.reader.readerHighlightColor
import com.brightblu.parc.reader.readerHighlightCss
import com.brightblu.parc.reader.readerHighlightColorsJson
import com.brightblu.parc.reader.readerColors
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class T236ReaderTest {
    @Test fun readerHtmlCarriesHighlightRangesBookmarksAndTheSelectionBridge() {
        val doc = ReaderBookDoc.plainText("book", "A Book", "A Writer", "One two three four five six")
        val highlight = ReaderHighlight("h", 0, 4, 7, ReaderHighlightTone.Sage, text = "two")
        val bookmark = ReaderBookmark("b", chapter = 0, characterOffset = 8, snippet = "three")
        val colors = readerHighlightColorsJson(Skin.Midnight)
        val html = ReaderWebDocument.build(doc, ReaderSettings(),
            readerColors(com.brightblu.parc.core.data.library.ReaderPageColor.Light, Skin.Midnight),
            360f, 640f, BookPosition(), 3, listOf(bookmark), listOf(highlight), colors)
        assertTrue(html.contains("Reader.selection"))
        assertTrue(html.contains("function beginSelection"))
        assertTrue(html.contains("function applyHighlights"))
        assertTrue(html.contains("function bookmarkOnPage"))
        assertTrue(html.contains("Reader.bookmarkPage"))
        assertTrue(html.contains("""[{"c":0,"s":4,"e":7,"t":"sage"}]"""))
        assertTrue(html.contains("""[{"c":0,"o":8,"p":-1}]"""))
        assertTrue(html.contains("rgba("))
        assertTrue(html.contains("data-parc-mark"))
        File("build/t236-reader.html").apply { parentFile.mkdirs(); writeText(html) }
    }

    @Test fun theSelectionPayloadMapsCharacterOffsetsAndNormalisedHandles() {
        val selection = ReaderSelection.parse(
            """{"chapter":2,"startOffset":10,"endOffset":14,"text":"word",
                "rects":[[0.1,0.2,0.3,0.25]],"start":[0.1,0.25],"end":[0.3,0.25]}""")
        assertNotNull(selection)
        assertEquals(2, selection!!.chapter)
        assertEquals(10, selection.start)
        assertEquals(14, selection.end)
        assertEquals("word", selection.text)
        assertEquals(1, selection.rects.size)
        assertEquals(0.1f, selection.startHandle.first, 0.001f)
        assertNull(ReaderSelection.parse(null))
        assertNull(ReaderSelection.parse("bad json"))
        assertNull(ReaderSelection.parse("""{"chapter":0,"startOffset":2,"endOffset":2,"text":""}"""))
    }

    @Test fun highlightWashesComeFromThreeDistinctSkinTokensInEveryBuiltInSkin() {
        val skins = listOf(Skin.Midnight, Skin.Dusk)
        for (skin in skins) {
            val tones = listOf(skin.colors.highlightSand, skin.colors.highlightSage, skin.colors.highlightRose)
            assertEquals(3, tones.distinct().size)
            ReaderHighlightTone.entries.forEach { tone ->
                val css = readerHighlightCss(skin, tone)
                assertTrue("${skin.id} $tone", css.startsWith("rgba("))
                assertTrue(css.endsWith(",0.380)"))
            }
        }
        assertNotEquals(Skin.Midnight.colors.highlightSand, Skin.Midnight.colors.highlightRose)
    }

    @Test fun highlightWashesKeepTheReaderInkReadableOnEveryPageColour() {
        val alpha = (Metrics.readerHighlightAlpha * 255f).toInt().toLong() shl 24
        for (skin in listOf(Skin.Midnight, Skin.Dusk)) {
            for (page in ReaderPageColor.entries) {
                val colors = readerColors(page, skin)
                for (tone in ReaderHighlightTone.entries) {
                    val wash = (readerHighlightColor(skin, tone) and 0x00FFFFFFL) or alpha
                    val background = ColorMath.over(wash, colors.paper)
                    val ratio = ColorMath.contrast(colors.ink, background)
                    assertTrue("${skin.id} $page $tone $ratio", ratio >= 4.5)
                }
            }
        }
    }

    @Test fun readerUiWiresLongPressBookmarkRibbonMarksAndTheLightlyConfirmSheet() {
        val root = File("src/main/kotlin/com/brightblu/parc/reader")
        val screen = File(root, "ReaderScreen.kt").readText()
        val pdf = File(root, "ReaderPdf.kt").readText()
        val ui = File(root, "ReaderMarksUi.kt").readText()
        assertTrue(screen.contains("onLongPress ="))
        assertTrue(screen.contains("beginSelection("))
        assertTrue(screen.contains("ReaderSelectionLayer("))
        assertTrue(screen.contains("ReaderSelectionMenu("))
        assertTrue(screen.contains("ReaderBookmarkRibbon("))
        assertTrue(screen.contains("ReaderBarAction(if (bookmarked) \"Bookmarked\" else \"Bookmark\""))
        assertTrue(screen.contains("ReaderSheet.Marks"))
        assertTrue(screen.contains("QuoteEditorSheet(draft, skin, quotesStore)"))
        assertTrue(ui.contains("ReaderMarksMath.PdfMarksExplanation"))
        assertTrue(ui.contains("ReaderBarAction(\"Add to Commonplace\""))
        assertTrue(ui.contains("onLongClickLabel = \"Delete mark\""))
        assertTrue(ui.contains("detectHorizontalDragGestures("))
        assertTrue(pdf.contains("ReaderSheet.Marks"))
        assertTrue(pdf.contains("Bookmarked"))
        assertTrue(pdf.contains("ReaderMarksSheet("))
    }

    @Test fun marksShareTheLibraryBackupSectionAndTheStoreKeepsThemPerBook() {
        assertEquals(listOf("books_v1", "marks_v1", "shelves_v1"), BackupSpecs.library.keys.map { it.name })
        assertTrue(BackupSpecs.library in BackupSpecs.all)
        assertTrue(LibraryStore.MarksKeyName in BackupSpecs.library.keys.map { it.name })
        assertEquals(BookFormat.EPUB, BookFormat.entries.first())
        assertNotNull(ReaderMarksMath.bookmarkForText("b", BookPosition(1, 2), "text", 3))
    }

    @Test fun selectionAndBookmarkPayloadNeedNoThirdPartyReaderLibrary() {
        val ui = File("src/main/kotlin/com/brightblu/parc/reader/ReaderMarksUi.kt").readText()
        assertTrue(ui.contains("Json.parseToJsonElement(raw)"))
        assertTrue(ui.contains("json[\"rects\"]"))
        assertFalse(ui.contains("RenderEffect"))
        assertFalse(ui.contains("BlurMaskFilter"))
    }
}
