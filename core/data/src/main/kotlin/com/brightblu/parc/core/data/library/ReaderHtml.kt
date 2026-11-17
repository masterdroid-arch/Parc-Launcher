package com.brightblu.parc.core.data.library

import com.brightblu.parc.model.Metrics
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/**
 * Framework-free HTML cleanup for the reader. Everything is emitted into one in-memory document:
 * no base URL, no remote src or href, no script, no style and no form. Images become data URIs.
 * Internal links become a `parc-reader://` link the activity intercepts.
 */
object ReaderHtml {
    private val blockTags = setOf(
        "p", "div", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "ul", "ol", "li",
        "dl", "dt", "dd", "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption", "pre",
        "figure", "figcaption", "section", "article", "aside", "header", "footer", "nav",
    )
    private val inlineTags = setOf("em", "strong", "i", "b", "u", "s", "span", "sup", "sub", "small", "code")
    private val droppedTags = setOf(
        "script", "style", "iframe", "object", "embed", "form", "input", "button", "select", "textarea",
        "video", "audio", "canvas", "svg", "math", "link", "meta", "base", "noscript",
    )
    private val voidTags = setOf("br", "hr", "img")

    fun escape(value: String): String = buildString(value.length) {
        for (c in value) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    /** Plain text exactly as a browser's textContent would read the sanitised fragment. */
    fun text(html: String): String = buildString(html.length) {
        var i = 0
        while (i < html.length) {
            val c = html[i]
            if (c == '<') {
                i++
                while (i < html.length && html[i] != '>') i++
                i++
            } else if (c == '&') {
                val end = html.indexOf(';', i + 1)
                if (end < 0) { append(c); i++ } else {
                    val entity = html.substring(i + 1, end)
                    append(decodeEntity(entity))
                    i = end + 1
                }
            } else {
                append(c)
                i++
            }
        }
    }

    private fun decodeEntity(entity: String): String = when {
        entity == "amp" -> "&"
        entity == "lt" -> "<"
        entity == "gt" -> ">"
        entity == "quot" -> "\""
        entity == "apos" || entity == "#39" -> "'"
        entity == "nbsp" -> "\u00A0"
        entity.startsWith("#x") || entity.startsWith("#X") -> entity.drop(2).toIntOrNull(16)
            ?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: "&$entity;"
        entity.startsWith("#") -> entity.drop(1).toIntOrNull()
            ?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: "&$entity;"
        else -> "&$entity;"
    }

    /** Character offset of an element id inside a sanitised chapter, for a contents or footnote link. */
    fun fragmentOffset(html: String, fragment: String?): Int {
        if (fragment.isNullOrBlank()) return 0
        val safe = fragment.filter { it.isLetterOrDigit() || it in "_.:-" }
        if (safe.isEmpty()) return 0
        val match = Regex("""\bid\s*=\s*["']${Regex.escape(safe)}["']""").find(html) ?: return 0
        return text(html.substring(0, match.range.first)).length
    }

    /**
     * Converts one XHTML spine item into a safe fragment. [resource] reads a zip path; [spine]
     * maps a zip path to the reader chapter index. Internal links are left as a small marker and
     * finalised by [finishLinks] once every chapter's text is known.
     */
    fun sanitize(
        xhtml: ByteArray,
        basePath: String,
        spine: Map<String, Int>,
        resource: (String) -> ByteArray?,
    ): String {
        val root = parseXml(xhtml)
        val body = elements(root, "body").firstOrNull() ?: root
        val out = StringBuilder()
        val images = ImageBudget()
        for (child in children(body)) write(child, out, basePath, spine, resource, images)
        return out.toString()
    }

    /** Replaces internal-link markers with the concrete character offset in the target chapter. */
    fun finishLinks(chapters: List<ReaderChapter>, target: (Int) -> ReaderChapter): List<ReaderChapter> {
        val marker = Regex("""data-parc-target="(\d+)\|([^"]*)"""")
        return chapters.map { chapter ->
            val html = marker.replace(chapter.html) { match ->
                val targetIndex = match.groupValues[1].toIntOrNull() ?: return@replace ""
                val fragment = runCatching {
                    URLDecoder.decode(match.groupValues[2], "UTF-8")
                }.getOrDefault("")
                val offset = fragmentOffset(target(targetIndex).html, fragment)
                "href=\"parc-reader://chapter/$targetIndex?offset=$offset\""
            }
            chapter.copy(html = html)
        }
    }

    private fun write(node: Node, out: StringBuilder, basePath: String, spine: Map<String, Int>,
        resource: (String) -> ByteArray?, images: ImageBudget) {
        when (node.nodeType) {
            Node.TEXT_NODE -> out.append(escape(node.nodeValue.orEmpty()))
            Node.CDATA_SECTION_NODE -> out.append(escape(node.nodeValue.orEmpty()))
            Node.ELEMENT_NODE -> {
                val element = node as Element
                val tag = localName(element).lowercase()
                if (tag in droppedTags) return
                if (tag == "img") {
                    val html = image(element, basePath, resource, images)
                    if (html != null) out.append(html)
                    return
                }
                if (tag == "a") {
                    anchor(element, out, basePath, spine, images, resource)
                    return
                }
                if (tag !in blockTags && tag !in inlineTags) {
                    // Unknown markup is unwrapped rather than trusted.
                    for (child in children(element)) write(child, out, basePath, spine, resource, images)
                    return
                }
                out.append('<').append(tag)
                val id = element.getAttribute("id")
                if (id.isNotBlank()) out.append(" id=\"").append(escape(safeId(id))).append('"')
                if (tag == "td" || tag == "th") {
                    val colspan = element.getAttribute("colspan").filter { it.isDigit() }
                    val rowspan = element.getAttribute("rowspan").filter { it.isDigit() }
                    if (colspan.isNotEmpty()) out.append(" colspan=\"").append(colspan).append('"')
                    if (rowspan.isNotEmpty()) out.append(" rowspan=\"").append(rowspan).append('"')
                }
                out.append('>')
                if (tag !in voidTags) {
                    for (child in children(element)) write(child, out, basePath, spine, resource, images)
                    out.append("</").append(tag).append('>')
                }
            }
        }
    }

    private fun anchor(element: Element, out: StringBuilder, basePath: String, spine: Map<String, Int>,
        images: ImageBudget, resource: (String) -> ByteArray?) {
        val href = element.getAttribute("href").trim()
        val resolved = resolveHref(basePath, href, spine)
        out.append("<a")
        if (resolved != null) {
            val (chapter, fragment) = resolved
            out.append(" data-parc-target=\"").append(chapter).append('|')
                .append(escape(URLEncoder.encode(fragment.orEmpty(), "UTF-8"))).append('"')
        }
        out.append('>')
        for (child in children(element)) write(child, out, basePath, spine, resource, images)
        out.append("</a>")
    }

    private fun resolveHref(basePath: String, href: String, spine: Map<String, Int>): Pair<Int, String?>? {
        if (href.isBlank()) return null
        if (href.startsWith("#")) {
            val current = spine[basePath] ?: return null
            return current to href.drop(1)
        }
        if (href.startsWith("http:", true) || href.startsWith("https:", true) ||
            href.startsWith("mailto:", true) || href.startsWith("tel:", true)) return null
        val path = EpubMetadataParser.resolvePath(basePath, href) ?: return null
        val chapter = spine[path] ?: return null
        return chapter to href.substringAfter('#', "").ifBlank { null }
    }

    private fun image(element: Element, basePath: String, resource: (String) -> ByteArray?,
        images: ImageBudget): String? {
        val raw = element.getAttribute("src").trim()
        if (raw.isEmpty()) return null
        val alt = element.getAttribute("alt").take(200)
        val data = when {
            raw.startsWith("data:image/", true) -> {
                if (raw.length > Metrics.readerMaxImageBytes * 2) return null
                // Only a plain base64 image URI is passed through; a quote or markup character
                // in a hostile live document could otherwise leave the src attribute.
                if (!raw.contains(";base64,", ignoreCase = true)) return null
                if (raw.any { !(it.isLetterOrDigit() || it in "+/=:;.,_-") }) return null
                raw
            }
            raw.startsWith("http:", true) || raw.startsWith("https:", true) -> return null
            else -> {
                val path = EpubMetadataParser.resolvePath(basePath, raw) ?: return null
                val bytes = resource(path) ?: return null
                if (bytes.isEmpty() || bytes.size > Metrics.readerMaxImageBytes) return null
                if (!images.take(bytes.size)) return null
                val mime = mimeType(path, bytes)
                "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
            }
        }
        return "<img src=\"" + data + "\" alt=\"" + escape(alt) + "\">"
    }

    private fun mimeType(path: String, bytes: ByteArray): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "bmp" -> "image/bmp"
            else -> when {
                bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
                bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/gif"
                else -> "application/octet-stream"
            }
        }
    }

