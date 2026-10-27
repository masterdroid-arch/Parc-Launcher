package com.brightblu.parc.ring

import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Debug
import android.util.Log
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import com.brightblu.parc.model.*
import kotlinx.coroutines.delay
import kotlin.math.*

/** Debug-only option B. The host uses RingRenderer.Field; no View implementation is coupled to it. */
class SpikeRenderer(
    private val geometry: SpikeGeometry,
    private val assets: SpikeAssets,
    private val controller: SpikeController,
    private val allocationCheck: Boolean = false,
) : RingRenderer {
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun Field(state: RingState, skin: Skin, callbacks: RingCallbacks) {
        val density = LocalDensity.current.density
        val scope = rememberCoroutineScope()
        val resources = remember(geometry, assets, density, skin, state) {
            DrawResources(geometry, assets, density, skin, state, allocationCheck)
        }
        LaunchedEffect(resources) {
            while (allocationCheck) {
                delay(2_000)
                Log.i("Parc", "M0 draw allocation counter: supported=${resources.counterSupported}" +
                    " samples=${resources.samples} objects=${resources.objects} max=${resources.maxObjects}")
            }
        }
        LaunchedEffect(controller) {
            if (controller.animatorScale <= 0f) return@LaunchedEffect
            val start = withFrameNanos { it }
            while (true) {
                withFrameNanos {
                    controller.breathe.floatValue =
                        ((it - start) % (skin.motion.breathePeriod * 1_000_000L)).toFloat() /
                            (skin.motion.breathePeriod * 1_000_000L)
                }
            }
        }
        Canvas(Modifier.fillMaxSize().pointerInteropFilter { event ->
            val x = event.x / density
            val y = event.y / density
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) controller.interrupt()
                    controller.glowX.floatValue = event.x
                    controller.glowY.floatValue = event.y
                    controller.touching.value = true
                    val hit = geometry.hit(x, y)
                    controller.activeArc.intValue = if (hit < 0) -1 else geometry.arcIndices[hit]
                }
                MotionEvent.ACTION_UP -> {
                    controller.touching.value = false
                    controller.activeArc.intValue = -1
                    if (geometry.hubHit(x, y)) controller.snap(scope)
                    else {
                        val index = geometry.hit(x, y)
                        if (index >= 0) {
                            val offset = if (controller.level.floatValue >= 0.5f) geometry.count else 0
                            val slot = state.slots[index + offset]
                            callbacks.onTap(SlotHit(slot.position, slot.slot, x, y))
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    controller.touching.value = false
                    controller.activeArc.intValue = -1
                }
            }
            true
        }) {
            // Only primitive state reads and preallocated native draw resources in this path.
            val before = if (resources.counterSupported) Debug.getThreadAllocCount() else 0
            val canvas = drawContext.canvas.nativeCanvas
            resources.draw(canvas, controller.level.floatValue, controller.breathe.floatValue,
                controller.touching.value, controller.glowX.floatValue, controller.glowY.floatValue,
                controller.activeArc.intValue)
            resources.frames++
            if (resources.counterSupported && resources.frames > SpikeConfig.allocationWarmup) {
                val allocated = Debug.getThreadAllocCount() - before
                resources.samples++
                resources.objects += allocated
                if (allocated > resources.maxObjects) resources.maxObjects = allocated
            }
        }
    }
}

