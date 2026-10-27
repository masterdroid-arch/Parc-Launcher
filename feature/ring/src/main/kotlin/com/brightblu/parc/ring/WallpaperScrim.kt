package com.brightblu.parc.ring

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.brightblu.parc.model.*

internal fun screenHeightKey(height: Float, density: Float) = (height * density).toInt()

/** No pixel access or storage permission. Listener and initial query run only while needed. */
@Composable
internal fun rememberWallpaperLuminance(enabled: Boolean): Double? {
    val context = LocalContext.current
    var estimate by remember { mutableStateOf<Double?>(null) }
    DisposableEffect(context, enabled) {
        val manager = WallpaperManager.getInstance(context)
        fun update(colors: WallpaperColors?) {
            estimate = colors?.let {
                WallpaperReadability.estimate(listOfNotNull(it.primaryColor, it.secondaryColor, it.tertiaryColor).map { c -> c.toArgb() },
                    it.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0)
            }
        }
        val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) update(colors)
        }
        if (enabled) {
            update(runCatching { manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull())
            manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        }
        onDispose { if (enabled) manager.removeOnColorsChangedListener(listener) }
    }
    return estimate
}

/** Cached gradient paint; draw uses only a preallocated Paint and primitive rectangle. */
internal fun wallpaperScrim(skin: Skin, look: LookSettings, estimate: Double?, height: Int): Paint? {
    if (look.wallpaper != WallpaperStyle.System || !look.wallpaperReadability) return null
    val surface = skin.colors.bg.toInt()
    val uncertain = estimate == null || estimate >= .5
    val topAlpha = if (uncertain) Metrics.wallpaperTopScrim else Metrics.wallpaperTopScrim * Metrics.wallpaperKnownScrimScale
    fun tint(alpha: Float) = ((alpha * 255).toInt() shl 24) or (surface and 0xffffff)
    val gradient = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, height.coerceAtLeast(1).toFloat(),
            intArrayOf(tint(topAlpha), tint(Metrics.wallpaperArcScrim)), null, Shader.TileMode.CLAMP)
    }
    val strip = Bitmap.createBitmap(1, height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    Canvas(strip).drawRect(0f, 0f, 1f, strip.height.toFloat(), gradient)
    return Paint().apply { shader = BitmapShader(strip, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
}
