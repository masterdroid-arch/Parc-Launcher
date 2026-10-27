package com.brightblu.parc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.asImageBitmap
import com.brightblu.parc.model.*

internal val LocalHubLook = compositionLocalOf { LookSettings() }

/** Rasterized once per size, skin or preference change, never inside the frame callback. */
internal fun Modifier.hubSurface(skin: Skin, look: LookSettings): Modifier = drawWithCache {
    val surface = skin.colors.surface.toInt()
    val alpha = if (look.wallpaperReadability) WallpaperReadability.safeAlpha(surface,
        listOf(skin.colors.textPrimary.toInt(), skin.colors.textSecondary.toInt(), skin.colors.accent.toInt()),
        look.panelOpacityPercent / 100f) else look.panelOpacityPercent / 100f
    fun tint(a: Float) = ((a * 255).toInt() shl 24) or (surface and 0xffffff)
    val bitmap = Bitmap.createBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = skin.shape.surfaceCornerDp * density
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, 0f, bitmap.height.toFloat(), tint((alpha + Metrics.panelGradientLift).coerceAtMost(1f)), tint(alpha), Shader.TileMode.CLAMP)
    }
    canvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, paint)
    paint.shader = null
    paint.color = skin.colors.lineActive.toInt()
    paint.alpha = (Metrics.panelBorderAlpha * 255).toInt()
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = skin.shape.surfaceBorderDp * density
    val edge = paint.strokeWidth / 2f
    canvas.drawRoundRect(edge, edge, size.width - edge, size.height - edge, radius, radius, paint)
    val image = bitmap.asImageBitmap()
    onDrawBehind { drawImage(image) }
}
