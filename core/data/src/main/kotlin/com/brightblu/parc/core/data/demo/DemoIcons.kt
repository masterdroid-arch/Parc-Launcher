package com.brightblu.parc.core.data.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.brightblu.parc.model.AppId
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * T130: generic, code-drawn icons for the demo launcher only.
 *
 * The fixture has no brand assets and no image files. Every icon is a coloured shape plus one
 * simple glyph, chosen from the small tables below. The tables and the demo gate are pure Kotlin
 * so JUnit can assert them; only [DemoIcons.iconFor] touches Android graphics.
 */

/** The only three silhouettes a demo icon can use. */
enum class DemoIconShape { Circle, RoundedSquare, Squircle }

/** The small line/fill marks drawn inside a demo icon. */
enum class DemoGlyph {
    Envelope, Pin, Note, Photo, Camera, Lines, Calendar, Globe, Chat, Folder, Sun, Mic,
    Blocks, Card, Clock, Gear, Plus, Grid, Compass, Text, Scan, Play, Image, Phone,
    Radio, Timer, List, Cards, Bars, Movie,
}

/** One immutable icon recipe: silhouette, palette slot and glyph. */
data class DemoIconSpec(
    val shape: DemoIconShape,
    val colorIndex: Int,
    val glyph: DemoGlyph,
)

/**
 * Eight colours that stay distinct on both a dark and a light wallpaper. They are deliberately
 * generic: no colour is copied from a real app's logo.
 */
object DemoIconPalette {
    val colors: List<Long> = listOf(
        0xFF3D6FE0L, // blue
        0xFF2E9E6BL, // green
        0xFFE08A2EL, // orange
        0xFFD44A5AL, // red
        0xFF7A5CE0L, // purple
        0xFF1A9AA8L, // teal
        0xFFD8B52EL, // yellow
        0xFFD65C9EL, // pink
    )

    /** The glyph ink; near-white so it reads on every palette entry. */
    const val Ink: Long = 0xFFF7F9FCL
}

/**
 * The demo fixture's app keys in one place. [DemoIconCatalog.specFor] is total for these keys, and
 * `DemoIconsTest` fails if the list and the table ever drift apart.
 */
object DemoIconCatalog {
    /** Keys used by `DemoHomeLayout` in :app. Kept here so the table is testable in :core:data. */
    val sampleKeys: List<String> = listOf(
        "notes", "maps", "music", "camera", "clock", "weather", "photos", "files",
        "calendar", "calculator", "recorder", "wallet", "chat", "mail", "feed", "calls",
        "compass", "translator", "scanner", "timer", "podcasts", "video", "radio", "gallery",
        "solitaire", "blocks", "sudoku",
    )

    private val specs: Map<String, DemoIconSpec> = listOf(
        "notes" to DemoIconSpec(DemoIconShape.Squircle, 6, DemoGlyph.Lines),
        "maps" to DemoIconSpec(DemoIconShape.Circle, 1, DemoGlyph.Pin),
        "music" to DemoIconSpec(DemoIconShape.Circle, 4, DemoGlyph.Note),
        "camera" to DemoIconSpec(DemoIconShape.RoundedSquare, 3, DemoGlyph.Camera),
        "clock" to DemoIconSpec(DemoIconShape.Circle, 0, DemoGlyph.Clock),
        "weather" to DemoIconSpec(DemoIconShape.Circle, 2, DemoGlyph.Sun),
        "photos" to DemoIconSpec(DemoIconShape.Squircle, 7, DemoGlyph.Photo),
        "files" to DemoIconSpec(DemoIconShape.RoundedSquare, 0, DemoGlyph.Folder),
        "calendar" to DemoIconSpec(DemoIconShape.RoundedSquare, 3, DemoGlyph.Calendar),
        "calculator" to DemoIconSpec(DemoIconShape.Squircle, 5, DemoGlyph.Plus),
        "recorder" to DemoIconSpec(DemoIconShape.Circle, 3, DemoGlyph.Mic),
        "wallet" to DemoIconSpec(DemoIconShape.RoundedSquare, 4, DemoGlyph.Card),
        "chat" to DemoIconSpec(DemoIconShape.Squircle, 1, DemoGlyph.Chat),
        "mail" to DemoIconSpec(DemoIconShape.RoundedSquare, 0, DemoGlyph.Envelope),
        "feed" to DemoIconSpec(DemoIconShape.Squircle, 2, DemoGlyph.List),
        "calls" to DemoIconSpec(DemoIconShape.Circle, 1, DemoGlyph.Phone),
        "compass" to DemoIconSpec(DemoIconShape.Circle, 2, DemoGlyph.Compass),
        "translator" to DemoIconSpec(DemoIconShape.Squircle, 5, DemoGlyph.Text),
        "scanner" to DemoIconSpec(DemoIconShape.RoundedSquare, 4, DemoGlyph.Scan),
        "timer" to DemoIconSpec(DemoIconShape.Circle, 3, DemoGlyph.Timer),
        "podcasts" to DemoIconSpec(DemoIconShape.Circle, 4, DemoGlyph.Mic),
        "video" to DemoIconSpec(DemoIconShape.RoundedSquare, 0, DemoGlyph.Play),
        "radio" to DemoIconSpec(DemoIconShape.Circle, 6, DemoGlyph.Radio),
        "gallery" to DemoIconSpec(DemoIconShape.Squircle, 7, DemoGlyph.Image),
        "solitaire" to DemoIconSpec(DemoIconShape.RoundedSquare, 1, DemoGlyph.Cards),
        "blocks" to DemoIconSpec(DemoIconShape.RoundedSquare, 2, DemoGlyph.Blocks),
        "sudoku" to DemoIconSpec(DemoIconShape.Squircle, 5, DemoGlyph.Grid),
    ).toMap()

