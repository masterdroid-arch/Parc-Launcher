package com.brightblu.parc.reader

import android.content.Context
import android.net.Uri
import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.BookFiles
import com.brightblu.parc.core.data.library.BookFormat
import com.brightblu.parc.core.data.library.ReaderBookDoc
import com.brightblu.parc.core.data.library.ReaderBookProblem
import com.brightblu.parc.core.data.library.ReaderBookException
import com.brightblu.parc.core.data.library.ReaderEpub
import com.brightblu.parc.core.data.library.ReaderText
import com.brightblu.parc.model.Metrics
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keeps only the last opened book, so Continue can draw it while the tap is still on the screen. */
internal object ReaderCache {
    @Volatile private var cached: ReaderBookDoc? = null
    @Volatile private var cachedId: String? = null
    @Volatile private var cachedUri: String? = null

    fun get(book: Book): ReaderBookDoc? = cached?.takeIf {
        cachedId == book.id && cachedUri == book.uri && it.id == book.id
    }

    fun put(book: Book, doc: ReaderBookDoc) {
        cached = doc
        cachedId = book.id
        cachedUri = book.uri
    }

    fun clear() {
        cached = null
        cachedId = null
        cachedUri = null
    }
}

/** Reads one book off the main thread; the parser itself is framework-free. */
internal object ReaderLoader {
    const val LostAccess =
        "Parc cannot open this book because the file moved or access was lost. Locate the file, or restore access and retry."
    const val ReadFailed = "The book could not be read. Check that the file is available, then retry."
    const val BrokenArchive = "This EPUB archive is incomplete or damaged. Locate a complete copy of the file."

    suspend fun load(context: Context, book: Book): ReaderBookDoc = withContext(Dispatchers.IO) {
        ReaderCache.get(book)?.let { cached ->
            if (book.format != BookFormat.EPUB) return@withContext cached
            try {
                // A stream open still proves the grant and the file exist for every provider.
                BookFiles(context).open(book.uri).close()
                // A provider without file descriptors reports no size; only a known mismatch clears.
                val actual = runCatching {
                    BookFiles(context).descriptor(book.uri).use { it.statSize }
                }.getOrDefault(-1L)
                if (cacheSizeMatches(book.sizeBytes, actual)) return@withContext cached
                // The source changed on disk: never serve the stale parse, let the archive be read again.
                ReaderCache.clear()
            } catch (e: Exception) {
                throw epubReadError(e)
            }
        }
        val doc = when (book.format) {
            BookFormat.EPUB -> readEpub(context, book)
            BookFormat.TXT -> readText(context, book)
            BookFormat.PDF -> throw ReaderBookException(
                "This PDF is opened by the PDF reader.")
            BookFormat.CBZ -> throw ReaderBookException(
                "This comic is opened by the comic reader.")
        }
        ReaderCache.put(book, doc)
        doc
    }

    /** Unknown sizes (-1) and books added without one (0) cannot disprove a cache hit. */
    internal fun cacheSizeMatches(expected: Long, actual: Long): Boolean =
        expected <= 0 || actual < 0 || actual == expected

    private fun readEpub(context: Context, book: Book): ReaderBookDoc {
        val files = BookFiles(context)
        val direct = localFile(book.uri)
        var temp: File? = null
        val zipFile = try {
            if (direct != null) direct
            else {
                val copy = File.createTempFile("parc-reader-", ".epub", context.cacheDir)
                temp = copy
                files.open(book.uri).use { input ->
                    copy.outputStream().use { output -> copyCapped(input, output, Metrics.readerMaxSourceBytes) }
                }
                copy
            }
        } catch (e: ReaderBookException) {
            temp?.delete()
            throw e
        } catch (e: Exception) {
            temp?.delete()
            throw epubReadError(e)
        }
        return try {
            ZipFile(zipFile).use { zip -> ReaderEpub.parse(zip, book.id, book.title, book.author) }
        } catch (e: ReaderBookException) {
            throw e
        } catch (e: java.util.zip.ZipException) {
            throw brokenArchive(e)
        } catch (e: IOException) {
            throw epubReadError(e)
        } catch (e: SecurityException) {
            throw epubReadError(e)
        } finally {
            temp?.delete()
        }
    }

    private fun epubReadError(cause: Exception): ReaderBookException {
        val problem = problemFor(cause)
        return ReaderBookException(messageFor(problem), cause, problem)
    }

    /** Pure so the failure wording is unit-tested without an Android Context. */
    internal fun problemFor(cause: Throwable): ReaderBookProblem = when (cause) {
        is FileNotFoundException, is SecurityException -> ReaderBookProblem.LostAccess
        is java.util.zip.ZipException, is java.io.EOFException -> ReaderBookProblem.BrokenArchive
        else -> ReaderBookProblem.Other
    }

    internal fun messageFor(problem: ReaderBookProblem): String = when (problem) {
        ReaderBookProblem.LostAccess -> LostAccess
        ReaderBookProblem.BrokenArchive -> BrokenArchive
        ReaderBookProblem.UnsupportedMarkup -> "This EPUB uses markup the reader cannot open."
        ReaderBookProblem.Other -> ReadFailed
    }

    private fun brokenArchive(cause: Throwable) =
        ReaderBookException(BrokenArchive, cause, ReaderBookProblem.BrokenArchive)

    private fun readText(context: Context, book: Book): ReaderBookDoc {
        val bytes = try {
            BookFiles(context).open(book.uri).use { input -> readCapped(input, Metrics.readerMaxSourceBytes.toInt()) }
        } catch (e: ReaderBookException) {
            throw e
        } catch (e: Exception) {
            throw ReaderBookException("This text file could not be read. It may have moved.", e)
        }
        if (bytes.size > Metrics.readerMaxSourceBytes) {
            throw ReaderBookException("This text file is too large to open.")
        }
        val text = ReaderText.decode(bytes)
        if (text.length > Metrics.readerMaxDocumentChars) {
            throw ReaderBookException("This text file is too large to open.")
        }
        return ReaderBookDoc.plainText(book.id, book.title, book.author, text)
    }

    private fun localFile(location: String): File? {
        val uri = Uri.parse(location)
        return when (uri.scheme) {
            null -> File(location)
            "file" -> uri.path?.let { File(it) }
            else -> null
        }?.takeIf { it.isFile }
    }

    private fun readCapped(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(max, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > max) throw ReaderBookException("This book is too large to open.")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun copyCapped(input: InputStream, output: java.io.OutputStream, max: Long) {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > max) throw ReaderBookException("This book is too large to open.")
            output.write(buffer, 0, read)
        }
    }
}
