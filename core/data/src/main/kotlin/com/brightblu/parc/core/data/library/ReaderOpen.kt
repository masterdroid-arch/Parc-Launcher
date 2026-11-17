package com.brightblu.parc.core.data.library

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

/**
 * T209: the three formats the reader accepts from another app and from the Files search mode.
 * CBZ is deliberately absent: open-with only registers EPUB, PDF and plain text.
 */
object ReaderOpenFormat {
    val supported: Set<BookFormat> = setOf(BookFormat.EPUB, BookFormat.PDF, BookFormat.TXT)

    fun fromMime(mime: String?): BookFormat? = when (mime?.substringBefore(';')?.trim()?.lowercase()) {
        BookFormat.EPUB.mimeType -> BookFormat.EPUB
        BookFormat.PDF.mimeType -> BookFormat.PDF
        BookFormat.TXT.mimeType -> BookFormat.TXT
        else -> null
    }

    fun fromName(name: String): BookFormat? =
        BookFormat.fromName(name)?.takeIf { it in supported }

    /** The MIME type wins; a provider that sends a vague type still gets its extension read. */
    fun resolve(mime: String?, name: String): BookFormat? = fromMime(mime) ?: fromName(name)

    /** The title shown before metadata is read: the display name without its last extension. */
    fun title(name: String): String = name.substringBeforeLast('.').ifBlank { name }.ifBlank { "Untitled" }
}

/**
 * T209: an unadded file's place is remembered by a hash of its display name plus size, never by a
 * path (the same path can be reused) and never by page number. Pure so it is unit tested.
 */
object ReaderOpenHash {
    fun of(name: String, sizeBytes: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$name\n${sizeBytes.coerceAtLeast(0)}".toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            out.append(Hex[value ushr 4]).append(Hex[value and 0x0f])
        }
        return out.toString()
    }

    private const val Hex = "0123456789abcdef"
}

/** One remembered place for an unadded file. [updatedAt] is wall-clock millis. */
@Serializable
data class ReaderOpenPosition(
    val hash: String,
    val chapter: Int = 0,
    val characterOffset: Int = 0,
    val page: Int = 0,
    val pageOffset: Double = 0.0,
    val updatedAt: Long,
) {
    init {
        require(hash.isNotBlank())
        require(chapter >= 0 && characterOffset >= 0 && page >= 0)
        require(pageOffset.isFinite() && pageOffset in 0.0..1.0)
        require(updatedAt >= 0)
    }

    fun position(): BookPosition = BookPosition(chapter, characterOffset, page, pageOffset)

    companion object {
        fun of(hash: String, position: BookPosition, updatedAt: Long): ReaderOpenPosition =
            ReaderOpenPosition(hash, position.chapter, position.characterOffset,
                position.page, position.pageOffset, updatedAt)
    }
}

/**
 * The tiny on-phone document for unadded positions. It is deliberately not in the backup: those
 * places belong to files that were never chosen into the library. Entries older than 30 days are
 * dropped on every read and write; a future clock never deletes a place early.
 */
object ReaderOpenPositions {
    const val RetentionMs = 30L * 24 * 60 * 60 * 1000
    const val MaxEntries = 200
    const val MaxChars = 200_000

    fun retained(entries: List<ReaderOpenPosition>, now: Long): List<ReaderOpenPosition> =
        entries.asSequence()
            .filter { it.updatedAt >= 0 }
            .filter { now - it.updatedAt <= RetentionMs }
            .sortedWith(compareByDescending<ReaderOpenPosition> { it.updatedAt }.thenBy { it.hash })
            .distinctBy { it.hash }
            .take(MaxEntries)
            .toList()

    fun find(entries: List<ReaderOpenPosition>, hash: String, now: Long): ReaderOpenPosition? =
        retained(entries, now).firstOrNull { it.hash == hash }

    /** Add or replace one hash, drop expired rows and keep the newest [MaxEntries]. */
    fun merge(entries: List<ReaderOpenPosition>, entry: ReaderOpenPosition, now: Long): List<ReaderOpenPosition> =
        retained(entries.filterNot { it.hash == entry.hash } + entry, now)
}

object ReaderOpenPositionsJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(entries: List<ReaderOpenPosition>): String {
        val text = json.encodeToString(JsonObject(mapOf(
            "positions" to JsonArray(entries.map { json.encodeToJsonElement(ReaderOpenPosition.serializer(), it) }),
        )))
        require(text.length <= ReaderOpenPositions.MaxChars) { "Open-with positions are too large" }
        return text
    }

    /** Empty, old, malformed or oversized documents read as nothing; one bad row keeps the others. */
    fun decode(text: String?, now: Long): List<ReaderOpenPosition> {
        if (text.isNullOrBlank() || text.length > ReaderOpenPositions.MaxChars) return emptyList()
        val rows = runCatching {
            when (val root = json.parseToJsonElement(text)) {
                is JsonArray -> root
                is JsonObject -> root["positions"]?.jsonArray
                else -> null
            }
        }.getOrNull() ?: return emptyList()
        val decoded = rows.mapNotNull {
            runCatching { json.decodeFromJsonElement(ReaderOpenPosition.serializer(), it) }.getOrNull()
        }
        return ReaderOpenPositions.retained(decoded, now)
    }
}
