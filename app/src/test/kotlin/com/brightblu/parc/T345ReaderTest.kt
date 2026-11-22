package com.brightblu.parc

import com.brightblu.parc.core.data.library.BookPosition
import com.brightblu.parc.core.data.library.ReaderBookDoc
import com.brightblu.parc.core.data.library.ReaderChapter
import com.brightblu.parc.core.data.library.ReaderFamily
import com.brightblu.parc.core.data.library.ReaderImageTone
import com.brightblu.parc.core.data.library.ReaderPageColor
import com.brightblu.parc.core.data.library.ReaderSettings
import com.brightblu.parc.core.data.library.ReaderSettingsJson
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.reader.ReaderWebDocument
import com.brightblu.parc.reader.pdfColorMatrix
import com.brightblu.parc.reader.readerBodyWeight
import com.brightblu.parc.reader.readerColors
import com.brightblu.parc.reader.readerFontFamilyCss
import com.brightblu.parc.reader.readerHeadingWeight
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T345: paired pure-paper pages, heavier text, image tone, font choice, keep-awake and continuous text. */
class T345ReaderTest {
    private fun doc(): ReaderBookDoc = ReaderBookDoc(
        "book", "A Book", "A Writer",
        listOf(
            ReaderChapter(0, "One", "<h1>One</h1><p>hello <a href=\"#\">link</a></p>", "hello link"),
            ReaderChapter(1, "Two", "<h1>Two</h1><p>second</p>", "second"),
        ),
        emptyList(),
    )

    private fun build(settings: ReaderSettings): String = ReaderWebDocument.build(
        doc(), settings, readerColors(settings, Skin.Midnight), 360f, 640f, BookPosition(0, 0), 3,
    )

    @Test fun purePaperModesUseExactBlackAndWhite() {
        val white = readerColors(ReaderPageColor.BlackOnWhite, Skin.Midnight)
        assertEquals(Metrics.readerPageBlackOnWhitePaper, white.paper)
        assertEquals(Metrics.readerPageBlackOnWhiteInk, white.ink)
        assertEquals(0xFFFFFFFFL, white.paper)
        assertEquals(0xFF000000L, white.ink)
        assertTrue(white.light)

        val black = readerColors(ReaderPageColor.WhiteOnBlack, Skin.Midnight)
        assertEquals(0xFF000000L, black.paper)
        assertEquals(0xFFFFFFFFL, black.ink)
        assertFalse(black.light)

        // The four existing themes keep their exact values.
        assertEquals(Metrics.readerPageLightPaper, readerColors(ReaderPageColor.Light, Skin.Midnight).paper)
        assertEquals(Metrics.readerPageSepiaPaper, readerColors(ReaderPageColor.Sepia, Skin.Midnight).paper)
        assertEquals(Metrics.readerPageBlackInk, readerColors(ReaderPageColor.Black, Skin.Midnight).ink)
    }

    @Test fun purePaperClearsNightWhileOtherColoursKeepIt() {
        val night = ReaderSettings(night = true, pageColor = ReaderPageColor.Dark)
        val white = night.withPageColor(ReaderPageColor.BlackOnWhite)
        assertEquals(ReaderPageColor.BlackOnWhite, white.pageColor)
        assertFalse(white.night)

        val black = night.withPageColor(ReaderPageColor.WhiteOnBlack)
        assertFalse(black.night)

        val sepia = night.withPageColor(ReaderPageColor.Sepia)
        assertEquals(ReaderPageColor.Sepia, sepia.pageColor)
        assertTrue(sepia.night)
    }

    @Test fun newPreferencesRoundTripAndOldDocumentsKeepDefaults() {
        val custom = ReaderSettings(
            family = ReaderFamily.Sans,
            imageTone = ReaderImageTone.Greyscale,
            heavyText = true,
            keepAwake = true,
            continuous = true,
        )
        assertEquals(custom, ReaderSettingsJson.decode(ReaderSettingsJson.encode(custom)))
        assertTrue(ReaderSettingsJson.encode(custom).length <= ReaderSettingsJson.MaxChars)

        val old = ReaderSettingsJson.decode("""{"fontSp":20,"family":"Sans"}""")
        assertEquals(ReaderFamily.Sans, old.family)
        assertEquals(ReaderImageTone.Colour, old.imageTone)
        assertFalse(old.heavyText)
        assertFalse(old.keepAwake)
        assertFalse(old.continuous)
    }

