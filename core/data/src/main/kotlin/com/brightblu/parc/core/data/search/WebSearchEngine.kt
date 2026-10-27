package com.brightblu.parc.core.data.search

import java.net.URLEncoder
import java.util.Locale

/**
 * The web search engines the search sheet can hand a query to (T67). Immutable, non-null.
 *
 * The URL is built here, but nothing is fetched by Parc: the host opens it with
 * `Intent.ACTION_VIEW`, so no INTERNET permission is declared or needed. [id] is the value stored
 * in DataStore, so renaming an enum constant never loses a stored choice.
 */
enum class WebSearchEngine(
    val id: String,
    val label: String,
    private val template: String,
) {
    Google("google", "Google", "https://www.google.com/search?q=%s"),
    DuckDuckGo("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    Brave("brave", "Brave", "https://search.brave.com/search?q=%s"),
    Startpage("startpage", "Startpage", "https://www.startpage.com/sp/search?query=%s");

    /**
     * Absolute URL for [query], or null for a blank query. Spaces are percent-encoded rather than
     * left as `+` so the string is valid in both a browser and a sharing intent.
     */
    fun searchUrl(query: String): String? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null
        val encoded = URLEncoder.encode(trimmed, Charsets.UTF_8.name()).replace("+", "%20")
        return template.format(Locale.ROOT, encoded)
    }

    companion object {
        /** Unknown or missing stored ids fall back to [Google], the documented default. */
        fun fromId(id: String?): WebSearchEngine =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: Google
    }
}
