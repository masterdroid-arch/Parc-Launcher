package com.brightblu.parc.core.data.library

import java.io.ByteArrayInputStream
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

data class EpubMetadata(val title: String?, val author: String?, val coverPath: String?)

/** Framework-free EPUB 2/3 package parsing. Paths name zip entries, never filesystem paths. */
object EpubMetadataParser {
    const val MaxXmlBytes = 2 * 1024 * 1024

    fun packagePath(container: ByteArray): String? = elements(parse(container), "rootfile")
        .firstOrNull { it.getAttribute("media-type") == "application/oebps-package+xml" }
        ?.getAttribute("full-path")?.let { resolvePath("", it) }

    fun parseOpf(opf: ByteArray, packagePath: String): EpubMetadata {
        val root = parse(opf)
        val metadata = elements(root, "metadata").firstOrNull()
        val title = metadata?.let { elements(it, "title").firstOrNull()?.textContent?.trim() }?.ifBlank { null }
        val authors = metadata?.let { elements(it, "creator").map { node -> node.textContent.trim() }
            .filter { it.isNotEmpty() }.joinToString(", ") }?.ifBlank { null }
        val items = elements(root, "manifest").firstOrNull()?.let { elements(it, "item") }.orEmpty()
        val coverId = metadata?.let { elements(it, "meta") }
            ?.firstOrNull { it.getAttribute("name").equals("cover", ignoreCase = true) }?.getAttribute("content")
        val cover = items.firstOrNull { "cover-image" in it.getAttribute("properties").split(Regex("\\s+")) }
            ?: items.firstOrNull { !coverId.isNullOrBlank() && it.getAttribute("id") == coverId }
        return EpubMetadata(title, authors, cover?.getAttribute("href")?.let { resolvePath(packagePath, it) })
    }

    fun resolvePath(base: String, href: String): String? {
        val raw = href.substringBefore('#').substringBefore('?')
        val decoded = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
        if (decoded.isBlank() || decoded.startsWith('/') || '\\' in decoded || ':' in decoded ||
            decoded.any { it.code < 32 }) return null
        val segments = ArrayDeque<String>()
        val parent = base.substringBeforeLast('/', "")
        for (part in (if (parent.isEmpty()) decoded else "$parent/$decoded").split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeLast()
                else -> segments.addLast(part)
            }
        }
        return segments.joinToString("/").ifEmpty { null }
    }

    private fun parse(bytes: ByteArray): Element {
        require(bytes.size <= MaxXmlBytes)
        val builder = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // Android and the JVM expose different parser features. The resolver below always
            // denies external entities; reject a document type before entity expansion as well.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }.newDocumentBuilder()
        // Removing NULs also catches UTF-16/32 declarations before parsing.
        require(!String(bytes, Charsets.ISO_8859_1).replace("\u0000", "").contains("<!DOCTYPE", true))
        builder.setEntityResolver { _, _ -> throw SAXException("External entities are not allowed") }
        builder.setErrorHandler(object : DefaultHandler() {
            override fun error(e: org.xml.sax.SAXParseException) { throw e }
            override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
        })
        return builder.parse(InputSource(ByteArrayInputStream(bytes))).documentElement
    }

    private fun elements(root: Element, localName: String): List<Element> {
        val nodes = root.getElementsByTagNameNS("*", localName)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }
}