/** Preallocated native drawing resources. No iterators, paths, paints, rects or matrices in draw. */
@Suppress("DEPRECATION")
private class DrawResources(
    private val geometry: SpikeGeometry,
    private val assets: SpikeAssets,
    private val density: Float,
    private val skin: Skin,
    state: RingState,
    allocationCheck: Boolean,
) {
    private val lines = Array(geometry.arcs.size * 2) { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    } }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = skin.colors.accent.toInt() }
    private val rect = RectF()
    private val arcRect = RectF()
    private val paths = Array(geometry.arcs.size) { Path() }
    private val source = android.graphics.Rect(0, 0, SpikeConfig.bitmapPx, SpikeConfig.bitmapPx)
    private val cx = geometry.originX * density
    private val cy = geometry.originY * density
    private val halfIcon = Metrics.iconDp * density / 2f
    private val glowRadius = skin.glowRadiusDp * density
    private val px = FloatArray(geometry.count) { (geometry.x[it] - geometry.originX) * density }
    private val py = FloatArray(geometry.count) { (geometry.y[it] - geometry.originY) * density }
    private val dim = FloatArray(geometry.count * 2) {
        val app = state.slots[it].slot as? Slot.App
        val highlight = state.highlight
        if (highlight != null && (app == null || app.id !in highlight)) 0.15f else 1f
    }
    private var calibration: ByteArray? = null
    val counterSupported: Boolean
    var frames = 0L
    var samples = 0L
    var objects = 0L
    var maxObjects = 0

    init {
        var a = 0
        while (a < geometry.arcs.size) {
            val arc = geometry.arcs[a]
            val radius = arc.radiusDp * density
            arcRect.set(-radius, -radius, radius, radius)
            paths[a].addArc(arcRect, arc.startAngle, arc.endAngle - arc.startAngle)
            val start = arc.startAngle / 360f
            val end = arc.endAngle / 360f
            val fade = (end - start) * skin.shape.lineEndFadeFraction
            val stops = floatArrayOf(0f, start, start + fade, end - fade, end, 1f)
            var active = 0
            while (active < 2) {
                val color = (if (active == 1) skin.colors.lineActive else skin.colors.lineBase).toInt()
                val transparent = color and 0x00ffffff
                val paint = lines[a * 2 + active]
                paint.shader = android.graphics.SweepGradient(0f, 0f,
                    intArrayOf(transparent, transparent, color, color, transparent, transparent), stops)
                paint.strokeWidth = (if (active == 1) skin.lineWidths.activeDp
                    else skin.lineWidths.baseDp) * density
                active++
            }
            a++
        }
        // Prime lazy framework shader/bitmap handles before the first interactive draw.
        val warmup = android.graphics.Picture()
        val warmCanvas = warmup.beginRecording(1, 1)
        var paintIndex = 0
        while (paintIndex < lines.size) {
            warmCanvas.drawPath(paths[paintIndex / 2], lines[paintIndex])
            paintIndex++
        }
        rect.set(0f, 0f, 1f, 1f)
        warmCanvas.drawBitmap(assets.glow, source, rect, icon)
        warmup.endRecording()
        // Calibrate with an escaping allocation; unsupported ART counters must not imply zero.
        if (allocationCheck) {
            Debug.startAllocCounting()
            val before = Debug.getThreadAllocCount()
            calibration = ByteArray(1_024)
            counterSupported = Debug.getThreadAllocCount() > before
        } else counterSupported = false
    }

    fun draw(canvas: NativeCanvas, level: Float, phase: Float, touching: Boolean,
        glowX: Float, glowY: Float, activeArc: Int) {
        canvas.drawColor(skin.colors.bg.toInt())
        val breathe = 1f + skin.breatheAmount * sin(phase * (2f * Math.PI.toFloat()))
        if (touching) {
            rect.set(glowX - glowRadius, glowY - glowRadius,
                glowX + glowRadius, glowY + glowRadius)
            icon.alpha = 255
            canvas.drawBitmap(assets.glow, source, rect, icon)
        }
        if (level < 1f) drawLayer(canvas, 1f - level,
            1f - (1f - SpikeConfig.collapsedScale) * level, false, breathe, activeArc)
        if (level > 0f) drawLayer(canvas, level,
            SpikeConfig.collapsedScale + (1f - SpikeConfig.collapsedScale) * level,
            true, breathe, activeArc)
        canvas.drawCircle(cx, cy, Metrics.hubRadiusDp * density, hub)
    }

    private fun drawLayer(canvas: NativeCanvas, alpha: Float, scale: Float, folder: Boolean,
        breathe: Float, activeArc: Int) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        var arcIndex = 0
        while (arcIndex < geometry.arcs.size) {
            val line = lines[arcIndex * 2 + if (arcIndex == activeArc) 1 else 0]
            line.alpha = (255f * alpha * breathe / (1f + skin.breatheAmount)).toInt().coerceIn(0, 255)
            canvas.drawPath(paths[arcIndex], line)
            arcIndex++
        }
        val bitmaps = if (folder) assets.folder else assets.home
        val offset = if (folder) geometry.count else 0
        var index = 0
        while (index < geometry.count) {
            icon.alpha = (255f * alpha * skin.icons.alphaUnfocused * dim[offset + index]).toInt()
            rect.set(px[index] - halfIcon, py[index] - halfIcon,
                px[index] + halfIcon, py[index] + halfIcon)
            canvas.drawBitmap(bitmaps[index], source, rect, icon)
            index++
        }
        canvas.restore()
    }
}
