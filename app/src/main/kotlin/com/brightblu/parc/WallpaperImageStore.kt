package com.brightblu.parc

import android.content.Context
import android.graphics.*
import android.net.Uri
import com.brightblu.parc.model.*
import java.io.File
import java.io.FileOutputStream

/** Bounded private image files. All callers run decoding and writing on Dispatchers.IO. */
internal class WallpaperImageStore(context: Context) {
    private val context = context.applicationContext
    private val directory get() = File(context.filesDir, "wallpaper")
    val file get() = File(directory, "home.jpg")
    private val source get() = File(directory, "crop.jpg")
    fun stage(uri: Uri): Boolean = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        val src = ImageDecoder.createSource(resolver, uri)
        val oriented = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val sample = WallpaperImageRules.sampleSize(info.size.width, info.size.height)
            decoder.setTargetSampleSize(sample)
            val size = WallpaperImageRules.output(info.size.width.toFloat(), info.size.height.toFloat())
            decoder.setTargetSize(size.first, size.second)
        }
        directory.mkdirs()
        try { FileOutputStream(source).use { require(oriented.compress(Bitmap.CompressFormat.JPEG, Metrics.wallpaperQuality, it)) } }
        finally { oriented.recycle() }
        true
    }.getOrDefault(false)

    fun preview(): Bitmap? = runCatching { BitmapFactory.decodeFile(source.path) }.getOrNull()
    fun remove() { runCatching { file.delete(); source.delete() } }
    fun cancel() { runCatching { source.delete() } }
    fun save(bitmap: Bitmap, aspect: Float, zoom: Float, panX: Float, panY: Float): Boolean = runCatching {
        val crop = WallpaperImageRules.crop(bitmap.width, bitmap.height, aspect, zoom, panX, panY)
        val (w, h) = WallpaperImageRules.output(crop.width, crop.height)
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        try {
            val matrix = Matrix().apply {
                setRectToRect(RectF(crop.left, crop.top, crop.left + crop.width, crop.top + crop.height),
                    RectF(0f, 0f, w.toFloat(), h.toFloat()), Matrix.ScaleToFit.FILL)
            }
            Canvas(result).drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            val temp = File(directory, "pending.jpg")
            FileOutputStream(temp).use { require(result.compress(Bitmap.CompressFormat.JPEG, Metrics.wallpaperQuality, it)) }
            require(WallpaperImageRules.acceptsBytes(temp.length()))
            require(temp.renameTo(file))
            source.delete()
        } finally { result.recycle() }
        true
    }.getOrDefault(false)
}

/** Fixed 8×8 sample, taken only when accepting a crop. */
internal fun wallpaperBrightness(bitmap: Bitmap, aspect: Float, zoom: Float, panX: Float, panY: Float): Double {
    val crop = WallpaperImageRules.crop(bitmap.width, bitmap.height, aspect, zoom, panX, panY)
    var sum = 0.0
    for (y in 0 until 8) for (x in 0 until 8)
        sum += WallpaperReadability.luminance(bitmap.getPixel((crop.left + x * (crop.width - 1).coerceAtLeast(0f) / 7).toInt().coerceIn(0, bitmap.width - 1), (crop.top + y * (crop.height - 1).coerceAtLeast(0f) / 7).toInt().coerceIn(0, bitmap.height - 1)))
    return sum / 64
}
