package com.brightblu.parc.ring.module

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.brightblu.parc.model.GlassBackdrop
import com.brightblu.parc.model.GlassRefraction
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ModuleStyle
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * T153 (DESIGN 2.1, Glass): pure pixel maths for the frost. Works on plain arrays so it is testable without Android.
 * Edges are clamped, so a constant image stays constant and an impulse keeps its sum.
 */
object FrostMath {
    /** One separable box-blur pass over one channel; `radius` pixels each side, clamped at the edges. */
    fun boxPass(src: FloatArray, w: Int, h: Int, radius: Int, horizontal: Boolean, dst: FloatArray = FloatArray(src.size)): FloatArray {
        require(src.size == w * h && dst.size == src.size)
        if (radius <= 0 || w == 0 || h == 0) { src.copyInto(dst); return dst }
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val stride = if (horizontal) 1 else w
        val lineStep = if (horizontal) w else 1
        val window = (2 * radius + 1).toFloat()
        for (line in 0 until lines) {
            val base = line * lineStep
            var sum = 0f
            for (k in -radius..radius) sum += src[base + k.coerceIn(0, len - 1) * stride]
            for (i in 0 until len) {
                dst[base + i * stride] = sum / window
                sum += src[base + (i + radius + 1).coerceAtMost(len - 1) * stride]
                sum -= src[base + (i - radius).coerceAtLeast(0) * stride]
            }
        }
        return dst
    }

    /** `passes` rounds of horizontal then vertical box blur (three passes approximate a Gaussian). */
    fun blur(channel: FloatArray, w: Int, h: Int, radius: Int, passes: Int): FloatArray {
        var a = channel.copyOf()
        var b = FloatArray(channel.size)
        repeat(passes) {
            boxPass(a, w, h, radius, true, b)
            boxPass(b, w, h, radius, false, a)
        }
        return a
    }

    /** Blurs packed ARGB pixels, channel by channel; alpha is blurred too. */
    fun blurArgb(pixels: IntArray, w: Int, h: Int, radius: Int, passes: Int): IntArray {
        val out = IntArray(pixels.size)
        val channel = FloatArray(pixels.size)
        val blurred = Array(4) { FloatArray(0) }
        val opaque = pixels.all { it ushr 24 == 0xFF }
        for (c in 0 until 4) {
            val shift = 24 - 8 * c
            if (c == 0 && opaque) { blurred[0] = FloatArray(pixels.size) { 255f }; continue } // an opaque stage stays opaque
            for (i in pixels.indices) channel[i] = ((pixels[i] ushr shift) and 0xFF).toFloat()
            blurred[c] = blur(channel, w, h, radius, passes)
        }
        for (i in pixels.indices) {
            var p = 0
            for (c in 0 until 4) p = p or (blurred[c][i].plus(0.5f).toInt().coerceIn(0, 255) shl (24 - 8 * c))
            out[i] = p
        }
        return out
    }
}

/** T173: the lens edge on the small blurred picture; see [GlassRefraction] for the map. Plain arrays, no Android types. */
object FrostLens {
    /**
     * Returns a copy of [pixels] ([w] x [h], packed ARGB) in which the pixels within [band] of the rounded edge ([corner] radius) take
     * their colour, bilinear, from where [GlassRefraction.source] says. Everything further in is copied unchanged.
     */
    fun apply(pixels: IntArray, w: Int, h: Int, corner: Int, band: Int): IntArray {
        require(pixels.size == w * h)
        val out = pixels.copyOf()
        if (band <= 0 || w < 2 || h < 2) return out
        val reach = maxOf(band, corner)
        val p = FloatArray(2)
        for (y in 0 until h) {
            val bandRow = y < band || y >= h - band
            val cornerRow = y < reach || y >= h - reach
            val inner = if (bandRow) 0 else if (cornerRow) reach else band
            var x = 0
            while (x < w) {
                if (!bandRow && x == inner) x = maxOf(inner, w - inner)
                if (x >= w) break
                GlassRefraction.source(x + 0.5f, y + 0.5f, w.toFloat(), h.toFloat(), corner.toFloat(), band, p)
                if (p[0] != x + 0.5f || p[1] != y + 0.5f) out[y * w + x] = bilinear(pixels, w, h, p[0] - 0.5f, p[1] - 0.5f)
                x++
            }
        }
        return out
    }

