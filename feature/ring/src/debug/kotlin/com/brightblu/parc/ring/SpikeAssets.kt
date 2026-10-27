package com.brightblu.parc.ring

import android.graphics.*
import com.brightblu.parc.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Debug-only bitmaps; constructed once off main and retained for the screen lifetime. */
class SpikeAssets(val home: Array<Bitmap>, val folder: Array<Bitmap>, val glow: Bitmap) {
    companion object {
        suspend fun create(skin: Skin, count: Int): SpikeAssets = withContext(Dispatchers.Default) {
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            val palette = intArrayOf(skin.colors.accent.toInt(), skin.colors.pulse.toInt(),
                skin.colors.textSecondary.toInt(), skin.colors.lineActive.toInt())
            fun icons(folder: Boolean): Array<Bitmap> = Array(count) { index ->
                val bitmap = Bitmap.createBitmap(SpikeConfig.bitmapPx, SpikeConfig.bitmapPx,
                    Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                val half = SpikeConfig.bitmapPx / 2f
                paint.color = palette[(index + if (folder) 2 else 0) % palette.size]
                canvas.drawCircle(half, half, half, paint)
                paint.color = skin.colors.bg.toInt()
                paint.typeface = Typeface.create(skin.typography.fallbackFamily, Typeface.NORMAL)
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = SpikeConfig.bitmapPx * SpikeConfig.glyphFraction
                val letter = (if (folder) 'a' else 'A') + index
                canvas.drawText(letter.toString(), half,
                    half - (paint.ascent() + paint.descent()) / 2f, paint)
                bitmap
            }
            val glow = Bitmap.createBitmap(SpikeConfig.bitmapPx, SpikeConfig.bitmapPx,
                Bitmap.Config.ARGB_8888)
            val half = SpikeConfig.bitmapPx / 2f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(half, half, half, skin.colors.glow.toInt(),
                    skin.colors.glow.toInt() and 0x00ffffff, Shader.TileMode.CLAMP)
            }
            Canvas(glow).drawCircle(half, half, half, paint)
            SpikeAssets(icons(false), icons(true), glow)
        }
    }
}
