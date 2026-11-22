package com.brightblu.parc.core.data.library

import com.brightblu.parc.model.Metrics
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.w3c.dom.Element

/**
 * EPUB 2/3 spine, contents and cover reading for the reader (T207). The archive is only read; no
 * path is ever opened on the filesystem, and remote resources are never fetched. Pure except for
 * the [ZipFile] it is handed.
 */
object ReaderEpub {
    private const val MaxXmlBytes = 2 * 1024 * 1024
    private const val MaxChapterBytes = 4 * 1024 * 1024
    private const val MaxSpineItems = 2_000
    private const val MaxTocEntries = 2_000

    private data class Item(val id: String, val path: String, val mediaType: String, val properties: String)

    fun parse(zip: ZipFile, id: String, bookTitle: String, bookAuthor: String): ReaderBookDoc {
        val archive = Archive(zip)
        val container = archive.read("META-INF/container.xml", MaxXmlBytes)
            ?: throw ReaderBookException("This EPUB is missing its contents list.", problem = ReaderBookProblem.BrokenArchive)
        val opfPath = EpubMetadataParser.packagePath(container)
            ?: throw ReaderBookException("This EPUB is missing its package document.", problem = ReaderBookProblem.BrokenArchive)
        val opf = archive.read(opfPath, MaxXmlBytes)
            ?: throw ReaderBookException("This EPUB is missing its package document.", problem = ReaderBookProblem.BrokenArchive)
        val root = runCatching { ReaderHtml.parseXml(opf) }.getOrElse {
            throw ReaderBookException("This EPUB uses package markup the reader cannot open.", it, ReaderBookProblem.UnsupportedMarkup)
        }
        val metadata = runCatching { EpubMetadataParser.parseOpf(opf, opfPath) }.getOrNull()
        val title = metadata?.title?.takeIf { it.isNotBlank() } ?: bookTitle
        val author = metadata?.author?.takeIf { it.isNotBlank() } ?: bookAuthor

        val items = ReaderHtml.elements(root, "manifest").firstOrNull()
            ?.let { ReaderHtml.elements(it, "item") }.orEmpty()
            .mapNotNull { element ->
                val itemId = element.getAttribute("id")
                val href = element.getAttribute("href")
                val path = EpubMetadataParser.resolvePath(opfPath, href) ?: return@mapNotNull null
                Item(itemId, path, element.getAttribute("media-type"), element.getAttribute("properties"))
            }
        val byId = items.associateBy { it.id }
        val spine = ReaderHtml.elements(root, "spine").firstOrNull()
            ?: throw ReaderBookException("This EPUB has no reading order.", problem = ReaderBookProblem.BrokenArchive)
        val spineIds = ReaderHtml.elements(spine, "itemref").mapNotNull { it.getAttribute("idref").takeIf { id -> id.isNotBlank() } }
        val spineItems = spineIds.mapNotNull { byId[it] }
            .filter { it.mediaType in setOf("application/xhtml+xml", "text/html") || it.path.endsWith(".xhtml", true) || it.path.endsWith(".html", true) }
            .distinctBy { it.path }
            .take(MaxSpineItems)
        if (spineItems.isEmpty()) throw ReaderBookException("This EPUB has no readable pages.", problem = ReaderBookProblem.BrokenArchive)
        val pathToChapter = spineItems.mapIndexed { index, item -> item.path to index }.toMap()

        val chapters = spineItems.mapIndexed { index, item ->
            val bytes = archive.read(item.path, MaxChapterBytes)
                ?: throw ReaderBookException("This EPUB is missing a page.", problem = ReaderBookProblem.BrokenArchive)
            val html = runCatching {
                ReaderHtml.sanitize(bytes, item.path, pathToChapter) { path -> archive.read(path, Metrics.readerMaxImageBytes) }
            }.getOrElse {
                throw ReaderBookException("This EPUB uses chapter markup the reader cannot open.", it, ReaderBookProblem.UnsupportedMarkup)
            }
            val tocTitle = chapterTitle(html, bytes, item.path)
            ReaderChapter(index, tocTitle, html, ReaderHtml.text(html))
        }

        if (chapters.sumOf { it.html.length } > Metrics.readerMaxBookHtmlChars) {
            throw ReaderBookException("This book is too large to open on this phone.")
        }
        val finalChapters = ReaderHtml.finishLinks(chapters) { index -> chapters[index] }
        val toc = readToc(root, items, byId, spine, opfPath, archive, pathToChapter, finalChapters)
        val usableToc = toc.ifEmpty {
            finalChapters.map { ReaderTocEntry(it.title, it.index, 0) }
        }.take(MaxTocEntries)
        return ReaderBookDoc(id, title, author, finalChapters, usableToc)
    }

