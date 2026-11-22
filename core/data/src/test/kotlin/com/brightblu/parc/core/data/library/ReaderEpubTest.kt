package com.brightblu.parc.core.data.library

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderEpubTest {
    private val files = mutableListOf<File>()

    @After fun clean() {
        files.forEach { it.delete() }
    }

    private fun zip(entries: Map<String, String>): ZipFile {
        val file = File.createTempFile("reader-test-", ".epub")
        files += file
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
        return ZipFile(file)
    }

    private fun container(opf: String = "OEBPS/content.opf") = """
        <?xml version="1.0"?>
        <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
          <rootfiles><rootfile full-path="$opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>
    """.trimIndent()

    private fun opf(spine: String, extraManifest: String = "", nav: String = "nav.xhtml") = """
        <?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>Test Book</dc:title><dc:creator>Test Author</dc:creator>
          </metadata>
          <manifest>
            <item id="nav" href="$nav" media-type="application/xhtml+xml" properties="nav"/>
            <item id="one" href="text/one.xhtml" media-type="application/xhtml+xml"/>
            <item id="two" href="text/two.xhtml" media-type="application/xhtml+xml"/>
            $extraManifest
          </manifest>
          <spine>$spine</spine>
        </package>
    """.trimIndent()

    private fun chapter(title: String, body: String) = """
        <?xml version="1.0" encoding="utf-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head>
        <body><h1>$title</h1>$body</body></html>
    """.trimIndent()

    @Test fun xhtmlDeclarationsAreLocalAndFindKeepsAccents() {
        val xhtml11 = """<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN"
            "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">"""
        val markup = chapter("Um capítulo", "<p>A manhã&nbsp;chegou.</p>").replace("<html", "$xhtml11\n<html")
        zip(mapOf("META-INF/container.xml" to container(), "OEBPS/content.opf" to opf("""<itemref idref="one"/>"""),
            "OEBPS/text/one.xhtml" to markup)).use {
            val doc = ReaderEpub.parse(it, "id", "", "")
            // Find must work without the accent as well as with it.
            for (query in listOf("MANHÃ", "manha")) {
                val hit = ReaderSearch.inDoc(doc, query).single()
                assertEquals("manhã", hit.match)
                assertEquals(doc.chapters.single().text.indexOf("manhã"), hit.offset)
            }
        }

        // The other neutral declarations a book may carry, including single quotes and HTML5.
        for (doctype in listOf(
            """<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.0 Strict//EN' 'http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd'>""",
            """<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">""",
            "<!doctype html>",
        )) {
            val root = ReaderHtml.parseXml(markup.replace(xhtml11, doctype).toByteArray(), xhtml = true)
            assertEquals("html", root.localName)
        }

        // An internal subset (entity expansion), a substituted host and a local file URL are all
        // refused before parsing, so nothing is fetched or expanded. A second declaration is refused
        // too, and a package document never gets the chapter allowance.
        for (blocked in listOf(
            xhtml11.replace("dtd\">", "dtd\" [<!ENTITY local 'text'>]>"),
            xhtml11.replace("www.w3.org", "example.invalid"),
            xhtml11.replace("http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd", "file:///etc/passwd"),
            "$xhtml11\n$xhtml11",
        )) {
            assertTrue(blocked,
                runCatching { ReaderHtml.parseXml(markup.replace(xhtml11, blocked).toByteArray(), xhtml = true) }.isFailure)
        }
        assertTrue(runCatching { ReaderHtml.parseXml(markup.toByteArray()) }.isFailure)
    }

    @Test fun failureKindsSeparateUnsupportedMarkupFromAnIncompleteArchive() {
        // The archive has no container: incomplete, not a markup problem.
        zip(mapOf("OEBPS/text/one.xhtml" to chapter("One", "<p>Body.</p>"))).use {
            val error = runCatching { ReaderEpub.parse(it, "id", "", "") }.exceptionOrNull()
                as? ReaderBookException
            assertEquals(ReaderBookProblem.BrokenArchive, error?.problem)
        }
        // The manifest lists a page the archive does not contain.
        zip(mapOf("META-INF/container.xml" to container(), "OEBPS/content.opf" to opf("""<itemref idref="one"/>"""))).use {
            val error = runCatching { ReaderEpub.parse(it, "id", "", "") }.exceptionOrNull()
                as? ReaderBookException
            assertEquals(ReaderBookProblem.BrokenArchive, error?.problem)
        }
        // A chapter that declares its own entity subset is refused before any expansion.
        val unsafe = chapter("One", "<p>Body.</p>")
            .replace("<html", """<!DOCTYPE html [<!ENTITY local "expanded">]>
<html""")
        zip(mapOf("META-INF/container.xml" to container(), "OEBPS/content.opf" to opf("""<itemref idref="one"/>"""),
            "OEBPS/text/one.xhtml" to unsafe)).use {
            val error = runCatching { ReaderEpub.parse(it, "id", "", "") }.exceptionOrNull()
                as? ReaderBookException
            assertEquals(ReaderBookProblem.UnsupportedMarkup, error?.problem)
        }
    }

    @Test fun spineOrderAndNavContentsAreRead() {
        val zip = zip(mapOf(
            "META-INF/container.xml" to container(),
            "OEBPS/content.opf" to opf("""<itemref idref="two"/><itemref idref="one"/>"""),
            "OEBPS/nav.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
                <nav epub:type="toc"><ol>
                  <li><a href="text/two.xhtml#start">Second</a></li>
                  <li><a href="text/one.xhtml">First</a></li>
                </ol></nav></body></html>
            """.trimIndent(),
            "OEBPS/text/two.xhtml" to chapter("Two", "<p id=\"start\">Second body.</p>"),
            "OEBPS/text/one.xhtml" to chapter("One", "<p>First body.</p>"),
        ))
        val doc = ReaderEpub.parse(zip, "id", "", "")
        zip.close()
        assertEquals("Test Book", doc.title)
        assertEquals("Test Author", doc.author)
        assertEquals(listOf("Two", "One"), doc.chapters.map { it.title })
        assertEquals(listOf("Second", "First"), doc.toc.map { it.title })
        assertEquals(0, doc.toc[0].chapter)
        assertTrue("anchor offset should follow the heading", doc.toc[0].offset > 0)
        assertEquals(1, doc.toc[1].chapter)
        assertTrue(doc.chapters[0].text.contains("Second body."))
    }

    @Test fun ncxContentsAreUsedWhenThereIsNoNav() {
        val zip = zip(mapOf(
            "META-INF/container.xml" to container("content.opf"),
            "content.opf" to """
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Old</dc:title></metadata>
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="one" href="one.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx"><itemref idref="one"/></spine>
                </package>
            """.trimIndent(),
            "toc.ncx" to """
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><navMap>
                  <navPoint id="n1"><navLabel><text>Chapter One</text></navLabel><content src="one.xhtml"/></navPoint>
                </navMap></ncx>
            """.trimIndent(),
            "one.xhtml" to chapter("One", "<p>Body.</p>"),
        ))
        val doc = ReaderEpub.parse(zip, "id", "", "")
        zip.close()
        assertEquals(listOf("Chapter One"), doc.toc.map { it.title })
        assertEquals(0, doc.toc.single().chapter)
    }

    @Test fun imagesBecomeDataAndRemoteResourcesAndScriptsAreDropped() {
        val zip = zip(mapOf(
            "META-INF/container.xml" to container("OEBPS/content.opf"),
            "OEBPS/content.opf" to opf("""<itemref idref="one"/><itemref idref="two"/>""",
                """<item id="pic" href="img/pic.png" media-type="image/png"/>"""),
            "OEBPS/nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><nav epub:type="toc"><ol><li><a href="text/one.xhtml">One</a></li></ol></nav></body></html>""",
            "OEBPS/text/one.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title>
                <script>alert(1)</script><style>body{color:red}</style></head><body>
                <p>Text <img src="../img/pic.png" alt="Picture"/></p>
                <p><img src="https://example.com/remote.png" alt="Remote"/></p>
                <p><a href="../text/two.xhtml#there">Jump</a></p>
                <a href="../text/two.xhtml">Second</a>
                </body></html>
            """.trimIndent(),
            "OEBPS/text/two.xhtml" to chapter("Two", "<p id=\"there\">Target.</p>"),
            "OEBPS/img/pic.png" to "PNG",
        ))
        val doc = ReaderEpub.parse(zip, "id", "", "")
        zip.close()
        assertEquals(2, doc.chapters.size)
        val html = doc.chapters[0].html
        assertTrue(html.contains("data:image/png;base64,"))
        assertFalse(html.contains("example.com"))
        assertFalse(html.contains("script"))
        assertFalse(html.contains("alert"))
        assertFalse(html.contains("color:red"))
        assertTrue(html, html.contains("parc-reader://chapter/1?offset="))
    }

    @Test fun finishLinksUsesTheTargetChapterOffset() {
        val first = ReaderChapter(0, "One", "<p><a data-parc-target=\"1|there\">Jump</a></p>", "Jump")
        val second = ReaderChapter(1, "Two", "<h1>Two</h1><p id=\"there\">Target.</p>", "TwoTarget.")
        val done = ReaderHtml.finishLinks(listOf(first, second)) { listOf(first, second)[it] }
        assertTrue(done[0].html, done[0].html.contains("parc-reader://chapter/1?offset="))
        assertFalse(done[0].html.contains("data-parc-target"))
    }

    @Test fun sanitizerResolvesAnInternalHrefWhenGivenTheSpine() {
        val xhtml = chapter("One", "<p><a href=\"../text/two.xhtml#there\">Jump</a></p>")
        val html = ReaderHtml.sanitize(xhtml.toByteArray(), "OEBPS/text/one.xhtml",
            mapOf("OEBPS/text/one.xhtml" to 0, "OEBPS/text/two.xhtml" to 1)) { null }
        assertTrue(html, html.contains("data-parc-target=\"1|there\""))
    }

    @Test fun pathsResolveForLinks() {
        assertEquals("OEBPS/text/two.xhtml", EpubMetadataParser.resolvePath("OEBPS/text/one.xhtml", "../text/two.xhtml#there"))
    }

    @Test fun aMissingContainerIsAPlainReaderError() {
        val zip = zip(mapOf("other.txt" to "nothing"))
        val error = runCatching { ReaderEpub.parse(zip, "id", "", "") }.exceptionOrNull()
        zip.close()
        assertTrue(error is ReaderBookException)
        assertTrue(error!!.message!!.contains("contents"))
    }

    @Test fun anEmptySpineIsAPlainReaderError() {
        val zip = zip(mapOf(
            "META-INF/container.xml" to container("content.opf"),
            "content.opf" to """<package xmlns="http://www.idpf.org/2007/opf"><manifest/><spine/></package>""",
        ))
        val error = runCatching { ReaderEpub.parse(zip, "id", "", "") }.exceptionOrNull()
        zip.close()
        assertTrue(error is ReaderBookException)
    }

    @Test fun missingNavFallsBackToSpineTitles() {
        val zip = zip(mapOf(
            "META-INF/container.xml" to container("content.opf"),
            "content.opf" to """
                <package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
                <manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="one"/></spine></package>
            """.trimIndent(),
            "one.xhtml" to chapter("Only Chapter", "<p>Body.</p>"),
        ))
        val doc = ReaderEpub.parse(zip, "id", "", "")
        zip.close()
        assertEquals(listOf("Only Chapter"), doc.toc.map { it.title })
    }
}