    private fun bilinear(px: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val x0 = x.toInt().coerceIn(0, w - 1); val y0 = y.toInt().coerceIn(0, h - 1) // the source is never left of or above the pixel's own row/column
        val fx = (x - x0).coerceIn(0f, 1f); val fy = (y - y0).coerceIn(0f, 1f)
        val x1 = (x0 + 1).coerceAtMost(w - 1); val y1 = (y0 + 1).coerceAtMost(h - 1)
        val c00 = px[y0 * w + x0]; val c10 = px[y0 * w + x1]; val c01 = px[y1 * w + x0]; val c11 = px[y1 * w + x1]
        return mix(c00, c10, c01, c11, fx, fy, 24) or mix(c00, c10, c01, c11, fx, fy, 16) or
            mix(c00, c10, c01, c11, fx, fy, 8) or mix(c00, c10, c01, c11, fx, fy, 0)
    }

    private fun mix(c00: Int, c10: Int, c01: Int, c11: Int, fx: Float, fy: Float, shift: Int): Int {
        val top = ((c00 ushr shift) and 0xFF) * (1 - fx) + ((c10 ushr shift) and 0xFF) * fx
        val bottom = ((c01 ushr shift) and 0xFF) * (1 - fx) + ((c11 ushr shift) and 0xFF) * fx
        return ((top * (1 - fy) + bottom * fy + 0.5f).toInt().coerceIn(0, 255)) shl shift
    }
}

/** Everything that changes the picture, and nothing else. Used as the cache key. */
data class FrostKey(
    val widthPx: Int, val heightPx: Int,
    val cornerPx: Int, val roundBottom: Boolean,
    val tint: Int, val bg: Int, val haze: Int,
    val stageHash: Int,
    val edgePx: Int = 0, val ink: Int = 0,
    val userTint: Boolean = false,
    /** T173: width in px of the lens band along the edge (0: plain frost, no lens, no inner shadow). */
    val refractPx: Int = 0,
)

/** What to frost: a rectangle (px) of a screen-sized stage, and how to dress it. */
data class FrostRequest(
    val screenWidthPx: Int, val screenHeightPx: Int,
    val leftPx: Int, val topPx: Int, val widthPx: Int, val heightPx: Int,
    val cornerPx: Int, val roundBottom: Boolean,
    val tint: Int, val bg: Int, val haze: Int,
    /** T169: 0 frosts the haze stage; otherwise the identity of the wallpaper picture that is passed to [FrostRenderer.render]. */
    val stageId: Int = 0,
    /** T169: width of the light edge highlight in px (0 draws none), and the ink that has to stay readable on the card. */
    val edgePx: Int = 0, val ink: Int = 0,
    /** T171: the alpha of [tint] is final (the user's opacity, already raised for readability against the mean backdrop, T172); the renderer never changes it. */
    val userTint: Boolean = false,
    /** T173: width in px of the lens band along the edge; 0 keeps the plain frost. Part of the cache key. */
    val refractPx: Int = 0,
) {
    /** The stage hash covers the stage picture (haze: screen size and colours; wallpaper: its identity) and which part of it sits behind the surface. */
    val stageHash: Int get() = (if (stageId != 0) (stageId * 31 + screenWidthPx) * 31 + screenHeightPx
        else HazeStage.hash(screenWidthPx, screenHeightPx, bg, haze)) * 31 + leftPx * 17 + topPx
    val key: FrostKey get() = FrostKey(widthPx, heightPx, cornerPx, roundBottom, tint, bg, haze, stageHash, edgePx, if (stageId != 0) ink else 0, userTint, refractPx)

    companion object {
        fun edgeFor(density: Float) = (Metrics.glassEdgeDp * density + 0.5f).toInt().coerceAtLeast(1)
        /** The lens band for a hub card when "Glass refraction" is on, else 0. */
        fun refractFor(density: Float, on: Boolean) = if (on) GlassRefraction.bandPx(density) else 0

        fun of(skin: Skin, screenWidthPx: Int, screenHeightPx: Int, left: Int, top: Int, width: Int, height: Int,
            cornerPx: Int, roundBottom: Boolean = true, stageId: Int = 0, edgePx: Int = 0, ink: Int = skin.colors.textPrimary.toInt(),
            userTint: Boolean = false, refractPx: Int = 0) =
            FrostRequest(screenWidthPx, screenHeightPx, left, top, width, height, cornerPx, roundBottom,
                skin.colors.moduleSurface.toInt(), skin.colors.bg.toInt(), skin.colors.lineActive.toInt(), stageId, edgePx, ink, userTint, refractPx)
    }
}

