package com.brightblu.parc.core.data.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.brightblu.parc.model.Metrics
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.DataInputStream
import android.os.ParcelFileDescriptor
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The caller supplies the active Skin's ink/surface colours and bundled Figtree face. */
data class BookCoverStyle(val background: Int, val text: Int, val secondaryText: Int, val typeface: Typeface)
data class LoadedBook(val book: Book, val coverCached: Boolean)

/** Created on demand by Find books, never at launcher startup. No reader/WebView is retained. */
class BookMetadataLoader(context: Context) {
    private val app = context.applicationContext
    private val files = BookFiles(app)
    private val cache = BookCoverCache(File(app.cacheDir, "book-covers"))
    private val work = Mutex()

    /** Only call for selected files. This returns a row; adding it is a separate explicit action. */
    suspend fun load(
        found: FoundBook,
        style: BookCoverStyle,
        addedAt: Long = System.currentTimeMillis(),
        id: String = stableBookId(found.uri),
    ): LoadedBook =
        withContext(Dispatchers.IO) {
            work.withLock {
                var title = found.name.substringBeforeLast('.').ifBlank { found.name }
                var author = ""
                var cover: Bitmap? = null
                try {
                    when (found.format) {
                        BookFormat.EPUB -> withLocalCopy(found.uri) { local ->
                            ZipFile(local).use { zip ->
                                val container = zip.readEntry("META-INF/container.xml", EpubMetadataParser.MaxXmlBytes)
                                val opf = container?.let { EpubMetadataParser.packagePath(it) }
                                if (opf != null) {
                                    val data = zip.readEntry(opf, EpubMetadataParser.MaxXmlBytes)
                                        ?.let { EpubMetadataParser.parseOpf(it, opf) }
                                    title = data?.title ?: title
                                    author = data?.author.orEmpty()
                                    cover = data?.coverPath?.let { zip.readEntry(it, MaxImageBytes) }?.let { decodeCover(it) }
                                }
                            }
                        }
                        BookFormat.PDF -> {
                            fun readPdf(location: String) {
                                files.descriptor(location).use { descriptor ->
                                    // Read bounded metadata through a duplicate; PdfRenderer owns its
                                    // descriptor and seeks independently. Most providers need no copy.
                                    runCatching { pdfTitle(descriptor) }.getOrNull()?.let { title = it }
                                    PdfRenderer(descriptor).use { pdf ->
                                        if (pdf.pageCount > 0) pdf.openPage(0).use { page ->
                                            val scale = minOf(Metrics.bookCoverWidthPx.toFloat() / page.width,
                                                Metrics.bookCoverHeightPx.toFloat() / page.height)
                                            val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                                                (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                            try {
                                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                                cover = bitmap
                                            } catch (e: Exception) { bitmap.recycle(); throw e }
                                        }
                                    }
                                }
                            }
                            try { readPdf(found.uri) }
                            catch (_: java.io.IOException) { withLocalCopy(found.uri) { readPdf(it.absolutePath) } }
                            catch (_: IllegalArgumentException) { withLocalCopy(found.uri) { readPdf(it.absolutePath) } }
                        }
                        BookFormat.CBZ -> withLocalCopy(found.uri) { local ->
                            ZipFile(local).use { zip ->
                                cover = comicFirstImage(zip)?.let { decodeCover(it) }
                            }
                        }
                        BookFormat.TXT -> Unit
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { cover?.recycle(); throw e }
                catch (_: Exception) { cover?.recycle(); cover = null } // Broken metadata gets a typographic cover.
                val book = Book(id, found.uri, title, author, found.format,
                    found.sizeBytes, addedAt)
                val bitmap = cover ?: typographicCover(book, style)
                try {
                    val bytes = ByteArrayOutputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        output.toByteArray()
                    }
                    LoadedBook(book, runCatching { cache.put(book.id, bytes) }.getOrDefault(false))
                } finally { bitmap.recycle() }
            }
        }

    /** Decoding and disk I/O stay off the UI thread; the caller owns/recycles the returned bitmap. */
    suspend fun thumbnail(bookId: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { cache.get(bookId)?.let { decodeCover(it) } }.getOrNull()
    }

    /** A restored library has no thumbnails. Rebuild one on demand without changing its row. */
    suspend fun thumbnail(book: Book, style: BookCoverStyle): Bitmap? {
        thumbnail(book.id)?.let { return it }
        load(FoundBook(book.uri, "${book.title}.${book.format.name}", "", book.sizeBytes, 0, book.format),
            style, book.addedAt, book.id)
        return thumbnail(book.id)
    }

    /** Original art at the actual display size. Never enlarge a thumbnail or a small source. */
    suspend fun displayCover(book: Book, width: Int, height: Int): Bitmap? = withContext(Dispatchers.IO) {
        work.withLock {
            // Keep size-specific art separate from the old fixed-size thumbnail entries.
            val key = "display-v1:${book.id}:$width:$height"
            runCatching { cache.get(key)?.let { decodeCover(it, width, height, requireSharp = true) } }
                .getOrNull()?.let { return@withLock it }
            val cover = try {
                when (book.format) {
                    BookFormat.EPUB -> withLocalCopy(book.uri) { local ->
                        ZipFile(local).use { zip ->
                            val opf = zip.readEntry("META-INF/container.xml", EpubMetadataParser.MaxXmlBytes)
                                ?.let(EpubMetadataParser::packagePath)
                            val metadata = opf?.let { path ->
                                zip.readEntry(path, EpubMetadataParser.MaxXmlBytes)
                                    ?.let { EpubMetadataParser.parseOpf(it, path) }
                            }
                            metadata?.coverPath?.let { zip.readEntry(it, MaxImageBytes) }
                                ?.let { decodeCover(it, width, height, requireSharp = true) }
                        }
                    }
                    BookFormat.PDF -> {
                        fun render(location: String): Bitmap? = files.descriptor(location).use { descriptor ->
                            PdfRenderer(descriptor).use { pdf ->
                                if (pdf.pageCount == 0) null else pdf.openPage(0).use { page ->
                                    val size = BookCoverSizing.fit(page.width, page.height, width, height)
                                    val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                                    try {
                                        bitmap.eraseColor(android.graphics.Color.WHITE)
                                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        bitmap
                                    } catch (e: Exception) { bitmap.recycle(); throw e }
                                }
                            }
                        }
                        try { render(book.uri) }
                        catch (_: java.io.IOException) { withLocalCopy(book.uri) { render(it.absolutePath) } }
                        catch (_: IllegalArgumentException) { withLocalCopy(book.uri) { render(it.absolutePath) } }
                    }
                    BookFormat.CBZ -> withLocalCopy(book.uri) { local ->
                        ZipFile(local).use { zip ->
                            comicFirstImage(zip)?.let { decodeCover(it, width, height, requireSharp = true) }
                        }
                    }
                    BookFormat.TXT -> null
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
            if (cover != null) {
                runCatching {
                    ByteArrayOutputStream().use { output ->
                        cover.compress(Bitmap.CompressFormat.PNG, 100, output)
                        cache.put(key, output.toByteArray())
                    }
                }
            }
            cover
        }
    }

    private fun pdfTitle(descriptor: ParcelFileDescriptor): String? =
        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { input ->
            val size = input.channel.size()
            val head = ByteArray(minOf(size, PdfMetadataWindow.toLong()).toInt())
            input.channel.position(0)
            DataInputStream(input).readFully(head)
            val tail = if (size > head.size) {
                val length = minOf(size - head.size, PdfMetadataWindow.toLong()).toInt()
                input.channel.position(size - length)
                ByteArray(length).also { DataInputStream(input).readFully(it) }
            } else byteArrayOf()
            PdfMetadata.title(head + tail)
        }

    private suspend fun <T> withLocalCopy(location: String, block: (File) -> T): T {
        // Path scans already supply seekable files: do not duplicate large local books.
        if (location.startsWith('/')) return block(File(location))
        val directory = File(app.cacheDir, "book-work").apply { mkdirs() }
        val temp = File.createTempFile("book-", ".tmp", directory)
        try {
            files.open(location).use { input -> temp.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MaxTemporaryBytes) { "Book metadata is too large" }
                    output.write(buffer, 0, count)
                }
            } }
            return block(temp)
        } finally { temp.delete() }
    }

    /** T253: a comic's cover is its first page image, by the same page order the reader uses. */
    private fun comicFirstImage(zip: ZipFile): ByteArray? {
        val first = ComicPages.pages(zip.entries().asSequence().map { it.name }.toList()).firstOrNull()
            ?: return null
        return zip.readEntry(first, MaxComicCoverBytes)
    }

    private fun ZipFile.readEntry(path: String, limit: Int): ByteArray? {
        val entry = getEntry(path) ?: return null
        if (entry.isDirectory || entry.size > limit) return null
        return getInputStream(entry).use { it.boundedBytes(limit) }
    }

    private fun decodeCover(bytes: ByteArray, width: Int = Metrics.bookCoverWidthPx,
        height: Int = Metrics.bookCoverHeightPx, requireSharp: Boolean = false): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (requireSharp && !BookCoverSizing.sharpEnough(bounds.outWidth, bounds.outHeight, width, height)) return null
        val fitted = BookCoverSizing.fit(bounds.outWidth, bounds.outHeight, width, height)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= fitted.width &&
            bounds.outHeight / (sample * 2) >= fitted.height) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = minOf(1f, width.toFloat() / bitmap.width,
            height.toFloat() / bitmap.height)
        if (scale == 1f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1), true).also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun typographicCover(book: Book, style: BookCoverStyle): Bitmap {
        val width = Metrics.bookCoverWidthPx
        val bitmap = Bitmap.createBitmap(width, Metrics.bookCoverHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(style.background)
        val inset = width * Metrics.bookCoverInsetFraction
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.text; typeface = style.typeface; textSize = width * Metrics.bookCoverTitleFraction
        }
        val layout = StaticLayout.Builder.obtain(book.title, 0, book.title.length, paint, (width - inset * 2).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
            .setLineSpacing(0f, Metrics.bookCoverLineSpacing).setMaxLines(Metrics.bookCoverTitleLines)
            .setEllipsize(TextUtils.TruncateAt.END).build()
        canvas.save(); canvas.translate(inset, inset); layout.draw(canvas); canvas.restore()
        paint.color = style.secondaryText
        paint.textSize = width * Metrics.bookCoverAuthorFraction
        val author = TextUtils.ellipsize(book.author, paint, width - inset * 2, TextUtils.TruncateAt.END).toString()
        canvas.drawText(author, inset, bitmap.height - inset * 2, paint)
        canvas.drawText(book.format.name, inset, bitmap.height - inset, paint)
        return bitmap
    }

    private companion object {
        const val MaxImageBytes = 8 * 1024 * 1024
        const val MaxComicCoverBytes = 24 * 1024 * 1024
        const val PdfMetadataWindow = 1024 * 1024
        const val MaxTemporaryBytes = 256L * 1024 * 1024
    }
}

internal fun InputStream.boundedBytes(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        if (count < 0) break
        require(output.size() + count <= limit) { "Book entry is too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