    private fun chapterTitle(html: String, xhtml: ByteArray, path: String): String {
        val heading = Regex("""<h[1-3][^>]*>(.*?)</h[1-3]>""", RegexOption.IGNORE_CASE).find(html)
            ?.groupValues?.get(1)?.let { ReaderHtml.text(it).trim() }
            ?.takeIf { it.isNotEmpty() }
        if (heading != null) return heading.take(120)
        val documentTitle = runCatching {
            val root = ReaderHtml.parseXml(xhtml, xhtml = true)
            ReaderHtml.elements(root, "title").firstOrNull()?.textContent?.trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        return documentTitle?.take(120)
            ?: path.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Chapter" }
    }

    private fun readToc(root: Element, items: List<Item>, byId: Map<String, Item>, spine: Element,
        opfPath: String, archive: Archive, pathToChapter: Map<String, Int>,
        chapters: List<ReaderChapter>): List<ReaderTocEntry> {
        val navItem = items.firstOrNull { item -> item.properties.split(Regex("\\s+")).any { it == "nav" } }
        if (navItem != null) {
            val bytes = archive.read(navItem.path, MaxXmlBytes)
            val entries = bytes?.let { runCatching { parseNav(it, navItem.path, pathToChapter, chapters) }.getOrNull() }.orEmpty()
            if (entries.isNotEmpty()) return entries
        }
        val ncxItem = items.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            ?: byId[spine.getAttribute("toc")]
        if (ncxItem != null) {
            val bytes = archive.read(ncxItem.path, MaxXmlBytes)
            val entries = bytes?.let { runCatching { parseNcx(it, ncxItem.path, pathToChapter, chapters) }.getOrNull() }.orEmpty()
            if (entries.isNotEmpty()) return entries
        }
        return emptyList()
    }

    private fun parseNav(bytes: ByteArray, navPath: String, pathToChapter: Map<String, Int>,
        chapters: List<ReaderChapter>): List<ReaderTocEntry> {
        val root = ReaderHtml.parseXml(bytes, xhtml = true)
        val navs = ReaderHtml.elements(root, "nav")
        val tocNav = navs.firstOrNull { nav ->
            val type = nav.getAttribute("epub:type").ifBlank { nav.getAttribute("type") }
            type.split(Regex("\\s+")).any { it == "toc" }
        } ?: navs.firstOrNull()
        if (tocNav == null) return emptyList()
        return ReaderHtml.elements(tocNav, "a").mapNotNull { anchor ->
            val href = anchor.getAttribute("href").trim()
            val path = EpubMetadataParser.resolvePath(navPath, href) ?: return@mapNotNull null
            val chapter = pathToChapter[path] ?: return@mapNotNull null
            val title = anchor.textContent.trim().ifBlank { chapters[chapter].title }.take(160)
            val fragment = href.substringAfter('#', "").ifBlank { null }
            ReaderTocEntry(title, chapter, ReaderHtml.fragmentOffset(chapters[chapter].html, fragment))
        }.distinctBy { it.chapter to it.offset }
    }

    private fun parseNcx(bytes: ByteArray, ncxPath: String, pathToChapter: Map<String, Int>,
        chapters: List<ReaderChapter>): List<ReaderTocEntry> {
        val root = ReaderHtml.parseXml(bytes)
        return ReaderHtml.elements(root, "navPoint").mapNotNull { point ->
            val label = ReaderHtml.elements(point, "text").firstOrNull()?.textContent?.trim().orEmpty()
            val src = ReaderHtml.elements(point, "content").firstOrNull()?.getAttribute("src")?.trim().orEmpty()
            if (src.isEmpty()) return@mapNotNull null
            val path = EpubMetadataParser.resolvePath(ncxPath, src) ?: return@mapNotNull null
            val chapter = pathToChapter[path] ?: return@mapNotNull null
            val fragment = src.substringAfter('#', "").ifBlank { null }
            ReaderTocEntry(label.ifBlank { chapters[chapter].title }.take(160), chapter,
                ReaderHtml.fragmentOffset(chapters[chapter].html, fragment))
        }.distinctBy { it.chapter to it.offset }
    }

    private class Archive(private val zip: ZipFile) {
        private val entries: Map<String, ZipEntry> = buildMap {
            for (entry in zip.entries()) {
                val name = entry.name.replace('\\', '/').trimStart('/')
                if (!entry.isDirectory && name.isNotEmpty()) put(name, entry)
            }
        }

        fun read(path: String, maxBytes: Int): ByteArray? {
            val entry = entries[path] ?: return null
            if (entry.size > maxBytes) return null
            return runCatching { zip.getInputStream(entry).use { readCapped(it, maxBytes) } }.getOrNull()
        }

        private fun readCapped(input: InputStream, maxBytes: Int): ByteArray {
            val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > maxBytes) throw ReaderBookException("This book is too large to open.")
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}