    private fun safeId(id: String): String = id.filter { it.isLetterOrDigit() || it in "_.:-" }.take(200)

    internal fun parseXml(bytes: ByteArray): Element {
        require(bytes.isNotEmpty() && bytes.size <= Metrics.readerMaxDocumentChars)
        val builder = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }.newDocumentBuilder()
        require(!String(bytes, Charsets.ISO_8859_1).replace("\u0000", "").contains("<!DOCTYPE", true))
        builder.setEntityResolver { _, _ -> throw SAXException("External entities are not allowed") }
        builder.setErrorHandler(object : DefaultHandler() {
            override fun error(e: org.xml.sax.SAXParseException) { throw e }
            override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
        })
        return try {
            builder.parse(InputSource(ByteArrayInputStream(bytes))).documentElement
        } catch (_: Exception) {
            // Many EPUBs use HTML named entities that are not declared in XHTML. A second attempt
            // with the common ones expanded to numeric references keeps those books readable.
            val text = ReaderText.decode(bytes)
            val expanded = text
                .replace("&nbsp;", "&#160;").replace("&mdash;", "&#8212;")
                .replace("&ndash;", "&#8211;").replace("&hellip;", "&#8230;")
                .replace("&lsquo;", "&#8216;").replace("&rsquo;", "&#8217;")
                .replace("&ldquo;", "&#8220;").replace("&rdquo;", "&#8221;")
            builder.parse(InputSource(expanded.byteInputStream(Charsets.UTF_8))).documentElement
        }
    }

    internal fun elements(root: Element, localName: String): List<Element> {
        val nodes = root.getElementsByTagNameNS("*", localName)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    internal fun localName(element: Element): String =
        element.localName ?: element.tagName.substringAfter(':')

    internal fun children(element: Element): List<Node> {
        val node = element.firstChild ?: return emptyList()
        val out = ArrayList<Node>()
        var current: Node? = node
        while (current != null) {
            out += current
            current = current.nextSibling
        }
        return out
    }

    private class ImageBudget {
        private var used = 0
        fun take(bytes: Int): Boolean {
            if (used + bytes > Metrics.readerMaxImagesBytes) return false
            used += bytes
            return true
        }
    }
}
