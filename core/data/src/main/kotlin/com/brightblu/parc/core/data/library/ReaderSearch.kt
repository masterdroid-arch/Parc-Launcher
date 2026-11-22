package com.brightblu.parc.core.data.library

/**
 * T254: one place where a search matched. EPUB and text books jump by [chapter] and [offset] (a
 * character offset, so it survives any font or margin change); a PDF jumps by [page].
 */
data class ReaderSearchHit(
    val chapter: Int,
    val offset: Int,
    val page: Int,
    val before: String,
    val match: String,
    val after: String,
)

/** Pure, framework-free in-book search: case-insensitive, accent-insensitive, non-overlapping, in reading order. */
object ReaderSearch {
    const val MinQueryLength = 2
    const val MaxResults = 200
    const val SnippetContext = 36

    fun clean(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    fun isSearchable(raw: String): Boolean = clean(raw).length >= MinQueryLength

    /** Every match in one block of text. [chapter] and [page] are copied to each hit. */
    fun inText(text: String, query: String, chapter: Int, page: Int, limit: Int = MaxResults): List<ReaderSearchHit> {
        val needle = clean(query)
        if (needle.length < MinQueryLength || limit <= 0) return emptyList()
        val foldedNeedle = fold(needle)
        if (foldedNeedle.text.isEmpty()) return emptyList()
        val hay = fold(text)
        val hits = ArrayList<ReaderSearchHit>()
        var from = 0
        while (hits.size < limit) {
            val at = hay.text.indexOf(foldedNeedle.text, from)
            if (at < 0) break
            val foldedEnd = at + foldedNeedle.text.length
            // A hit can end on a combining mark's base; map the last folded unit back and include
            // any marks that belong to it, so a decomposed accent reads as one word.
            val start = hay.map[at]
            var end = hay.map[foldedEnd - 1] + 1
            while (end < text.length && isMark(text[end])) end++
            hits += ReaderSearchHit(chapter, start, page,
                snippet(text, (start - SnippetContext).coerceAtLeast(0), start, leading = true),
                text.substring(start, end),
                snippet(text, end, (end + SnippetContext).coerceAtMost(text.length), leading = false))
            from = foldedEnd
        }
        return hits
    }

    private fun isMark(c: Char): Boolean {
        val type = Character.getType(c).toInt()
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    /**
     * T347: lowercase text plus a map from each folded character back to its source character.
     * Accents are removed so a query typed without them still finds the printed Portuguese word,
     * while every hit keeps the exact character offset used to restore the reading place.
     */
    private class Folded(val text: String, val map: IntArray)

    private fun fold(source: String): Folded {
        val out = StringBuilder(source.length)
        val map = IntArray(source.length * 2)
        var used = 0
        for (index in source.indices) {
            val c = source[index]
            if (c.code < 0x80) {
                out.append(c.lowercaseChar())
                map[used++] = index
                continue
            }
            val decomposed = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)
            for (part in decomposed) {
                if (isMark(part)) continue
                out.append(part.lowercaseChar())
                map[used++] = index
            }
        }
        return Folded(out.toString(), map.copyOf(used))
    }

    fun inDoc(doc: ReaderBookDoc, query: String, limit: Int = MaxResults): List<ReaderSearchHit> {
        val all = ArrayList<ReaderSearchHit>()
        for (chapter in doc.chapters) {
            if (all.size >= limit) break
            all += inText(chapter.text, query, chapter.index, 0, limit - all.size)
        }
        return all
    }

    /** Line breaks become spaces so a hit always reads as one short line. */
    private fun snippet(text: String, from: Int, to: Int, leading: Boolean): String {
        val raw = text.substring(from.coerceIn(0, text.length), to.coerceIn(0, text.length))
            .replace(Regex("\\s+"), " ")
        val cut = if (leading && from > 0) "…$raw" else raw
        return if (!leading && to < text.length) "$cut…" else cut
    }

    /** "Chapter 3 · 41%" for a text book, "Page 12" for a PDF. */
    fun where(doc: ReaderBookDoc?, hit: ReaderSearchHit): String =
        if (doc == null) "Page ${hit.page + 1}"
        else "Chapter ${hit.chapter + 1} · ${ReaderPositionMath.progressPercent(doc, BookPosition(hit.chapter, hit.offset))}%"
}
