package com.brightblu.parc.ring

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.WallpaperImageRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * T169: the system wallpaper's pixels for the frost, if this app may read them. No permission is added; when the platform
 * refuses (or there is no static picture) this returns null and the cards frost the haze stage instead. The wallpaper
 * itself stays visible either way, because the window shows it. Re-read when [revision] changes (the colours listener fires).
 */
@Composable
internal fun rememberSystemWallpaperPixels(enabled: Boolean, revision: Any?, width: Int, height: Int): Bitmap? {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, enabled, revision, width to height) {
        value = if (enabled) withContext(Dispatchers.IO) { readSystemWallpaper(context, width, height) } else null
    }
    return bitmap
}

/** Centre-cropped to the screen's shape and drawn at a quarter of its size (the frost blurs it anyway). */
internal fun readSystemWallpaper(context: Context, width: Int, height: Int): Bitmap? = runCatching {
    val source = (WallpaperManager.getInstance(context).peekDrawable() as? BitmapDrawable)?.bitmap ?: return@runCatching null
    if (source.width <= 0 || source.height <= 0 || width <= 0 || height <= 0) return@runCatching null
    val w = (width / Metrics.glassSystemStageScale).coerceAtLeast(1)
    val h = (height / Metrics.glassSystemStageScale).coerceAtLeast(1)
    val crop = WallpaperImageRules.crop(source.width, source.height, width.toFloat() / height, 1f, 0f, 0f)
    val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val matrix = Matrix().apply {
        setRectToRect(RectF(crop.left, crop.top, crop.left + crop.width, crop.top + crop.height), RectF(0f, 0f, w.toFloat(), h.toFloat()), Matrix.ScaleToFit.FILL)
    }
    Canvas(output).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
    output
}.getOrNull()

/** T176: the phone wallpaper's main colour (no permission needed), read only when the pixels are not readable. */
@Composable
internal fun rememberSystemWallpaperColour(enabled: Boolean): Int? {
    val context = LocalContext.current
    val colour by produceState<Int?>(null, enabled) {
        value = if (!enabled) null else withContext(Dispatchers.IO) {
            runCatching { WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb() }.getOrNull()
        }
    }
    return colour
}

/**
 * T176: the stage the glass frosts for the phone wallpaper, with Home's [dim] (a translucent colour) laid over it: the real
 * pixels when readable, else a small flat bitmap of the wallpaper's main [colour]. Null when neither is known (haze).
 */
internal fun systemWallpaperStage(pixels: Bitmap?, colour: Int?, dim: Int): Bitmap? {
    val base = pixels?.copy(Bitmap.Config.ARGB_8888, true)
        ?: colour?.let { Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(it or (0xFF shl 24)) } }
        ?: return null
    Canvas(base).drawColor(dim)
    return base
}
