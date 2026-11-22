package com.brightblu.parc.reader

import android.graphics.Bitmap

/**
 * T352: the shelf tile already holds the book's real cover art in memory. The reader runs in its
 * own task, so the bitmap cannot travel through the intent; this small handoff lets the opening
 * shell paint the real cover on its first frame instead of a title card while the display-sized
 * copy is decoded. The shelf owns these bitmaps (Compose wraps the same pixels), so nothing here
 * recycles them. Only the few tiles near the tap are kept.
 */
internal object ReaderCoverHandoff {
    private const val Keep = 4

    private val covers = object : LinkedHashMap<String, Bitmap>(Keep, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > Keep
    }

    @Synchronized
    fun remember(bookId: String, bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        covers[bookId] = bitmap
    }

    @Synchronized
    fun get(bookId: String): Bitmap? = covers[bookId]?.takeUnless { it.isRecycled }
}