    /** The key part of a demo app id: `demo/notes` -> `notes`. */
    fun keyOf(id: AppId): String? =
        id.value.removePrefix(DemoIdPrefix).takeIf { id.value.startsWith(DemoIdPrefix) && it.isNotEmpty() }

    /** A recipe for every fixture key; null for anything else so the renderer keeps its fallback. */
    fun specFor(id: AppId): DemoIconSpec? = keyOf(id)?.let(specs::get)

    /** Stable colour for one spec; the palette index is always in range. */
    fun colorOf(spec: DemoIconSpec): Long = DemoIconPalette.colors[spec.colorIndex % DemoIconPalette.colors.size]

    const val DemoIdPrefix = "demo/"
}

/**
 * The demo-only gate and bitmap entry point.
 *
 * [shouldDraw] is pure and is the one place that decides whether a demo icon may replace the
 * launcher's real icon. `DemoIconPainterTest` (or any JVM test) can call it without Android.
 */
object DemoIcons {
    private const val CacheLimit = 64
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val lock = Any()

    /** True only for a sample id with demo mode on. Real ids never enter the demo painter. */
    fun shouldDraw(demo: Boolean, id: AppId): Boolean =
        demo && DemoIconCatalog.specFor(id) != null

    /**
     * A rasterised demo icon, or null when demo is off or [id] is not a fixture app. Callers fall
     * back to their normal icon source on null, so real mode is byte-for-byte the old path.
     */
    fun iconFor(demo: Boolean, id: AppId, sizePx: Int, label: String? = null): Bitmap? {
        if (!shouldDraw(demo, id)) return null
        val key = DemoIconCatalog.keyOf(id) ?: return null
        val spec = DemoIconCatalog.specFor(id) ?: return null
        val cacheKey = "$key@$sizePx"
        cache[cacheKey]?.let { return it }
        return synchronized(lock) {
            cache[cacheKey] ?: paintDemoIcon(spec, sizePx).also { bitmap ->
                if (cache.size >= CacheLimit) cache.clear()
                cache[cacheKey] = bitmap
            }
        }
    }

    /** Test seam: the cache must never leak between sizes or fixture runs. */
    internal fun clearCache() = cache.clear()
}

/**
 * Android rasterisation. Every coordinate is a fraction of [sizePx], so one routine serves the
 * launcher tile (144 px) and any future size. No text is drawn: a demo icon can never show a real
 * app name, and the fallback letter path is not used at all.
 */
