package com.brightblu.parc.core.data.library

import org.junit.Assert.*
import org.junit.Test

class EpubMetadataTest {
    private fun opf(body: String) = """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">$body</package>""".toByteArray()

    @Test fun epub2CoverIdAndNamespacedMetadata() {
        val parsed = EpubMetadataParser.parseOpf(opf("""
            <metadata><dc:title> A &amp; B </dc:title><dc:creator>Writer One</dc:creator>
            <dc:creator>Writer Two</dc:creator><meta name="cover" content="front"/></metadata>
            <manifest><item id="front" href="Images/front.jpg" media-type="image/jpeg"/></manifest>
        """), "OPS/book.opf")
        assertEquals(EpubMetadata("A & B", "Writer One, Writer Two", "OPS/Images/front.jpg"), parsed)
    }

    @Test fun epub3CoverPropertyAndOddPaths() {
        val parsed = EpubMetadataParser.parseOpf(opf("""
            <metadata><dc:title>Book</dc:title></metadata><manifest>
            <item id="c" properties="scripted cover-image" href="../Art/./My%20Cover+1.png#image"/>
            </manifest>
        """), "OPS/Text/package.opf")
        assertEquals("OPS/Art/My Cover+1.png", parsed.coverPath)
        assertNull(parsed.author)
    }

    @Test fun missingCoverIsNotMistakenForTheFirstImage() {
        assertNull(EpubMetadataParser.parseOpf(opf("""
            <metadata/><manifest><item id="x" href="image.png" media-type="image/png"/></manifest>
        """), "book.opf").coverPath)
        assertEquals(EpubMetadata(null, null, null), EpubMetadataParser.parseOpf(opf(""), "book.opf"))
    }

    @Test fun rootfileIsReadFromContainer() {
        val container = """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles>
            <rootfile full-path="ignore.xml" media-type="other"/>
            <rootfile full-path="OPS/My%20Book.opf" media-type="application/oebps-package+xml"/>
            </rootfiles></container>""".toByteArray()
        assertEquals("OPS/My Book.opf", EpubMetadataParser.packagePath(container))
    }

    @Test fun unsafePathsNeverLeaveTheArchive() {
        for (path in listOf("../../outside", "/absolute", "https://example.com/c.png", "//host/c",
            "..%2f..%2ffile", "a\\b", "bad%", "a%00b")) {
            assertNull(path, EpubMetadataParser.resolvePath("OPS/book.opf", path))
        }
        assertEquals("cover.png", EpubMetadataParser.resolvePath("OPS/book.opf", "../cover.png"))
    }

    @Test fun malformedAndExternalEntityDocumentsAreRejected() {
        val hostile = """<!DOCTYPE package [<!ENTITY x SYSTEM "file:///never-read">]><package>&x;</package>"""
        for (bytes in listOf("<package>".toByteArray(), hostile.toByteArray(), hostile.toByteArray(Charsets.UTF_16),
            ByteArray(EpubMetadataParser.MaxXmlBytes + 1))) {
            assertTrue(runCatching { EpubMetadataParser.parseOpf(bytes, "x.opf") }.isFailure)
        }
    }

    @Test fun boundedEntriesRejectOversizeEvenWithUnknownZipSize() {
        assertArrayEquals(byteArrayOf(1, 2), byteArrayOf(1, 2).inputStream().boundedBytes(2))
        assertTrue(runCatching { byteArrayOf(1, 2, 3).inputStream().boundedBytes(2) }.isFailure)
    }

    @Test fun pdfTitleResolvesInfoAndEscapedStrings() {
        val pdf = """%PDF-1.7
            1 0 obj << /Title (Wrong title) >> endobj
            5 0 obj << /Title (A \(small\) book\040two) >> endobj
            trailer << /Info 5 0 R >>
        """.toByteArray()
        assertEquals("A (small) book two", PdfMetadata.title(pdf))
        assertEquals("AB", PdfMetadata.title("2 0 obj << /Title <FEFF00410042> >> endobj /Info 2 0 R".toByteArray()))
        assertNull(PdfMetadata.title("not a document".toByteArray()))
    }
}