private const val MovedPx = 48f

/** Small thread-safe LRU. */
class FrostCache<V>(private val capacity: Int) {
    private val map = object : LinkedHashMap<FrostKey, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FrostKey, V>?) = size > capacity
    }
    @Synchronized fun get(key: FrostKey): V? = map[key]
    @Synchronized fun put(key: FrostKey, value: V) { map[key] = value }
    @Synchronized fun size() = map.size
}

/**
 * The Glass stage: `bg` plus soft blobs of `lineActive` at 12 to 18 %. Generated once per screen size and colours,
 * at a quarter of the screen size (the blobs are soft, so the scale-up shows nothing), and cached.
 */
object HazeStage {
    /** x, y, radius (fractions of the screen width / height / long side) and alpha. */
    private val blobs = arrayOf(
        floatArrayOf(0.15f, 0.12f, 0.55f, 0.16f), floatArrayOf(0.85f, 0.22f, 0.45f, 0.13f),
        floatArrayOf(0.40f, 0.45f, 0.50f, 0.12f), floatArrayOf(0.90f, 0.62f, 0.55f, 0.18f),
        floatArrayOf(0.20f, 0.78f, 0.50f, 0.15f), floatArrayOf(0.65f, 0.92f, 0.45f, 0.14f),
    )
    private const val VERSION = 1
    private val cache = object : LinkedHashMap<Int, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?) = size > 2
    }

    fun hash(w: Int, h: Int, bg: Int, haze: Int): Int = ((w * 31 + h) * 31 + bg) * 31 + haze + VERSION

    @Synchronized
    fun bitmap(w: Int, h: Int, bg: Int, haze: Int): Bitmap = cache.getOrPut(hash(w, h, bg, haze)) {
        val sw = (w / Metrics.frostHazeScale).coerceAtLeast(1)
        val sh = (h / Metrics.frostHazeScale).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(bg or (0xFF shl 24))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rgb = haze and 0xFFFFFF
        for (blob in blobs) {
            val alpha = blob[3].coerceIn(Metrics.frostHazeAlphaMin, Metrics.frostHazeAlphaMax)
            val radius = blob[2] * maxOf(sw, sh)
            paint.shader = RadialGradient(blob[0] * sw, blob[1] * sh, radius,
                ((alpha * 255f + 0.5f).toInt() shl 24) or rgb, rgb, Shader.TileMode.CLAMP)
            canvas.drawCircle(blob[0] * sw, blob[1] * sh, radius, paint)
        }
        bitmap
    }
}

/** The pre-rendered noise tile of the glass grain (T169): built once, drawn as a repeating shader while a frost is rendered. */
object GlassGrain {
    private var tile: Bitmap? = null
    @Synchronized fun bitmap(): Bitmap = tile ?: Bitmap.createBitmap(GlassBackdrop.grainTile(), Metrics.glassGrainTilePx,
        Metrics.glassGrainTilePx, Bitmap.Config.ARGB_8888).also { tile = it }
}

/**
 * Takes the stage behind a surface, shrinks it to one eighth, blurs it with three box passes on the small bitmap,
 * scales it back, tints it, adds the grain and the light edge, and rounds the corners. No live blur of any kind.
 * Call [render] only off the main thread.
 */
object FrostRenderer {
    private val cache = FrostCache<Bitmap>(Metrics.frostCacheEntries)

    fun cached(request: FrostRequest): Bitmap? = cache.get(request.key)

