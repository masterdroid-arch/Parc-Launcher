package com.brightblu.parc.reader

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.BookFormat
import com.brightblu.parc.core.data.library.BookPosition
import com.brightblu.parc.core.data.library.FoundBook
import com.brightblu.parc.core.data.library.ReaderOpenHash

/** The small, explicit contract between the Library shelf and the reader activity. */
internal data class ReaderRequest(
    val book: Book,
    val initialPosition: BookPosition,
    /** T209: opened from another app or Files search, so it is not (yet) a library book. */
    val unadded: Boolean = false,
    /** T209: the name-plus-size hash that remembers an unadded file's place for 30 days. */
    val openHash: String? = null,
    /** T210: the shelf cover's window rectangle, so the page can grow from it and shrink back. */
    val coverRect: ReaderMotion.Rect? = null,
)

internal object ReaderContract {
    const val ExtraBookId = "com.brightblu.parc.reader.BOOK_ID"
    const val ExtraLocation = "com.brightblu.parc.reader.LOCATION"
    const val ExtraTitle = "com.brightblu.parc.reader.TITLE"
    const val ExtraAuthor = "com.brightblu.parc.reader.AUTHOR"
    const val ExtraFormat = "com.brightblu.parc.reader.FORMAT"
    const val ExtraChapter = "com.brightblu.parc.reader.CHAPTER"
    const val ExtraOffset = "com.brightblu.parc.reader.OFFSET"
    const val ExtraPage = "com.brightblu.parc.reader.PAGE"
    const val ExtraPageOffset = "com.brightblu.parc.reader.PAGE_OFFSET"
    const val ExtraUnadded = "com.brightblu.parc.reader.UNADDED"
    const val ExtraOpenHash = "com.brightblu.parc.reader.OPEN_HASH"
    const val ExtraCoverLeft = "com.brightblu.parc.reader.COVER_LEFT"
    const val ExtraCoverTop = "com.brightblu.parc.reader.COVER_TOP"
    const val ExtraCoverRight = "com.brightblu.parc.reader.COVER_RIGHT"
    const val ExtraCoverBottom = "com.brightblu.parc.reader.COVER_BOTTOM"

    fun intent(context: Context, book: Book, position: BookPosition = book.position,
        unadded: Boolean = false, openHash: String? = null,
        coverRect: ReaderMotion.Rect? = null): Intent =
        Intent(context, ReaderActivity::class.java).apply {
            putExtra(ExtraBookId, book.id)
            putExtra(ExtraLocation, book.uri)
            putExtra(ExtraTitle, book.title)
            putExtra(ExtraAuthor, book.author)
            putExtra(ExtraFormat, book.format.name)
            putExtra(ExtraChapter, position.chapter)
            putExtra(ExtraOffset, position.characterOffset)
            putExtra(ExtraPage, position.page)
            putExtra(ExtraPageOffset, position.pageOffset)
            putExtra(ExtraUnadded, unadded)
            putExtra(ExtraOpenHash, openHash)
            if (coverRect != null && coverRect.isValid) {
                putExtra(ExtraCoverLeft, coverRect.left)
                putExtra(ExtraCoverTop, coverRect.top)
                putExtra(ExtraCoverRight, coverRect.right)
                putExtra(ExtraCoverBottom, coverRect.bottom)
            }
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun open(context: Context, book: Book, coverRect: ReaderMotion.Rect? = null) {
        context.startActivity(intent(context, book, coverRect = coverRect))
    }

    /** T209: tap a file found by Files search (or a VIEW intent) without adding it to the library. */
    fun openUnadded(context: Context, found: FoundBook, position: BookPosition, hash: String) {
        context.startActivity(intent(context, ReaderOpenWith.book(found), position,
            unadded = true, openHash = hash))
    }

    fun hashFor(found: FoundBook): String = ReaderOpenHash.of(found.name, found.sizeBytes)

    fun request(intent: Intent): ReaderRequest? {
        val id = intent.getStringExtra(ExtraBookId)?.takeIf { it.isNotBlank() } ?: return null
        val location = intent.getStringExtra(ExtraLocation)?.takeIf { it.isNotBlank() } ?: return null
        val title = intent.getStringExtra(ExtraTitle)?.takeIf { it.isNotBlank() } ?: return null
        val format = intent.getStringExtra(ExtraFormat)?.let { name ->
            BookFormat.entries.firstOrNull { it.name == name }
        } ?: return null
        val chapter = intent.getIntExtra(ExtraChapter, 0).coerceAtLeast(0)
        val offset = intent.getIntExtra(ExtraOffset, 0).coerceAtLeast(0)
        val page = intent.getIntExtra(ExtraPage, 0).coerceAtLeast(0)
        val pageOffset = intent.getDoubleExtra(ExtraPageOffset, 0.0).coerceIn(0.0, 1.0)
        val author = intent.getStringExtra(ExtraAuthor).orEmpty()
        val position = BookPosition(chapter, offset, page, pageOffset)
        val cover = ReaderMotion.coverOrNull(
            intent.getFloatExtra(ExtraCoverLeft, 0f),
            intent.getFloatExtra(ExtraCoverTop, 0f),
            intent.getFloatExtra(ExtraCoverRight, 0f),
            intent.getFloatExtra(ExtraCoverBottom, 0f),
        )
        return ReaderRequest(
            Book(id, location, title, author, format, position = position),
            position,
            unadded = intent.getBooleanExtra(ExtraUnadded, false),
            openHash = intent.getStringExtra(ExtraOpenHash)?.takeIf { it.isNotBlank() },
            coverRect = cover,
        )
    }
}
