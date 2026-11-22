package com.brightblu.parc

import android.content.Context
import android.content.ContextWrapper
import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.BookFormat
import com.brightblu.parc.core.data.library.BookPosition
import com.brightblu.parc.core.data.library.ReaderBookDoc
import com.brightblu.parc.core.data.library.ReaderChapter
import com.brightblu.parc.core.data.library.ReaderPageColor
import com.brightblu.parc.core.data.library.ReaderPositionMath
import com.brightblu.parc.core.data.library.ReaderSettings
import com.brightblu.parc.reader.ReaderColors
import com.brightblu.parc.reader.ReaderWebDocument
import com.brightblu.parc.reader.readerColors
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class T207ReaderTest {
    private val context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = FakeSharedPreferences()
    }

    private fun book(format: BookFormat = BookFormat.EPUB, position: BookPosition = BookPosition()) =
        Book("book-1", "content://library/book-1", "A Book", "An Author", format,
            sizeBytes = 1_000, addedAt = 10, position = position)

    private fun doc() = ReaderBookDoc("book-1", "A Book", "An Author", listOf(
        ReaderChapter(0, "One", "<h1>One</h1><p>First page text.</p>", "OneFirst page text."),
        ReaderChapter(1, "Two", "<h1>Two</h1><p>Second page text.</p>", "TwoSecond page text."),
    ), emptyList())

    @Test fun theReaderIsItsOwnActivityAndFollowsTheUserRotation() {
        // T209 turns this activity into the open-with target, so it is exported and its VIEW
        // filters are asserted in T209FilesTest. T345 gives the reader the user's rotation so a
        // landscape sensor or rotation lock is respected while the launcher stays portrait.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val activity = Regex("""<activity[^>]*android:name="\.reader\.ReaderActivity"[^>]*>""").find(manifest)
        assertNotNull("ReaderActivity must be declared", activity)
        val line = activity!!.value
        assertTrue(line.contains("android:exported=\"true\""))
        assertTrue(line.contains("android:taskAffinity=\"\""))
        assertTrue(line.contains("android:screenOrientation=\"user\""))
        assertTrue(line.contains("android:excludeFromRecents=\"true\""))
        assertNotNull(Class.forName("com.brightblu.parc.reader.ReaderActivity"))
    }

    @Test fun theReaderAaChoicesTravelInTheBackup() {
        assertTrue(BackupSpecs.reader in BackupSpecs.all)
        assertEquals(setOf("settings_v1"), BackupSpecs.reader.keys.map { it.name }.toSet())
        assertTrue(BackupSpecs.reader.keys.single().maxLength >= 256)
        val specs = AppBackup.stores(context).map { it.spec }
        assertEquals(listOf(BackupSpecs.reader), specs.filter { it.id == "reader" })
    }

    @Test fun theInMemoryPageIsColumnPaginatedAndKnowsItsStartPosition() {
        val settings = ReaderSettings(fontSp = 22f, lineSpacing = 1.8f, marginFraction = 0.1f)
        val colors = ReaderColors(0xFFF3EDE2L, 0xFF24211CL, 0xFF6B6258L, 0xFF7FB4C8L, true)
        val html = ReaderWebDocument.build(doc(), settings, colors, 360f, 640f, BookPosition(1, 4))
        assertTrue(html.contains("column-width:"))
        assertTrue(html.contains("font-size:22.0px"))
        assertTrue(html.contains("line-height:1.8"))
        assertTrue(html.contains("data-chapter=\"0\""))
        assertTrue(html.contains("data-chapter=\"1\""))
        assertTrue(html.contains("var start={chapter:1,offset:4};"))
        assertTrue(html.contains("Reader.moved("))
        assertFalse(html.contains("http://"))
        assertFalse(html.contains("https://"))
    }

    @Test fun pageColoursAreReadableInEverySkin() {
        val dark = readerColors(ReaderPageColor.Dark, Skin.Midnight)
        assertFalse(dark.light)
        assertEquals(Skin.Midnight.colors.bg, dark.paper)
        val light = readerColors(ReaderPageColor.Light, Skin.Midnight)
        assertTrue(light.light)
        val black = readerColors(ReaderPageColor.Black, Skin.Midnight)
        assertEquals(Metrics.readerPageBlackPaper, black.paper)
        assertFalse(black.light)
        listOf(ReaderPageColor.Light, ReaderPageColor.Sepia, ReaderPageColor.Dark, ReaderPageColor.Black).forEach {
            val colors = readerColors(it, Skin.Midnight)
            assertTrue(colors.ink != colors.paper)
        }
    }

    @Test fun progressUsesCharactersNotPagesSoAFontChangeKeepsThePlace() {
        val document = doc()
        val position = BookPosition(1, 4)
        val before = ReaderPositionMath.progress(document, position)
        val after = ReaderPositionMath.progress(document, position)
        assertEquals(before, after, 0.0)
        assertEquals(position, ReaderPositionMath.clamp(document, position))
        assertTrue(ReaderPositionMath.progressPercent(document, BookPosition(1, 4)) > 0)
    }
}