    /** Halving steps keep the shrink a plain average, so thin stripes in a wallpaper do not alias. */
    private fun shrink(source: Bitmap, w: Int, h: Int): Bitmap {
        var current = source
        while (current.width / 2 >= w && current.height / 2 >= h) {
            val next = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
            if (current !== source) current.recycle()
            current = next
        }
        val last = Bitmap.createScaledBitmap(current, w, h, true)
        if (current !== source && current !== last) current.recycle()
        return last
    }

    /** [stage] is the wallpaper picture when `request.stageId != 0`; without it the haze stage is frosted instead. */
    fun render(request: FrostRequest, stage: Bitmap? = null, log: Boolean = false): Bitmap {
        val wallpaper = request.stageId != 0 && stage != null
        val req = if (request.stageId != 0 && !wallpaper) request.copy(stageId = 0, ink = 0) else request
        cache.get(req.key)?.let { return it }
        val start = SystemClock.elapsedRealtimeNanos()
        val picture = if (wallpaper) stage!! else HazeStage.bitmap(req.screenWidthPx, req.screenHeightPx, req.bg, req.haze)
        val sx = picture.width / req.screenWidthPx.toFloat()
        val sy = picture.height / req.screenHeightPx.toFloat()
        val cropLeft = (req.leftPx * sx).toInt().coerceIn(0, picture.width - 1)
        val cropTop = (req.topPx * sy).toInt().coerceIn(0, picture.height - 1)
        val cropW = (req.widthPx * sx).toInt().coerceIn(1, picture.width - cropLeft)
        val cropH = (req.heightPx * sy).toInt().coerceIn(1, picture.height - cropTop)
        val smallW = (req.widthPx / Metrics.frostDownscale).coerceAtLeast(1)
        val smallH = (req.heightPx / Metrics.frostDownscale).coerceAtLeast(1)
        val tStage = SystemClock.elapsedRealtimeNanos()
        val crop = Bitmap.createBitmap(picture, cropLeft, cropTop, cropW, cropH)
        val scaled = shrink(crop, smallW, smallH)
        val small = scaled.copy(Bitmap.Config.ARGB_8888, true)
        if (scaled !== crop) scaled.recycle()
        val pixels = IntArray(smallW * smallH)
        small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH)
        val tShrink = SystemClock.elapsedRealtimeNanos()
        val blurred = FrostMath.blurArgb(pixels, smallW, smallH, Metrics.frostBlurRadiusPx, Metrics.frostBlurPasses)
        // T173: the lens is applied to the small blurred picture (one eighth the size, so a few thousand pixels), then scaled up with the frost.
        val tLens = SystemClock.elapsedRealtimeNanos()
        val shown = if (req.refractPx > 0) FrostLens.apply(blurred, smallW, smallH, req.cornerPx / Metrics.frostDownscale,
            req.refractPx / Metrics.frostDownscale) else blurred
        val lensMs = (SystemClock.elapsedRealtimeNanos() - tLens) / 1_000_000.0
        small.setPixels(shown, 0, smallW, 0, 0, smallW, smallH)
        // Menus and sheets (not the hubs): over a real wallpaper the tint starts at 70 percent and rises as far as the ink needs 7:1 over the darkest and lightest blurred pixel.
        var tint = req.tint
        if (wallpaper && req.ink != 0 && !req.userTint) {
            var low = blurred[0]; var high = blurred[0]
            var lowLuma = Int.MAX_VALUE; var highLuma = -1
            for (p in blurred) {
                val luma = ((p shr 16) and 0xFF) * 54 + ((p shr 8) and 0xFF) * 183 + (p and 0xFF) * 19
                if (luma < lowLuma) { lowLuma = luma; low = p }
                if (luma > highLuma) { highLuma = luma; high = p }
            }
            val alpha = GlassBackdrop.tintAlpha(req.tint or (0xFF shl 24), req.ink or (0xFF shl 24), low or (0xFF shl 24), high or (0xFF shl 24))
            tint = (req.tint and 0xFFFFFF) or (alpha shl 24)
        }