    @Test fun fontHeavyAndImageChoicesReachTheDocumentCss() {
        assertEquals("serif", readerFontFamilyCss(ReaderFamily.Serif))
        assertEquals("sans-serif", readerFontFamilyCss(ReaderFamily.Sans))
        assertEquals("'ParcFigtree'", readerFontFamilyCss(ReaderFamily.Figtree))
        assertEquals(400, readerBodyWeight(false))
        assertEquals(500, readerBodyWeight(true))
        assertEquals(600, readerHeadingWeight(false))
        assertEquals(800, readerHeadingWeight(true))

        val heavy = build(ReaderSettings(family = ReaderFamily.Sans, heavyText = true,
            imageTone = ReaderImageTone.Greyscale))
        assertTrue(heavy.contains("font-family:sans-serif"))
        assertTrue(heavy.contains("font-weight:500"))
        assertTrue(heavy.contains("font-weight:800"))
        assertTrue(heavy.contains("filter:grayscale(1)"))

        val plain = build(ReaderSettings())
        assertTrue(plain.contains("font-family:serif"))
        assertTrue(plain.contains("font-weight:400"))
        assertFalse(plain.contains("font-weight:500"))
        assertFalse(plain.contains("filter:grayscale"))
    }

    @Test fun continuousReadingUsesVerticalFlowWhilePagedKeepsColumns() {
        val continuous = build(ReaderSettings(continuous = true))
        assertTrue(continuous.contains("overflow-y:auto"))
        assertTrue(continuous.contains("var CONT=true"))
        assertTrue(continuous.contains("vscroll:function(dy)"))
        assertTrue(continuous.contains("function contShow(ch,off)"))
        assertFalse(continuous.contains("column-width"))

        val paged = build(ReaderSettings())
        assertTrue(paged.contains("column-width"))
        assertTrue(paged.contains("var CONT=false"))
        assertTrue(paged.contains("overflow:hidden"))
        // Standalone JS regression harness input; no Android WebView is claimed here.
        File("build/t345-reader-continuous.html").apply { parentFile.mkdirs(); writeText(continuous) }
        File("build/t345-reader-paged.html").apply { parentFile.mkdirs(); writeText(paged) }
    }

    @Test fun pdfToneMatrixIsLuminanceThenInversion() {
        assertNull(pdfColorMatrix(grey = false, invert = false))

        val grey = pdfColorMatrix(grey = true, invert = false)!!
        assertEquals(0.2126f, grey[0], 0.0001f)
        assertEquals(0.7152f, grey[1], 0.0001f)
        assertEquals(0.0722f, grey[2], 0.0001f)
        assertEquals(0f, grey[4], 0.0001f)
        assertEquals(grey[0], grey[5], 0.0001f)
        assertEquals(grey[2], grey[12], 0.0001f)
        assertEquals(1f, grey[18], 0f)

        val both = pdfColorMatrix(grey = true, invert = true)!!
        assertEquals(-0.2126f, both[0], 0.0001f)
        assertEquals(255f, both[4], 0f)

        val invertOnly = pdfColorMatrix(grey = false, invert = true)!!
        assertEquals(-1f, invertOnly[0], 0f)
        assertEquals(255f, invertOnly[4], 0f)
        assertEquals(1f, invertOnly[18], 0f)
    }

    @Test fun readerActivityKeepsTheScreenAwakeOnlyWhileOpen() {
        val activity = File("src/main/kotlin/com/brightblu/parc/reader/ReaderActivity.kt").readText()
        assertTrue(activity.contains("FLAG_KEEP_SCREEN_ON"))
        assertTrue(activity.contains("fun setKeepAwake(on: Boolean)"))
        val screen = File("src/main/kotlin/com/brightblu/parc/reader/ReaderScreen.kt").readText()
        assertTrue(screen.contains("setKeepAwake(settings.keepAwake)"))
        assertFalse(File("src/main/AndroidManifest.xml").readText()
            .contains("android.permission.INTERNET"))
    }

    @Test fun theReadingSheetOffersBothPurePapersAndALiveSample() {
        val screen = File("src/main/kotlin/com/brightblu/parc/reader/ReaderScreen.kt").readText()
        assertTrue(screen.contains("ReaderPageColor.BlackOnWhite to \"Black on white\""))
        assertTrue(screen.contains("ReaderPageColor.WhiteOnBlack to \"White on black\""))
        assertTrue(screen.contains("ReaderToneSample"))
        assertTrue(screen.contains("it.withPageColor(page)"))
        assertTrue(screen.contains("ReaderChoiceRow(\"Typeface\""))
        assertTrue(screen.contains("ReaderChoiceRow(\"Heavier text\""))
        assertTrue(screen.contains("ReaderChoiceRow(\"Keep screen awake\""))
        assertTrue(screen.contains("ReaderChoiceRow(\"Reading mode\""))
        assertTrue(screen.contains("ReaderImageTone.Greyscale"))
        assertTrue(File("src/main/kotlin/com/brightblu/parc/reader/ReaderPdf.kt").readText()
            .contains("PdfPagePainter(settings.imageTone == ReaderImageTone.Greyscale, settings.pdfInvert)"))
    }
}
