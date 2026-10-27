package com.brightblu.parc.core.data.comms

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Reads the small preview image attached to a message (T55) and immediately reduces it to a
 * [CommsImage]. The original bitmap or byte array never leaves this class, and nothing is written
 * to disk.
 *
 * Two sources exist in the platform: `Notification.EXTRA_PICTURE` (a Bitmap the listener already
 * received) and `MessagingStyle.Message.getDataUri()` (a content URI the posting app granted to the
 * listener). The second is read through [ContentResolver] with a hard 2 MB cap before decoding, so
 * a hostile URI cannot make Parc allocate an unbounded buffer. Decoding is bounds-first and
 * subsampled; the result is at most 96 px on the long side before dithering.
 *
 * Every failure (missing grant, unreadable URI, corrupt bytes, a hardware bitmap that cannot be
 * read) yields null and the message simply has no image. Nothing is logged.
 */
internal class CommsImageLoader(private val resolver: ContentResolver) {

    /** Converts an already-received preview bitmap, or returns null when it cannot be read. */
    fun fromBitmap(bitmap: Bitmap?): CommsImage? {
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0 || bitmap.isRecycled) return null
        return try {
            val size = OrderedDither.targetSize(bitmap.width, bitmap.height)
            val source = if (size.width != bitmap.width || size.height != bitmap.height) {
                Bitmap.createScaledBitmap(bitmap, size.width, size.height, true)
            } else {
                bitmap
            }
            val pixels = IntArray(size.width * size.height)
            source.getPixels(pixels, 0, size.width, 0, 0, size.width, size.height)
            OrderedDither.fromPixels(pixels, size.width, size.height)
        } catch (e: RuntimeException) {
            // Hardware bitmaps reject getPixels, and a recycled bitmap can race an update.
            null
        }
    }

    /** Reads a data URI with the size cap, decodes it subsampled, and dithers it. */
    fun fromUri(raw: String?): CommsImage? {
        if (raw.isNullOrBlank()) return null
        return try {
            val bytes = readCapped(Uri.parse(raw)) ?: return null
            val bitmap = decode(bytes) ?: return null
            fromBitmap(bitmap)
        } catch (e: Exception) {
            // A data URI whose file is gone throws FileNotFoundException (an IOException, not a
            // RuntimeException); a bad notification must never take the process down.
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun readCapped(uri: Uri): ByteArray? {
        val input = resolver.openInputStream(uri) ?: return null
        return input.use { stream ->
            val buffer = ByteArray(8 * 1024)
            val output = ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > CommsImageLimits.MaxBytes) return null
                output.write(buffer, 0, read)
            }
            if (output.size() == 0) null else output.toByteArray()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val target = OrderedDither.targetSize(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target.width &&
            bounds.outHeight / (sample * 2) >= target.height
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}

/** Bounds of the Comms image pipeline; the card fixes both. */
internal object CommsImageLimits {

    /** 2 MB compressed input ceiling, checked while reading. */
    const val MaxBytes = 2 * 1024 * 1024

    /** At most 96 px on the long side after scaling. */
    const val MaxSide = CommsImage.MaxSide
}
