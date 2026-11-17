package com.brightblu.parc.core.data.library

import com.brightblu.parc.model.Metrics
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTextTest {
    private fun textDoc(text: String) = ReaderBookDoc.plainText("id", "Book", "Author", text)

    @Test fun utf8IsReadAndABomIsDropped() {
        val plain = "Hello, café.".toByteArray(Charsets.UTF_8)
        assertEquals("Hello, café.", ReaderText.decode(plain))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + plain
        assertEquals("Hello, café.", ReaderText.decode(bom))
        assertTrue(ReaderText.isStrictUtf8(plain))
    }

    @Test fun malformedUtf8FallsBackToWindows1252() {
        val bytes = byteArrayOf('C'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte())
        assertFalse(ReaderText.isStrictUtf8(bytes))
        assertEquals("Café", ReaderText.decode(bytes))
    }

    @Test fun utf16BomsAreHonoured() {
        val text = "Café"
        assertEquals(text, ReaderText.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, ReaderText.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)))
    }

    @Test fun emptyTextIsAnEmptyChapter() {
        val doc = textDoc("")
        assertEquals(0, doc.totalChars)
        assertEquals(0.0, ReaderPositionMath.progress(doc, BookPosition(0, 0)), 0.0001)
        assertEquals("", doc.chapters.single().text)
    }

    @Test fun plainTextKeepsItsExactCharactersForOffsets() {
        val text = "First line\nSecond <line> & more"
        val doc = textDoc(text)
        assertEquals(text, doc.chapters.single().text)
        assertTrue(doc.chapters.single().html.contains("&lt;line&gt;"))
        assertTrue(doc.chapters.single().html.startsWith("<pre>"))
        assertEquals(text.length, doc.totalChars)
    }

    @Test fun positionClampsAndProgressCountsChapters() {
        val doc = ReaderBookDoc("id", "Book", "",
            listOf(
                ReaderChapter(0, "One", "One", "abcdefghij"),
                ReaderChapter(1, "Two", "Two", "abcdefghijklmnopqrst"),
            ), emptyList())
        assertEquals(10, ReaderPositionMath.clamp(doc, 0, 99).characterOffset)
        assertEquals(1, ReaderPositionMath.clamp(doc, 9, 0).chapter)
        assertEquals(0.0, ReaderPositionMath.progress(doc, BookPosition(0, 0)), 0.0001)
        assertEquals(10.0 / 30.0, ReaderPositionMath.progress(doc, BookPosition(0, 10)), 0.0001)
        assertEquals(1.0, ReaderPositionMath.progress(doc, BookPosition(1, 20)), 0.0001)
        assertEquals(BookPosition(1, 5), ReaderPositionMath.positionAt(doc, 0.5))
        assertTrue(ReaderPositionMath.same(BookPosition(1, 5), BookPosition(1, 6)))
        assertFalse(ReaderPositionMath.same(BookPosition(1, 5), BookPosition(1, 99)))
    }

    @Test fun aFontSizeChangeDoesNotChangeTheStoredOffset() {
        val doc = textDoc((1..1_000).joinToString(" ") { "word$it" })
        val position = BookPosition(0, 2_345)
        val small = ReaderSettings(fontSp = 15f)
        val large = ReaderSettings(fontSp = 30f)
        assertEquals(position, ReaderPositionMath.clamp(doc, position))
        assertEquals(ReaderPositionMath.progress(doc, position), ReaderPositionMath.progress(doc, position), 0.0)
        assertTrue(small.fontSp < large.fontSp)
        assertTrue(ReaderPositionMath.progress(doc, position) > 0.0)
    }

    @Test fun readerSettingsRoundTripAndClampGarbage() {
        val custom = ReaderSettings(22f, 1.85f, 0.11f, ReaderFamily.Sans, ReaderPageColor.Dark)
        assertEquals(custom, ReaderSettingsJson.decode(ReaderSettingsJson.encode(custom)))
        assertEquals(ReaderSettings(), ReaderSettingsJson.decode(null))
        assertEquals(ReaderSettings(), ReaderSettingsJson.decode(""))
        assertEquals(ReaderSettings(), ReaderSettingsJson.decode("not json"))
        val clamped = ReaderSettingsJson.decode("""{"fontSp":999,"lineSpacing":0.1,"marginFraction":9,"family":"Sans","pageColor":"Sepia"}""")
        assertEquals(30f, clamped.fontSp, 0.0f)
        assertEquals(1.2f, clamped.lineSpacing, 0.0f)
        assertEquals(0.14f, clamped.marginFraction, 0.0f)
        assertEquals(ReaderFamily.Sans, clamped.family)
        assertEquals(ReaderPageColor.Sepia, clamped.pageColor)
        assertTrue(ReaderSettings().largerText().fontSp > ReaderSettings().fontSp)
        assertTrue(ReaderSettings().widerMargins().marginFraction > ReaderSettings().marginFraction)
        var spacing = ReaderSettings(lineSpacing = Metrics.readerMaxLineSpacing)
        repeat(4) { spacing = spacing.moreLineSpacing() }
        assertEquals(Metrics.readerMaxLineSpacing, spacing.lineSpacing, 0.0f)
        var margins = ReaderSettings(marginFraction = Metrics.readerMaxMarginFraction)
        repeat(4) { margins = margins.widerMargins() }
        assertEquals(Metrics.readerMaxMarginFraction, margins.marginFraction, 0.0f)
        assertTrue(ReaderSettingsJson.encode(custom).length <= ReaderSettingsJson.MaxChars)
        assertEquals("settings_v1", ReaderSettingsStore.KeyName)
    }
}