        val tBlur = SystemClock.elapsedRealtimeNanos()
        val out = Bitmap.createBitmap(req.widthPx, req.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val r = req.cornerPx.toFloat()
        val radii = floatArrayOf(r, r, r, r, if (req.roundBottom) r else 0f, if (req.roundBottom) r else 0f,
            if (req.roundBottom) r else 0f, if (req.roundBottom) r else 0f)
        val shape = Path().apply { addRoundRect(RectF(0f, 0f, out.width.toFloat(), out.height.toFloat()), radii, Path.Direction.CW) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // The small bitmap is scaled back up by the shader (bilinear) while drawing once into the output.
        paint.isFilterBitmap = true
        paint.shader = android.graphics.BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setScale(req.widthPx / smallW.toFloat(), req.heightPx / smallH.toFloat()) })
        }
        canvas.drawPath(shape, paint)
        paint.shader = null
        paint.color = tint
        canvas.drawPath(shape, paint)
        if (req.edgePx > 0) {
            // T169: fine grain from the pre-rendered noise tile, then a light edge so the card reads as glass.
            paint.shader = android.graphics.BitmapShader(GlassGrain.bitmap(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            canvas.drawPath(shape, paint)
            paint.shader = null
            val inset = req.edgePx / 2f
            val edgeRadii = FloatArray(8) { (radii[it] - inset).coerceAtLeast(0f) }
            val edge = Path().apply {
                addRoundRect(RectF(inset, inset, out.width - inset, out.height - inset), edgeRadii, Path.Direction.CW)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = req.edgePx.toFloat()
            paint.color = ((Metrics.glassEdgeAlpha * 255f + 0.5f).toInt() shl 24) or 0xFFFFFF
            canvas.drawPath(edge, paint)
            if (req.refractPx > 0) {
                // T173: a slightly darker line just inside the bright rim, so the edge reads as thick glass.
                val width = GlassRefraction.innerShadowPx(req.refractPx).toFloat()
                val shadowInset = req.edgePx + width / 2f
                val shadowRadii = FloatArray(8) { (radii[it] - shadowInset).coerceAtLeast(0f) }
                val shadow = Path().apply {
                    addRoundRect(RectF(shadowInset, shadowInset, out.width - shadowInset, out.height - shadowInset), shadowRadii, Path.Direction.CW)
                }
                paint.strokeWidth = width
                paint.color = (Math.round(Metrics.glassInnerShadowAlpha * 255f) shl 24)
                canvas.drawPath(shadow, paint)
            }
        }
        if (crop !== picture) crop.recycle()
        small.recycle()
        cache.put(req.key, out)
        if (log) {
            val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            Log.i("Parc", "T169 frost render ${req.widthPx}x${req.heightPx} at ${req.leftPx},${req.topPx} wallpaper=$wallpaper tintAlpha=${tint ushr 24} lens=${"%.1f".format(lensMs)} ms=${"%.1f".format(ms)} stage=${(tStage - start) / 1_000_000} shrink=${(tShrink - tStage) / 1_000_000} blur=${(tBlur - tShrink) / 1_000_000}")
        }
        return out
    }
}

/** Frosted bitmap for a request, or null while it renders (the caller shows the plain tint meanwhile). Swap only, no animation. */
@Composable
fun rememberFrosted(request: FrostRequest?, stage: Bitmap? = null): State<Bitmap?> {
    val state = remember { mutableStateOf(request?.let { FrostRenderer.cached(it) }) }
    val log = (LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    LaunchedEffect(request) {
        if (request == null) { state.value = null; return@LaunchedEffect }
        // The previous picture stays until the new one is ready (a swap, never a blank frame).
        state.value = FrostRenderer.cached(request) ?: withContext(Dispatchers.Default) { FrostRenderer.render(request, stage, log) }
    }
    return state
}

/** T173: "Glass refraction" is on. Provided by Home; read by the hub cards only (menus and sheets never refract). */
val LocalGlassRefraction = androidx.compose.runtime.compositionLocalOf { false }

/** T169: the wallpaper picture the frost is taken from, published by the home canvas while glass is on and the pixels are readable. */
class GlassWallpaper(val id: Int, val bitmap: Bitmap)

/** Read by every frosted surface; null means "frost the haze stage". Set only from the home canvas. */
object GlassWallpaperStage {
    var current by mutableStateOf<GlassWallpaper?>(null)
}

/**
 * Frosted background for a Compose surface (stats card, menus, sheets). Only skins with `shape.moduleStyle == frosted`
 * get the frost; for every other skin this returns the modifier unchanged. Drawing is one `drawImage`, or the plain tint rectangle while rendering.
 */
fun Modifier.frostedBackground(skin: Skin, cornerDp: Float, roundBottom: Boolean = true, userTint: Boolean = false,
    hubCard: Boolean = false): Modifier =
    if (!FrostPath.frosted(skin)) this else composed {
        val density = LocalDensity.current.density
        val config = LocalConfiguration.current
        var bounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        val wallpaper = GlassWallpaperStage.current
        val refract = hubCard && LocalGlassRefraction.current
        val request = bounds?.takeIf { it.width >= 1f && it.height >= 1f }?.let {
            FrostRequest.of(skin, (config.screenWidthDp * density).toInt(), (config.screenHeightDp * density).toInt(),
                it.left.toInt(), it.top.toInt(), it.width.toInt(), it.height.toInt(), (cornerDp * density).toInt(), roundBottom,
                stageId = wallpaper?.id ?: 0, edgePx = FrostRequest.edgeFor(density),
                ink = if (userTint) 0 else skin.colors.textPrimary.toInt(), userTint = userTint,
                refractPx = FrostRequest.refractFor(density, refract))
        }
        val frosted by rememberFrosted(request, wallpaper?.bitmap)
        val flat = remember(skin.colors.moduleSurface) { androidx.compose.ui.graphics.Color(skin.colors.moduleSurface.toInt()) }
        val image = remember(frosted) { frosted?.asImageBitmap() }
        this.onGloballyPositioned {
            // Lean and spring offsets move the surface a few dp; only a real move or resize asks for a new frost.
            val next = it.boundsInWindow()
            val old = bounds
            if (old == null || kotlin.math.abs(next.width - old.width) >= 1f || kotlin.math.abs(next.height - old.height) >= 1f ||
                kotlin.math.abs(next.left - old.left) > MovedPx || kotlin.math.abs(next.top - old.top) > MovedPx) bounds = next
        }.drawBehind {
            if (image != null) drawImage(image, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())) else drawRoundRect(flat, cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerDp * density))
        }
    }

