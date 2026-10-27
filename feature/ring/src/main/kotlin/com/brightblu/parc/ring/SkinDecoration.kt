package com.brightblu.parc.ring

import android.graphics.*
import com.brightblu.parc.model.*
import kotlin.math.*

/** Prepared once with each skin/viewport scene. Drawing only reuses bitmap/path/paint storage. */
internal class SkinDecoration(skin: Skin, widthDp: Float, heightDp: Float, private val density: Float,
    arcs: List<ArcSpec>) {
    private val spec = skin.drawingSpec()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skin.colors.lineBase.toInt(); style = Paint.Style.STROKE
        strokeWidth = skin.lineWidths.baseDp * density
    }
    private val hubPaint = Paint(paint).apply { color = skin.colors.textPrimary.toInt() }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backgroundRect = RectF(0f, 0f, widthDp * density, heightDp * density)
    private val ticks = Path()
    private val compass = Path()
    private val numeralPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skin.colors.textSecondary.toInt(); textSize = skin.typography.caption.sizeSp * density
        typeface = Typeface.create(spec.annotationFamily, Typeface.NORMAL); textAlign = Paint.Align.CENTER
    }
    private val numerals = ArrayList<String>()
    private val numeralX = ArrayList<Float>()
    private val numeralY = ArrayList<Float>()
    private val numeralXs: FloatArray
    private val numeralYs: FloatArray
    private val bearings: Array<String>
    private val background: Bitmap? = if (spec.background == BackgroundPattern.none) null else
        Bitmap.createBitmap(ceil(widthDp).toInt().coerceAtLeast(1), ceil(heightDp).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { b ->
            val canvas = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = skin.colors.lineBase.toInt(); alpha = (255 * Metrics.atlasPatternAlpha).toInt()
                strokeWidth = skin.lineWidths.baseDp; style = Paint.Style.STROKE
            }
            when (spec.background) {
                BackgroundPattern.grid -> {
                    var x = 0f
                    while (x < widthDp) { canvas.drawLine(x, 0f, x, heightDp, p); x += Metrics.atlasGridDp }
                    var y = 0f
                    while (y < heightDp) { canvas.drawLine(0f, y, widthDp, y, p); y += Metrics.atlasGridDp }
                }
                BackgroundPattern.contour -> {
                    val path = Path()
                    var y = heightDp * 0.33f
                    while (y < heightDp) {
                        path.rewind()
                        var x = 0f
                        while (x <= widthDp + Metrics.gridDp) {
                            val yy = y + sin(x / Metrics.atlasGridDp + y / Metrics.atlasContourSpacingDp) * Metrics.atlasContourAmplitudeDp +
                                cos(x / (Metrics.atlasGridDp * 2) + y / Metrics.atlasGridDp) * Metrics.atlasContourAmplitudeDp
                            if (x == 0f) path.moveTo(x, yy) else path.lineTo(x, yy)
                            x += Metrics.gridDp
                        }
                        canvas.drawPath(path, p); y += Metrics.atlasContourSpacingDp
                    }
                }
                BackgroundPattern.paper -> {
                    // Deterministic pre-rasterised tile. No randomness or texture work during draw.
                    val tile = Bitmap.createBitmap(Metrics.atlasTilePx, Metrics.atlasTilePx, Bitmap.Config.ARGB_8888)
                    val tc = Canvas(tile)
                    p.style = Paint.Style.FILL
                    for (y in 0 until tile.height) for (x in 0 until tile.width) {
                        if ((x * 17 + y * 31) % 11 == 0) tc.drawPoint(x.toFloat(), y.toFloat(), p)
                    }
                    p.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                    canvas.drawRect(0f, 0f, widthDp, heightDp, p)
                }
                BackgroundPattern.none -> Unit
            }
        }

    init {
        if (spec.ticks || spec.bearings) for (arc in arcs) {
            for (degree in ceil(min(arc.startAngle, arc.endAngle)).toInt()..floor(max(arc.startAngle, arc.endAngle)).toInt()) {
                val radians = Math.toRadians(degree.toDouble())
                val c = cos(radians).toFloat(); val s = sin(radians).toFloat()
                val radius = arc.radiusDp * density
                val major = degree % Metrics.atlasBearingStepDegrees == 0
                if (spec.ticks && degree % Metrics.atlasTickStepDegrees == 0) {
                    val len = (if (major) Metrics.atlasMajorTickDp else Metrics.atlasTickDp) * density
                    ticks.moveTo(c * radius, s * radius); ticks.lineTo(c * (radius + len), s * (radius + len))
                }
                if (spec.bearings && major) {
                    numerals += degree.toString()
                    numeralX += c * (radius + Metrics.atlasBearingGapDp * density)
                    numeralY += s * (radius + Metrics.atlasBearingGapDp * density)
                }
            }
        }
        bearings = numerals.toTypedArray(); numeralXs = numeralX.toFloatArray(); numeralYs = numeralY.toFloatArray()
        val r = Metrics.hubRadiusDp * density
        if (spec.hub != HubStyle.plain) {
            compass.addCircle(0f, 0f, r * 0.78f, Path.Direction.CW)
            compass.moveTo(-r, 0f); compass.lineTo(r, 0f)
            compass.moveTo(0f, -r); compass.lineTo(0f, r)
            if (spec.hub == HubStyle.compass) {
                compass.moveTo(0f, -r * 0.78f); compass.lineTo(-r * 0.16f, r * 0.25f)
                compass.lineTo(0f, 0f); compass.lineTo(r * 0.16f, r * 0.25f); compass.close()
            }
        }
    }

    fun drawBackground(canvas: Canvas) { background?.let { canvas.drawBitmap(it, null, backgroundRect, backgroundPaint) } }
    fun visibleBearings(xs: FloatArray, ys: FloatArray, clearance: Float): BooleanArray =
        BooleanArray(bearings.size) { i ->
            var clear = true
            for (j in xs.indices) {
                if (hypot(numeralXs[i] - xs[j], numeralYs[i] - ys[j]) < clearance) { clear = false; break }
            }
            clear
        }
    fun railPaint(): Paint = Paint(paint)
    fun bearingPaint(): Paint = Paint(numeralPaint)
    fun drawRails(canvas: Canvas, alpha: Float, paint: Paint, numeralPaint: Paint, visible: BooleanArray) {
        paint.alpha = (255 * alpha).toInt(); numeralPaint.alpha = paint.alpha
        canvas.drawPath(ticks, paint)
        var i = 0
        while (i < bearings.size) {
            if (visible[i]) canvas.drawText(bearings[i], numeralXs[i], numeralYs[i], numeralPaint)
            i++
        }
    }
    fun drawHub(canvas: Canvas, x: Float, y: Float) {
        if (spec.hub == HubStyle.plain) return
        canvas.save(); canvas.translate(x, y); canvas.drawPath(compass, hubPaint); canvas.restore()
    }
}

/** Bounded, renderer-local cache. Preparation may overlap; published drawing assets are immutable. */
internal class SkinDecorationCache(private val skin: Skin, private val width: Float,
    private val height: Float, private val density: Float) {
    private val entries = LinkedHashMap<List<ArcSpec>, SkinDecoration>()
    @Synchronized fun get(arcs: List<ArcSpec>): SkinDecoration = entries[arcs] ?: run {
        val result = SkinDecoration(skin, width, height, density, arcs)
        if (entries.size >= 2) entries.remove(entries.keys.first())
        entries[arcs.toList()] = result
        result
    }
}
