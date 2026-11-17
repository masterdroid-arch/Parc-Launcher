package com.brightblu.parc.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.BookPosition
import com.brightblu.parc.core.data.library.ReaderOpenFormat
import com.brightblu.parc.core.data.library.ReaderOpenHash
import com.brightblu.parc.core.data.library.stableBookId
import java.io.File

/**
 * T209: turn a VIEW intent into a reader request. Only content and file URIs in the three formats
 * the manifest accepts are read. The book is never added to the library here; that is the explicit
 * "Add to library" action in the reader bar.
 */
internal object ReaderOpenWith {
    fun request(context: Context, intent: Intent): ReaderRequest? {
        if (intent.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        if (uri.scheme != "content" && uri.scheme != "file") return null
        val file = describe(context, uri) ?: return null
        val format = ReaderOpenFormat.resolve(intent.type, file.name) ?: return null
        val hash = ReaderOpenHash.of(file.name, file.sizeBytes)
        val position = ReaderOpenPositionsStore(context).position(hash) ?: BookPosition()
        val book = Book(
            id = stableBookId(uri.toString()),
            uri = uri.toString(),
            title = ReaderOpenFormat.title(file.name),
            format = format,
            sizeBytes = file.sizeBytes,
        )
        return ReaderRequest(book, position, unadded = true, openHash = hash)
    }

    private data class Described(val name: String, val sizeBytes: Long)

    private fun describe(context: Context, uri: Uri): Described? {
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val file = File(path)
            val name = file.name.takeIf { it.isNotBlank() } ?: return null
            return Described(name, file.length().coerceAtLeast(0))
        }
        val resolver = context.applicationContext.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val display = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                    display?.takeIf { it.isNotBlank() }?.let { Described(it, size.coerceAtLeast(0)) }
                }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let {
            // A provider without an OpenableColumns row still gets a stable name; its size is 0.
            Described(it, 0L)
        }
        return name
    }

    fun book(found: com.brightblu.parc.core.data.library.FoundBook): Book = Book(
        id = stableBookId(found.uri),
        uri = found.uri,
        title = ReaderOpenFormat.title(found.name),
        format = found.format,
        sizeBytes = found.sizeBytes,
    )

    /** True when this file is already a library row (same uri, or the same stable id). */
    fun inLibrary(found: com.brightblu.parc.core.data.library.FoundBook, books: List<Book>): Boolean =
        books.any { it.uri == found.uri || it.id == stableBookId(found.uri) }
}