/**
 * T173: the one decision "does this card take the frost?". The arc card, the stats card, the previews and the menus all ask it, so
 * the arc hub frosts in exactly the skins and modes where the stats hub does: whenever the card skin says glass is on.
 */
object FrostPath {
    fun frosted(skin: Skin) = skin.shape.moduleStyle == ModuleStyle.frosted
}

/**
 * T176: THE drawing path of a hub card, the arc hub and the stats hub alike, in every skin, on Home and in every preview.
 * [cardSkin] is the card's own skin from `HubCards` (:app): its `moduleSurface` alpha is final (the user's opacity, raised for
 * "Keep text readable") and its `moduleStyle` says glass on (`frosted`) or off. Glass on: the frost of the stage behind the
 * card (the chosen picture, the phone wallpaper stage, or the haze), tinted by `moduleSurface`, grain, edge highlight and,
 * with [refract], the lens rim. Glass off: one flat rounded card in `moduleSurface`. No skin and no hub has its own copy.
 */
object HubCardPaint {
    fun frosted(cardSkin: Skin) = FrostPath.frosted(cardSkin)

    /** The frost to render for a card at this place, or null for a flat card. All sizes in px. */
    fun request(cardSkin: Skin, screenWidthPx: Int, screenHeightPx: Int, leftPx: Int, topPx: Int, widthPx: Int, heightPx: Int,
        density: Float, stageId: Int, refract: Boolean): FrostRequest? = if (!frosted(cardSkin)) null else
        FrostRequest.of(cardSkin, screenWidthPx, screenHeightPx, leftPx, topPx, widthPx, heightPx,
            (cardSkin.shape.moduleCornerDp * density).toInt(), true, stageId = stageId, edgePx = FrostRequest.edgeFor(density),
            ink = 0, userTint = true, refractPx = FrostRequest.refractFor(density, refract))

