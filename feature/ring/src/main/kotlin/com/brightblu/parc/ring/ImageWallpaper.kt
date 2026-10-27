package com.brightblu.parc.ring

import android.graphics.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.brightblu.parc.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Decodes and rasterises once per size, image revision, skin or dim/scrim change. */
@Composable
internal fun rememberImageWallpaper(look: LookSettings, skin: Skin, width: Int, height: Int): Bitmap? {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, look, skin, width to height) {
        value = null
        if (look.wallpaper == WallpaperStyle.Image) value = withContext(Dispatchers.IO) {
            loadImageWallpaper(File(context.filesDir, "wallpaper/home.jpg"), look, skin, width, height)
        }
    }
    return bitmap
}

/** Shared static preparation for home and cached skin cards. Call on a worker dispatcher. */
fun loadImageWallpaper(file: File, look: LookSettings, skin: Skin, width: Int, height: Int): Bitmap? {
    if (look.wallpaper != WallpaperStyle.Image) return null
    return runCatching {
        require(WallpaperImageRules.acceptsBytes(file.length()))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && maxOf(bounds.outWidth, bounds.outHeight) <= Metrics.wallpaperMaxSide)
        val image = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = WallpaperImageRules.sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }) ?: return@runCatching null
        val (w, h) = WallpaperImageRules.output(width.toFloat(), height.toFloat())
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val canvas = Canvas(output)
        val crop = WallpaperImageRules.crop(image.width, image.height, width.toFloat() / height, 1f, 0f, 0f)
        val matrix = Matrix().apply { setRectToRect(RectF(crop.left, crop.top, crop.left + crop.width, crop.top + crop.height), RectF(0f, 0f, w.toFloat(), h.toFloat()), Matrix.ScaleToFit.FILL) }
        canvas.drawBitmap(image, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        image.recycle()
        canvas.drawColor((look.wallpaperDimPercent * 255 / 100) shl 24)
        if (look.imageReadabilityScrim) {
            val surface = skin.colors.bg.toInt()
            val alpha = WallpaperReadability.safeAlpha(surface, listOf(skin.colors.textPrimary.toInt()), Metrics.wallpaperArcScrim)
            fun color(a: Float) = ((a * 255).toInt() shl 24) or (surface and 0xffffff)
            val paint = Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), color(maxOf(alpha, Metrics.wallpaperTopScrim)), color(alpha), Shader.TileMode.CLAMP) }
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }
        output
    }.getOrNull()
}
