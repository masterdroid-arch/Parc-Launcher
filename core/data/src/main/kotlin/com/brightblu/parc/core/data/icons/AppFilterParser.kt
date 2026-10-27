package com.brightblu.parc.core.data.icons

/**
 * One start tag read from an appfilter.xml stream. Kept free of Android types so the parser runs
 * under plain JVM unit tests; [AndroidIconPackSource] builds these from an [android.content.res.XmlResourceParser].
 *
 * Immutable; attribute names are exactly as written in the file (no namespace prefix stripping
 * beyond what the platform parser already does).
 */
data class AppFilterTag(val name: String, val attributes: Map<String, String>)

/**
 * Parses an icon pack's `appfilter.xml` into a component-to-drawable map.
 *
 * The usual entry is `<item component="ComponentInfo{pkg/pkg.Activity}" drawable="name"/>`, but
 * packs in the wild also write a bare `pkg/pkg.Activity`, an activity alias
 * `ComponentInfo{pkg/pkg.Activity{alias}}`, and the `.Activity` shorthand. All of them normalise to
 * the same lower-case `pkg/pkg.class` key, which is what [normalizeComponent] returns and what
 * [com.brightblu.parc.core.data.AppId.flattenedComponent] resolves through.
 *
 * Parsing is deliberately forgiving: a malformed entry is skipped, never thrown on, because a pack
 * with one bad line must still theme the rest of its apps.
 */
object AppFilterParser {
    const val ItemTag = "item"
    const val ComponentAttribute = "component"
    const val DrawableAttribute = "drawable"

    /** Builds the map from already-read tags; later entries for the same component win. */
    fun parse(tags: List<AppFilterTag>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        tags.forEach { tag ->
            if (!tag.name.equals(ItemTag, ignoreCase = true)) return@forEach
            val key = normalizeComponent(tag.attributes[ComponentAttribute] ?: return@forEach)
                ?: return@forEach
            val drawable = tag.attributes[DrawableAttribute]?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@forEach
            out[key] = drawable
        }
        return out
    }

    /** Reads a plain-text appfilter.xml; the production path uses [parse] over platform tags. */
    fun parseXml(xml: String): Map<String, String> = parse(tagsFromXml(xml))

    /**
     * Normalises one component value to `pkg/class` in lower case, or null when it cannot describe
     * an activity. Case is folded because activity names are compared by launchers
     * case-insensitively and packs disagree about capitalisation.
     */
    fun normalizeComponent(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        if (value.startsWith("ComponentInfo", ignoreCase = true)) {
            val open = value.indexOf('{')
            val close = value.lastIndexOf('}')
            if (open < 0 || close <= open) return null
            value = value.substring(open + 1, close).trim()
        }
        val slash = value.indexOf('/')
        if (slash <= 0 || slash == value.lastIndex) return null
        val packageName = value.substring(0, slash).trim()
        var className = value.substring(slash + 1).trim()
        if (packageName.isEmpty() || className.isEmpty()) return null
        // Activity alias suffix, e.g. `pkg.Activity{alias}`: the alias does not change the target.
        className = className.substringBefore('{').trim()
        if (className.isEmpty()) return null
        if (className.startsWith('.')) className = packageName + className
        return (packageName + "/" + className).lowercase()
    }

    /**
     * Minimal scanner for plain-text appfilter XML, used by tests and by any pack that ships the
     * file as text. It only has to understand start tags; text, CDATA and processing instructions
     * are skipped. An unterminated tag ends the scan without throwing.
     */
    fun tagsFromXml(xml: String): List<AppFilterTag> {
        val tags = ArrayList<AppFilterTag>()
        var index = 0
        while (index < xml.length) {
            val open = xml.indexOf('<', index)
            if (open < 0 || open + 1 >= xml.length) break
            when (xml[open + 1]) {
                '/' -> {
                    index = skipPast(xml, open + 2, '>')
                    continue
                }
                '!' -> {
                    index = if (xml.startsWith("<!--", open)) {
                        val end = xml.indexOf("-->", open + 4)
                        if (end < 0) xml.length else end + 3
                    } else {
                        skipPast(xml, open + 2, '>')
                    }
                    continue
                }
                '?' -> {
                    index = skipPast(xml, open + 2, '>')
                    continue
                }
            }
            val end = findTagEnd(xml, open + 1)
            if (end < 0) break
            var body = xml.substring(open + 1, end)
            if (body.endsWith("/")) body = body.dropLast(1)
            val name = body.trimStart().takeWhile { !it.isWhitespace() && it != '/' }
            if (name.isNotEmpty()) {
                tags.add(AppFilterTag(name, attributesOf(body.substring(name.length))))
            }
            index = end + 1
        }
        return tags
    }

    private fun skipPast(xml: String, from: Int, target: Char): Int {
        val at = xml.indexOf(target, from)
        return if (at < 0) xml.length else at + 1
    }

    /** Index of the closing `>` of a tag, ignoring one inside a quoted attribute value. */
    private fun findTagEnd(xml: String, from: Int): Int {
        var quote: Char? = null
        var i = from
        while (i < xml.length) {
            val c = xml[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
            i++
        }
        return -1
    }

    private fun attributesOf(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && (text[i].isWhitespace() || text[i] == '/')) i++
            val nameStart = i
            while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' ||
                    text[i] == ':' || text[i] == '.' || text[i] == '-')) i++
            val name = text.substring(nameStart, i)
            if (name.isEmpty()) {
                i++
                continue
            }
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || text[i] != '=') continue
            i++
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) break
            val quote = text[i]
            if (quote != '"' && quote != '\'') break
            val valueStart = ++i
            while (i < text.length && text[i] != quote) i++
            if (i >= text.length) break
            if (name.isNotEmpty()) out[name] = decodeEntities(text.substring(valueStart, i))
            i++
        }
        return out
    }

    private fun decodeEntities(value: String): String {
        if (!value.contains('&')) return value
        return value
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }
}