internal fun paintDemoIcon(spec: DemoIconSpec, sizePx: Int): Bitmap {
    val size = sizePx.coerceAtLeast(1).toFloat()
    val bitmap = Bitmap.createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DemoIconCatalog.colorOf(spec).toInt() }
    val edge = size * 0.055f
    when (spec.shape) {
        DemoIconShape.Circle -> canvas.drawCircle(size / 2f, size / 2f, size / 2f - edge, fill)
        DemoIconShape.RoundedSquare -> canvas.drawRoundRect(
            edge, edge, size - edge, size - edge, size * 0.20f, size * 0.20f, fill)
        DemoIconShape.Squircle -> canvas.drawPath(squirclePath(size, edge), fill)
    }
    // A faint top highlight keeps the flat shape from looking like a placeholder letter tile.
    val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(26, 255, 255, 255)
    }
    canvas.drawOval(RectF(size * 0.16f, size * 0.10f, size * 0.84f, size * 0.58f), shine)
    drawGlyph(canvas, spec.glyph, size)
    return bitmap
}

/** Superellipse `|x|^4 + |y|^4 = 1`, sampled densely enough to read as a squircle. */
internal fun squirclePath(size: Float, inset: Float): Path {
    val path = Path()
    val centre = size / 2f
    val radius = centre - inset
    val steps = 72
    for (i in 0..steps) {
        val t = i * Math.PI * 2 / steps
        val c = cos(t).toFloat()
        val s = sin(t).toFloat()
        val x = centre + radius * (if (c < 0f) -1f else 1f) * abs(c).pow(0.5f)
        val y = centre + radius * (if (s < 0f) -1f else 1f) * abs(s).pow(0.5f)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

private fun drawGlyph(canvas: Canvas, glyph: DemoGlyph, s: Float) {
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DemoIconPalette.Ink.toInt()
        style = Paint.Style.STROKE
        strokeWidth = s * 0.055f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val solid = Paint(ink).apply { style = Paint.Style.FILL }
    fun p(x: Float, y: Float) = Pair(x * s, y * s)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
        val (a, b) = p(x1, y1); val (c, d) = p(x2, y2)
        canvas.drawLine(a, b, c, d, ink)
    }
    fun circle(x: Float, y: Float, r: Float, filled: Boolean = false) {
        canvas.drawCircle(x * s, y * s, r * s, if (filled) solid else ink)
    }
    fun rect(l: Float, t: Float, r: Float, b: Float, radius: Float = 0f) {
        canvas.drawRoundRect(l * s, t * s, r * s, b * s, radius * s, radius * s, ink)
    }
    fun path(block: Path.() -> Unit) {
        val path = Path().apply { block() }
        canvas.drawPath(path, ink)
    }
    fun move(x: Float, y: Float, path: Path) { path.moveTo(x * s, y * s) }
    fun to(x: Float, y: Float, path: Path) { path.lineTo(x * s, y * s) }
    fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, path: Path) {
        path.cubicTo(x1 * s, y1 * s, x2 * s, y2 * s, x3 * s, y3 * s)
    }

    when (glyph) {
        DemoGlyph.Envelope -> {
            rect(0.24f, 0.34f, 0.76f, 0.68f, 0.04f)
            path { move(0.24f, 0.38f, this); to(0.50f, 0.56f, this); to(0.76f, 0.38f, this) }
        }
        DemoGlyph.Pin -> {
            circle(0.50f, 0.40f, 0.14f)
            path { move(0.37f, 0.47f, this); to(0.50f, 0.72f, this); to(0.63f, 0.47f, this) }
            circle(0.50f, 0.40f, 0.045f, true)
        }
        DemoGlyph.Note -> {
            line(0.62f, 0.28f, 0.62f, 0.66f)
            circle(0.54f, 0.66f, 0.075f, true)
            path { move(0.62f, 0.29f, this); to(0.75f, 0.34f, this) }
        }
        DemoGlyph.Photo, DemoGlyph.Image -> {
            if (glyph == DemoGlyph.Image) rect(0.22f, 0.26f, 0.78f, 0.74f, 0.05f)
            circle(0.36f, 0.38f, 0.07f, true)
            path {
                move(0.25f, 0.70f, this); to(0.44f, 0.48f, this); to(0.56f, 0.61f, this)
                to(0.65f, 0.53f, this); to(0.76f, 0.70f, this)
            }
        }
        DemoGlyph.Camera -> {
            rect(0.22f, 0.36f, 0.78f, 0.70f, 0.06f)
            line(0.38f, 0.36f, 0.44f, 0.30f)
            line(0.56f, 0.30f, 0.62f, 0.36f)
            circle(0.50f, 0.53f, 0.11f)
        }
        DemoGlyph.Lines -> {
            line(0.28f, 0.34f, 0.72f, 0.34f)
            line(0.28f, 0.50f, 0.72f, 0.50f)
            line(0.28f, 0.66f, 0.58f, 0.66f)
        }
        DemoGlyph.Calendar -> {
            rect(0.24f, 0.32f, 0.76f, 0.72f, 0.05f)
            line(0.24f, 0.44f, 0.76f, 0.44f)
            line(0.36f, 0.26f, 0.36f, 0.36f)
            line(0.64f, 0.26f, 0.64f, 0.36f)
            circle(0.36f, 0.56f, 0.035f, true)
            circle(0.50f, 0.56f, 0.035f, true)
            circle(0.64f, 0.56f, 0.035f, true)
        }
        DemoGlyph.Globe -> {
            circle(0.50f, 0.50f, 0.25f)
            path { move(0.50f, 0.25f, this); to(0.50f, 0.75f, this) }
            path {
                move(0.26f, 0.50f, this)
                cubic(0.34f, 0.42f, 0.66f, 0.42f, 0.74f, 0.50f, this)
                cubic(0.66f, 0.58f, 0.34f, 0.58f, 0.26f, 0.50f, this)
            }
        }
        DemoGlyph.Chat -> {
            rect(0.22f, 0.30f, 0.78f, 0.64f, 0.10f)
            path { move(0.36f, 0.62f, this); to(0.32f, 0.74f, this); to(0.48f, 0.64f, this) }
            circle(0.36f, 0.47f, 0.035f, true)
            circle(0.50f, 0.47f, 0.035f, true)
            circle(0.64f, 0.47f, 0.035f, true)
        }
        DemoGlyph.Folder -> path {
            move(0.24f, 0.34f, this); to(0.44f, 0.34f, this); to(0.49f, 0.40f, this)
            to(0.76f, 0.40f, this); to(0.76f, 0.70f, this); to(0.24f, 0.70f, this)
            close()
        }
        DemoGlyph.Sun -> {
            circle(0.50f, 0.50f, 0.13f)
            for (i in 0 until 8) {
                val a = i * Math.PI / 4
                line((0.50f + cos(a).toFloat() * 0.20f), (0.50f + sin(a).toFloat() * 0.20f),
                    (0.50f + cos(a).toFloat() * 0.27f), (0.50f + sin(a).toFloat() * 0.27f))
            }
        }
        DemoGlyph.Mic -> {
            rect(0.42f, 0.26f, 0.58f, 0.56f, 0.08f)
            path { move(0.34f, 0.50f, this); cubic(0.34f, 0.68f, 0.66f, 0.68f, 0.66f, 0.50f, this) }
            line(0.50f, 0.66f, 0.50f, 0.74f)
            line(0.40f, 0.74f, 0.60f, 0.74f)
        }
        DemoGlyph.Blocks -> {
            rect(0.28f, 0.28f, 0.46f, 0.46f, 0.03f)
            rect(0.54f, 0.28f, 0.72f, 0.46f, 0.03f)
            rect(0.28f, 0.54f, 0.46f, 0.72f, 0.03f)
            rect(0.54f, 0.54f, 0.72f, 0.72f, 0.03f)
        }
        DemoGlyph.Grid -> {
            rect(0.24f, 0.24f, 0.76f, 0.76f, 0.05f)
            line(0.24f, 0.42f, 0.76f, 0.42f)
            line(0.24f, 0.60f, 0.76f, 0.60f)
            line(0.42f, 0.24f, 0.42f, 0.76f)
            line(0.60f, 0.24f, 0.60f, 0.76f)
        }
        DemoGlyph.Card -> {
            rect(0.22f, 0.34f, 0.78f, 0.68f, 0.06f)
            line(0.22f, 0.46f, 0.78f, 0.46f)
            line(0.30f, 0.58f, 0.50f, 0.58f)
        }
        DemoGlyph.Clock -> {
            circle(0.50f, 0.50f, 0.25f)
            line(0.50f, 0.50f, 0.50f, 0.34f)
            line(0.50f, 0.50f, 0.62f, 0.56f)
        }
        DemoGlyph.Gear -> {
            circle(0.50f, 0.50f, 0.16f)
            circle(0.50f, 0.50f, 0.05f)
            for (i in 0 until 8) {
                val a = i * Math.PI / 4
                line((0.50f + cos(a).toFloat() * 0.22f), (0.50f + sin(a).toFloat() * 0.22f),
                    (0.50f + cos(a).toFloat() * 0.29f), (0.50f + sin(a).toFloat() * 0.29f))
            }
        }
        DemoGlyph.Plus -> {
            line(0.50f, 0.26f, 0.50f, 0.74f)
            line(0.26f, 0.50f, 0.74f, 0.50f)
        }
        DemoGlyph.Compass -> {
            circle(0.50f, 0.50f, 0.25f)
            path {
                move(0.58f, 0.34f, this); to(0.54f, 0.54f, this); to(0.36f, 0.66f, this)
                to(0.46f, 0.46f, this); close()
            }
        }
        DemoGlyph.Text -> {
            line(0.32f, 0.70f, 0.50f, 0.30f)
            line(0.50f, 0.30f, 0.68f, 0.70f)
            line(0.39f, 0.56f, 0.61f, 0.56f)
        }
        DemoGlyph.Scan -> {
            path { move(0.24f, 0.38f, this); to(0.24f, 0.26f, this); to(0.36f, 0.26f, this) }
            path { move(0.64f, 0.26f, this); to(0.76f, 0.26f, this); to(0.76f, 0.38f, this) }
            path { move(0.76f, 0.62f, this); to(0.76f, 0.74f, this); to(0.64f, 0.74f, this) }
            path { move(0.36f, 0.74f, this); to(0.24f, 0.74f, this); to(0.24f, 0.62f, this) }
            line(0.34f, 0.50f, 0.66f, 0.50f)
        }
        DemoGlyph.Play -> path {
            move(0.40f, 0.30f, this); to(0.72f, 0.50f, this); to(0.40f, 0.70f, this); close()
        }
        DemoGlyph.Phone -> path {
            move(0.34f, 0.28f, this); to(0.43f, 0.28f, this); to(0.47f, 0.42f, this)
            to(0.41f, 0.47f, this)
            cubic(0.43f, 0.58f, 0.52f, 0.66f, 0.61f, 0.69f, this)
            to(0.66f, 0.63f, this); to(0.78f, 0.67f, this); to(0.78f, 0.76f, this)
            cubic(0.50f, 0.78f, 0.22f, 0.52f, 0.24f, 0.28f, this)
            close()
        }
        DemoGlyph.Radio -> {
            circle(0.50f, 0.54f, 0.06f, true)
            path { move(0.34f, 0.38f, this); cubic(0.24f, 0.48f, 0.24f, 0.60f, 0.34f, 0.70f, this) }
            path { move(0.66f, 0.38f, this); cubic(0.76f, 0.48f, 0.76f, 0.60f, 0.66f, 0.70f, this) }
            path { move(0.26f, 0.30f, this); cubic(0.12f, 0.44f, 0.12f, 0.64f, 0.26f, 0.78f, this) }
            path { move(0.74f, 0.30f, this); cubic(0.88f, 0.44f, 0.88f, 0.64f, 0.74f, 0.78f, this) }
        }
        DemoGlyph.Timer -> {
            circle(0.50f, 0.54f, 0.23f)
            line(0.50f, 0.54f, 0.50f, 0.40f)
            line(0.42f, 0.24f, 0.58f, 0.24f)
            line(0.50f, 0.24f, 0.50f, 0.31f)
        }
        DemoGlyph.List -> {
            circle(0.29f, 0.34f, 0.03f, true)
            circle(0.29f, 0.50f, 0.03f, true)
            circle(0.29f, 0.66f, 0.03f, true)
            line(0.38f, 0.34f, 0.72f, 0.34f)
            line(0.38f, 0.50f, 0.72f, 0.50f)
            line(0.38f, 0.66f, 0.72f, 0.66f)
        }
        DemoGlyph.Cards -> {
            rect(0.30f, 0.28f, 0.70f, 0.64f, 0.05f)
            rect(0.38f, 0.38f, 0.78f, 0.74f, 0.05f)
        }
        DemoGlyph.Bars -> {
            line(0.32f, 0.70f, 0.32f, 0.48f)
            line(0.50f, 0.70f, 0.50f, 0.34f)
            line(0.68f, 0.70f, 0.68f, 0.42f)
        }
        else -> {
            // Movie: a rounded frame with a play triangle.
            rect(0.24f, 0.32f, 0.76f, 0.68f, 0.05f)
            path { move(0.44f, 0.40f, this); to(0.64f, 0.50f, this); to(0.44f, 0.60f, this); close() }
        }
    }
}
