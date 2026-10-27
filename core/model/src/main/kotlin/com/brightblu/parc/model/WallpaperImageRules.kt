package com.brightblu.parc.model

import kotlin.math.*

/** Pure crop coordinates in source pixels; pan is a fraction of the available overflow. */
object WallpaperImageRules {
    data class Crop(val left: Float, val top: Float, val width: Float, val height: Float)
    fun crop(width: Int, height: Int, aspect: Float, zoom: Float, panX: Float, panY: Float): Crop {
        require(width > 0 && height > 0 && aspect.isFinite() && aspect > 0)
        val z = if (zoom.isFinite()) zoom.coerceIn(1f, Metrics.wallpaperMaxZoom) else 1f
        val w = min(width.toFloat(), height * aspect) / z
        val h = (w / aspect).coerceAtMost(height.toFloat())
        fun offset(p: Float) = if (p.isFinite()) p.coerceIn(-1f, 1f) else 0f
        return Crop((width - w).coerceAtLeast(0f) * (1 + offset(panX)) / 2, (height - h).coerceAtLeast(0f) * (1 + offset(panY)) / 2, w, h)
    }
    fun output(width: Float, height: Float): Pair<Int, Int> {
        require(width > 0 && height > 0 && width.isFinite() && height.isFinite())
        val scale = min(1f, Metrics.wallpaperMaxSide / max(width, height))
        return max(1, floor(width * scale).toInt()) to max(1, floor(height * scale).toInt())
    }
    fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (max(width, height) / sample > Metrics.wallpaperMaxSide * 2) sample *= 2
        return sample
    }
    fun acceptsBytes(size: Long) = size in 1..Metrics.wallpaperMaxBytes.toLong()
    fun includeInBackup(enabled: Boolean, size: Long) = enabled && acceptsBytes(size)
    fun effectiveStyle(requested: WallpaperStyle, readable: Boolean) =
        if (requested == WallpaperStyle.Image && !readable) WallpaperStyle.Black else requested
}