    /** The flat card: a rounded rectangle in `moduleSurface`. */
    fun flat(cardSkin: Skin, widthPx: Int, heightPx: Int, density: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cardSkin.colors.moduleSurface.toInt(); style = Paint.Style.FILL }
        val corner = cardSkin.shape.moduleCornerDp * density
        Canvas(bitmap).drawRoundRect(RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), corner, corner, paint)
        return bitmap
    }

    /** Frost or flat, synchronously. Call off the main thread. */
    fun render(cardSkin: Skin, screenWidthPx: Int, screenHeightPx: Int, leftPx: Int, topPx: Int, widthPx: Int, heightPx: Int,
        density: Float, stage: Bitmap?, refract: Boolean): Bitmap {
        val id = stage?.let { s -> GlassBackdrop.identity(s.width, s.height) { x, y -> s.getPixel(x, y) } } ?: 0
        return request(cardSkin, screenWidthPx, screenHeightPx, leftPx, topPx, widthPx, heightPx, density, id, refract)
            ?.let { FrostRenderer.render(it, stage) } ?: flat(cardSkin, widthPx, heightPx, density)
    }
}

/**
 * T176: a Compose surface drawn by [HubCardPaint] (the stats hub card). Same request, renderer and cache as the arc card, so
 * the two hubs can never differ. While the frost renders the flat card shows (a swap, never a blank frame).
 */
fun Modifier.hubCardBackground(cardSkin: Skin, refract: Boolean): Modifier = composed {
    val density = LocalDensity.current.density
    val config = LocalConfiguration.current
    var bounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val wallpaper = GlassWallpaperStage.current
    val request = bounds?.takeIf { it.width >= 1f && it.height >= 1f }?.let {
        HubCardPaint.request(cardSkin, (config.screenWidthDp * density).toInt(), (config.screenHeightDp * density).toInt(),
            it.left.toInt(), it.top.toInt(), it.width.toInt(), it.height.toInt(), density, wallpaper?.id ?: 0, refract)
    }
    val frosted by rememberFrosted(request, wallpaper?.bitmap)
    val flat = remember(cardSkin.colors.moduleSurface) { androidx.compose.ui.graphics.Color(cardSkin.colors.moduleSurface.toInt()) }
    val image = remember(frosted) { frosted?.asImageBitmap() }
    val corner = cardSkin.shape.moduleCornerDp * density
    this.onGloballyPositioned {
        val next = it.boundsInWindow()
        val old = bounds
        if (old == null || kotlin.math.abs(next.width - old.width) >= 1f || kotlin.math.abs(next.height - old.height) >= 1f ||
            kotlin.math.abs(next.left - old.left) > MovedPx || kotlin.math.abs(next.top - old.top) > MovedPx) bounds = next
    }.drawBehind {
        if (image != null && request != null) drawImage(image, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()))
        else drawRoundRect(flat, cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner))
    }
}

/** T173: a hub card for the gallery and Settings previews: [skin] is the card skin (`arcSkin` / `statsSkin`), the place is in dp. */
class HomeCard(val skin: Skin, val leftDp: Float, val topDp: Float, val widthDp: Float, val heightDp: Float, val refract: Boolean)

/** T173/T176: the preview draws a hub card through [HubCardPaint], exactly as Home. Call off the main thread. */
object HomeCards {
    fun draw(canvas: Canvas, card: HomeCard, density: Float, widthDp: Float, heightDp: Float, stage: Bitmap?) {
        val bitmap = HubCardPaint.render(card.skin, (widthDp * density).toInt(), (heightDp * density).toInt(),
            (card.leftDp * density).toInt(), (card.topDp * density).toInt(), (card.widthDp * density).toInt(),
            (card.heightDp * density).toInt(), density, stage, card.refract)
        canvas.drawBitmap(bitmap, card.leftDp * density, card.topDp * density, Paint(Paint.FILTER_BITMAP_FLAG))
    }
}
