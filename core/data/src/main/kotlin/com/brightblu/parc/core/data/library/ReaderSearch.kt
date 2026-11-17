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

/** Pure, framework-free in-book search: case-insensitive, non-overlapping, in reading order. */
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
        val hits = ArrayList<ReaderSearchHit>()
        var from = 0
        while (hits.size < limit) {
            val at = text.indexOf(needle, from, ignoreCase = true)
            if (at < 0) break
            val end = at + needle.length
            hits += ReaderSearchHit(chapter, at, page,
                snippet(text, (at - SnippetContext).coerceAtLeast(0), at, leading = true),
                text.substring(at, end),
                snippet(text, end, (end + SnippetContext).coerceAtMost(text.length), leading = false))
            from = end
        }
        return hits
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
