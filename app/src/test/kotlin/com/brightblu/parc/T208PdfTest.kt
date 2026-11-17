package com.brightblu.parc

import com.brightblu.parc.reader.ReaderPdfMessages
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class T208PdfTest {
    @Test fun pdfErrorsArePlainAndDistinct() {
        val messages = listOf(
            ReaderPdfMessages.Password,
            ReaderPdfMessages.Damaged,
            ReaderPdfMessages.Missing,
            ReaderPdfMessages.Empty,
            ReaderPdfMessages.TooLarge,
        )
        messages.forEach { message ->
            assertTrue(message.isNotBlank())
            assertFalse(message.contains("!"))
        }
        assertEquals(messages.size, messages.distinct().size)
        assertTrue(ReaderPdfMessages.Password.contains("password-protected"))
        assertTrue(ReaderPdfMessages.Damaged.contains("damaged"))
    }

    @Test fun thePdfReaderUsesThePlatformRendererAndReleasesIt() {
        val source = File("src/main/kotlin/com/brightblu/parc/reader/ReaderPdf.kt").readText()
        assertTrue(source.contains("PdfRenderer"))
        assertTrue(source.contains("PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY"))
        assertTrue(source.contains("fun close()"))
        assertTrue(source.contains("temporary?.delete()"))
        assertTrue(source.contains("ReaderPdfMath.visiblePages"))
        assertTrue(source.contains("pdfInvert"))
        assertFalse(source.contains("import com.tom_roush"))
        assertFalse(source.contains("import com.artifex"))
    }
}
