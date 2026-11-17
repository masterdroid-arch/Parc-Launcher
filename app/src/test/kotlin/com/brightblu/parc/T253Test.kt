package com.brightblu.parc

import com.brightblu.parc.core.data.library.ComicPages
import com.brightblu.parc.core.data.library.ComicZoom
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T253: the comics (CBZ) reader's page order and zoom limits, and a real zip read the same way. */
class T253Test {
    @Test
    fun pagesAreImagesInNaturalOrderWithoutJunk() {
        val names = listOf("10.jpg", "2.jpg", "1.PNG", "__MACOSX/1.jpg", ".hidden.jpg", "notes.txt",
            "chapter 2/", "ch1/003.webp", "ch1/020.webp", "ch1/1.jpeg", "ComicInfo.xml", "cover.gif")
        assertEquals(listOf("1.PNG", "2.jpg", "10.jpg", "ch1/1.jpeg", "ch1/003.webp", "ch1/020.webp"),
            ComicPages.pages(names))
        assertTrue(ComicPages.naturalCompare("page9", "page10") < 0)
        assertTrue(ComicPages.naturalCompare("Page1", "page1") == 0)
        assertFalse(ComicPages.isPage("folder/"))
    }

    @Test
    fun aRealZipIsReadInPageOrder() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            listOf("p10.jpg", "p2.jpg", "p1.jpg", "readme.txt").forEach {
                zip.putNextEntry(ZipEntry(it)); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
            }
        }
        val file = File.createTempFile("t253", ".cbz").apply { writeBytes(out.toByteArray()); deleteOnExit() }
        ZipFile(file).use { zip ->
            assertEquals(listOf("p1.jpg", "p2.jpg", "p10.jpg"),
                ComicPages.pages(zip.entries().asSequence().map { it.name }.toList()))
        }
    }

    @Test
    fun zoomStaysInRangeAndAFittedPageCannotMove() {
        assertEquals(1f, ComicZoom.clamp(0.2f), 0f)
        assertEquals(ComicZoom.Max, ComicZoom.clamp(40f), 0f)
        assertEquals(1f, ComicZoom.clamp(Float.NaN), 0f)
        assertEquals(ComicZoom.DoubleTap, ComicZoom.doubleTap(1f), 0f)
        assertEquals(1f, ComicZoom.doubleTap(2.5f), 0f)
        assertEquals(0f, ComicZoom.panLimit(1000f, 1f), 0f)
        assertEquals(500f, ComicZoom.panLimit(1000f, 2f), 0f)
        assertEquals(500f, ComicZoom.clampPan(900f, 1000f, 2f), 0f)
        assertEquals(-500f, ComicZoom.clampPan(-900f, 1000f, 2f), 0f)
        assertEquals(0f, ComicZoom.clampPan(80f, 1000f, 1f), 0f)
    }

    @Test
    fun theReaderOpensComicsInsteadOfRefusingThem() {
        val loader = File("src/main/kotlin/com/brightblu/parc/reader/ReaderLoader.kt").readText()
        assertFalse(loader.contains("later version"))
        val screen = File("src/main/kotlin/com/brightblu/parc/reader/ReaderScreen.kt").readText()
        assertTrue(screen.contains("ComicReaderPage"))
        assertTrue(screen.contains("ReaderComicLoader.load"))
    }
}
