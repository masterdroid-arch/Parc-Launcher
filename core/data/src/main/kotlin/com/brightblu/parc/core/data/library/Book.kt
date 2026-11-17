package com.brightblu.parc.core.data.library

import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

@Serializable
enum class BookFormat(val mimeType: String) {
    EPUB("application/epub+zip"), PDF("application/pdf"), TXT("text/plain"), CBZ("application/vnd.comicbook+zip");

    companion object {
        fun fromName(name: String): BookFormat? = entries.firstOrNull {
            it.name.lowercase(Locale.ROOT) == name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        }

        fun accepts(name: String, types: Set<BookFormat>): Boolean = fromName(name) in types
        fun mimeTypes(types: Set<BookFormat>): Array<String> = types.flatMap {
            if (it == CBZ) listOf(it.mimeType, "application/x-cbz", "application/zip") else listOf(it.mimeType)
        }.distinct().toTypedArray()
    }
}

/** Character offset is within the EPUB chapter / text; page and offset serve PDF and comics. */
@Serializable
data class BookPosition(
    val chapter: Int = 0,
    val characterOffset: Int = 0,
    val page: Int = 0,
    val pageOffset: Double = 0.0,
) {
    init {
        require(chapter >= 0 && characterOffset >= 0 && page >= 0)
        require(pageOffset.isFinite() && pageOffset in 0.0..1.0)
    }
}

@Serializable
data class Book(
    val id: String,
    val uri: String,
    val title: String,
    val author: String = "",
    val format: BookFormat,
    val sizeBytes: Long = 0,
    val addedAt: Long = 0,
    val lastOpenedAt: Long? = null,
    val progress: Double = 0.0,
    val position: BookPosition = BookPosition(),
    /** T251: the user marked the book finished (independent of how far the reader got). */
    val finished: Boolean = false,
    /** T251: the one-time last-page question has been shown; it is never shown for this book again. */
    val finishAsked: Boolean = false,
    /** T251: the reader reached the last page (for a one-page book, its only page). */
    val reachedEnd: Boolean = false,
    /** T251: ids of the user's own shelves; the built-in shelves are never stored here. */
    val shelves: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank() && uri.isNotBlank() && title.isNotBlank())
        require(sizeBytes >= 0 && addedAt >= 0 && (lastOpenedAt == null || lastOpenedAt >= 0))
        require(progress.isFinite() && progress in 0.0..1.0)
    }
}

/** Availability is checked locally after restore; it is never trusted from a backup. */
data class LibraryEntry(val book: Book, val fileNotFound: Boolean) {
    val statusLabel: String? get() = if (fileNotFound) "File not found" else null
    val removeActionLabel: String get() = "Remove from library"
}

object LibraryJson {
    const val MaxChars = 1_000_000
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(books: List<Book>): String = json.encodeToString(
        JsonObject(mapOf("books" to JsonArray(books.map { json.encodeToJsonElement(Book.serializer(), it) }))),
    ).also { require(it.length <= MaxChars) { "Library is too large" } }

    /** Empty/old documents are empty; a malformed row cannot discard the other books. */
    fun decode(text: String?): List<Book> {
        if (text.isNullOrBlank() || text.length > MaxChars) return emptyList()
        val rows = runCatching {
            when (val root = json.parseToJsonElement(text)) {
                is JsonArray -> root
                is JsonObject -> root["books"]?.jsonArray
                else -> null
            }
        }.getOrNull() ?: return emptyList()
        return rows.mapNotNull { runCatching { json.decodeFromJsonElement(Book.serializer(), it) }.getOrNull() }
            // T251: old rows have no shelves; a malformed membership list is cleaned, never fatal.
            .map { it.copy(shelves = it.shelves.map(String::trim).filter(String::isNotBlank).distinct()) }
            .distinctBy { it.id }.distinctBy { it.uri }
    }
}
