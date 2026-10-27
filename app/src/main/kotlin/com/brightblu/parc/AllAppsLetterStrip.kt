package com.brightblu.parc

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.ring.FeelEvent
import com.brightblu.parc.ring.FeelPlayer
import com.brightblu.parc.ring.RingRotation
import com.brightblu.parc.ring.RingSteer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * OPUS4/OPUS5: the A to Z letters of the All-apps ring. Pure rules first (tested).
 *
 * Letters are "#" (names starting with a digit or symbol) then A to Z, from the same alphabetical
 * key the ring is sorted by, so a letter's first app is exactly the app the ring shows first for it.
 */
internal object LetterStrip {
    /** "#", then A to Z. */
    val Letters: List<String> = listOf("#") + ('A'..'Z').map { it.toString() }

    /** Index into [Letters] for a label: its first folded character, or "#" when that is not A to Z. */
    fun letterOf(label: String): Int {
        val first = alphabeticalKey(label).trimStart().firstOrNull() ?: return 0
        return if (first in 'a'..'z') first - 'a' + 1 else 0
    }

    /** For each letter, the ring index of its first app, or -1 when no app starts with it. */
    fun firstIndex(labelsInRingOrder: List<String>): IntArray {
        val first = IntArray(Letters.size) { -1 }
        labelsInRingOrder.forEachIndexed { index, label ->
            val letter = letterOf(label)
            if (first[letter] < 0) first[letter] = index
        }
        return first
    }

    /** Empty letters are skipped: the nearest letter that has apps (the later one on a tie), or -1. */
    fun nearestEnabled(letter: Int, first: IntArray): Int {
        if (first.none { it >= 0 }) return -1
        for (distance in 0 until first.size) {
            val after = letter + distance
            if (after in first.indices && first[after] >= 0) return after
            val before = letter - distance
            if (before in first.indices && first[before] >= 0) return before
        }
        return -1
    }

    /** The letter of the app at the front of the ring for a ring offset (follows hand turning). */
    fun frontLetter(offset: Float, labelsInRingOrder: List<String>): Int {
        val item = RingRotation.frontItem(offset, labelsInRingOrder.size)
        return if (item < 0) -1 else letterOf(labelsInRingOrder[item])
    }

    /** Only on the All-apps layer, when nothing covers it and the ring actually turns. */
    fun visible(layer: Layer, searchOpen: Boolean, editing: Boolean, appCount: Int, ringCapacity: Int): Boolean =
        layer == Layer.AllApps && !searchOpen && !editing && RingRotation.rotates(appCount, ringCapacity)
}

/**
 * OPUS5: the letters sit on an inner arc around the hub, concentric with the ring, in the empty
 * space between the hub and the All-apps ring, under a right thumb. "#" and A are at the top end
 * (where the ring starts its list), Z at the left end. A touch picks a letter by its ANGLE around
 * the hub; only touches inside a ring-shaped band count, so the hub (tap, long-press, joystick aim)
 * keeps everything near its centre. Angles are degrees in the screen frame (180 = left, 270 = up).
 */
internal object LetterArc {
    /** One placement of the letter arc, in dp. */
    data class Arc(val originX: Float, val originY: Float, val radius: Float,
        val startDeg: Float, val endDeg: Float, val band: Float) {
        /** Inner and outer edge of the touch band. */
        val inner: Float get() = radius - band
        val outer: Float get() = radius + band
    }

    /**
     * The radius sits between the second and third home arcs (A2/A3, reachable without regripping),
     * large enough that 27 upright letters do not touch where the arc runs vertically, pulled in
     * when the All-apps ring is closer, and never inside the hub's touch area plus a gap. The sweep
     * leaves a degree or two at both ends.
     */
    fun place(originX: Float, originY: Float, ring: ArcSpec?, iconSizeDp: Float,
        searchX: Float = Float.NaN, searchY: Float = Float.NaN): Arc {
        val band = Metrics.letterArcBandDp
        val hubKeep = Metrics.hubTouchDp / 2f + Metrics.letterArcHubGapDp + band
        val ringKeep = if (ring == null) Float.POSITIVE_INFINITY
            else ring.radiusDp - iconSizeDp / 2f - Metrics.gridDp * 2 - band
        var radius = minOf(Metrics.letterArcMaxRadiusDp, ringKeep).coerceAtLeast(hubKeep)
        // On a narrow screen the left end comes near the search button: pull the arc in until the
        // whole touch band clears the button's touch circle.
        if (searchX.isFinite() && searchY.isFinite()) {
            val clearance = Metrics.searchButtonTouchDp / 2f + Metrics.gridDp * 2
            while (radius > hubKeep && clearsSearch(Arc(originX, originY, radius, Metrics.letterArcStartDeg,
                    Metrics.letterArcEndDeg, band), searchX, searchY, clearance).not()) radius -= Metrics.gridDp / 2f
        }
        return Arc(originX, originY, radius, Metrics.letterArcStartDeg, Metrics.letterArcEndDeg, band)
    }

    private fun clearsSearch(arc: Arc, searchX: Float, searchY: Float, clearance: Float): Boolean {
        for (i in LetterStrip.Letters.indices) {
            val (x, y) = point(arc, angleOfLetter(arc, i), arc.outer)
            if (hypot(x - searchX, y - searchY) <= clearance) return false
        }
        return true
    }

    /** Screen-frame angle of a point around the arc origin, in [0, 360). */
    fun angleOf(arc: Arc, x: Float, y: Float): Float {
        val deg = Math.toDegrees(atan2((y - arc.originY).toDouble(), (x - arc.originX).toDouble())).toFloat()
        return if (deg < 0f) deg + 360f else deg
    }

    /** True for a touch inside the ring-shaped letter band; the hub area is never inside it. */
    fun inZone(arc: Arc, x: Float, y: Float): Boolean {
        val r = hypot(x - arc.originX, y - arc.originY)
        if (r < arc.inner || r > arc.outer) return false
        val a = angleOf(arc, x, y)
        val slack = step(arc)
        return a >= arc.startDeg - slack && a <= arc.endDeg + slack
    }

    /** Degrees between neighbouring letters. */
    fun step(arc: Arc): Float = (arc.endDeg - arc.startDeg) / (LetterStrip.Letters.size - 1)

    /** The letter for an angle: "#" at the top end, Z at the left end; angles past either end clamp. */
    fun letterAt(arc: Arc, angleDeg: Float): Int {
        val position = (arc.endDeg - angleDeg) / step(arc)
        return Math.round(position).coerceIn(0, LetterStrip.Letters.lastIndex)
    }

    /** Angle of a letter's centre. */
    fun angleOfLetter(arc: Arc, letter: Int): Float = arc.endDeg - letter * step(arc)

    /** Centre (dp) of a point at [radius] on the arc at [angleDeg]. */
    fun point(arc: Arc, angleDeg: Float, radius: Float = arc.radius): Pair<Float, Float> {
        val a = Math.toRadians(angleDeg.toDouble())
        return arc.originX + cos(a).toFloat() * radius to arc.originY + sin(a).toFloat() * radius
    }
}

/**
 * Pointer node that only takes touches that START inside the letter band. It shares every event
 * with the ring underneath (the renderer ignores letter-band touches itself), so the hub, the
 * joystick aim and the arcs work exactly as before everywhere else.
 */
private class LetterArcInputNode(var arc: LetterArc.Arc, var onTouch: (Float, Float, Boolean) -> Unit,
    var onRelease: () -> Unit) : Modifier.Node(), PointerInputModifierNode {
    private var tracking: PointerId? = null

    override fun sharePointerInputWithSiblings(): Boolean = true

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Main) return
        val density = requireDensity().density
        for (change in pointerEvent.changes) {
            val x = change.position.x / density
            val y = change.position.y / density
            if (tracking == null && change.pressed && !change.previousPressed) {
                if (LetterArc.inZone(arc, x, y)) {
                    tracking = change.id
                    change.consume()
                    onTouch(x, y, true)
                }
            } else if (change.id == tracking) {
                change.consume()
                if (change.pressed) onTouch(x, y, false) else { tracking = null; onRelease() }
            }
        }
    }

    override fun onCancelPointerInput() {
        if (tracking != null) { tracking = null; onRelease() }
    }
}

private data class LetterArcInputElement(val arc: LetterArc.Arc, val onTouch: (Float, Float, Boolean) -> Unit,
    val onRelease: () -> Unit) : ModifierNodeElement<LetterArcInputNode>() {
    override fun create() = LetterArcInputNode(arc, onTouch, onRelease)
    override fun update(node: LetterArcInputNode) { node.arc = arc; node.onTouch = onTouch; node.onRelease = onRelease }
}

/**
 * The letter arc: a thin arc line in the ring's own line colours with upright letters on it. Touch
 * or drag along it to turn the ring to a letter; a large bubble shows the letter outside the arc,
 * away from the thumb; a light tick per letter when haptics are on; one "Jump to letter" action per
 * letter for TalkBack. The front letter is highlighted while the ring is turned by hand.
 */
@Composable
internal fun AllAppsLetterArc(
    arc: LetterArc.Arc,
    labelsInRingOrder: List<String>,
    steer: RingSteer,
    skin: Skin,
    haptics: Boolean,
    feel: FeelPlayer,
) {
    val first = remember(labelsInRingOrder) { LetterStrip.firstIndex(labelsInRingOrder) }
    val front = LetterStrip.frontLetter(steer.frontOffset, labelsInRingOrder)
    var held by remember { mutableIntStateOf(-1) }
    var fingerAngle by remember { mutableFloatStateOf(0f) }
    val view: View = LocalView.current
    val latestHaptics by rememberUpdatedState(haptics)
    val latestFirst by rememberUpdatedState(first)
    val shown = if (held >= 0) held else front
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val textStyle = TextStyle(fontSize = Metrics.letterStripTextSp.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    fun jump(x: Float, y: Float, animate: Boolean) {
        fingerAngle = LetterArc.angleOf(arc, x, y)
        val target = LetterStrip.nearestEnabled(LetterArc.letterAt(arc, fingerAngle), latestFirst)
        if (target < 0) return
        if (target != held) {
            if (latestHaptics) feel.play(view, FeelEvent.Tick)
            held = target
        }
        steer.turnTo(latestFirst[target], animate)
    }
    val primary = Color(skin.colors.textPrimary.toInt())
    val accent = Color(skin.colors.accent.toInt())
    val dim = Color(skin.colors.textSecondary.toInt()).copy(alpha = Metrics.letterStripDimAlpha)
    val line = Color(skin.colors.lineBase.toInt())
    Box(Modifier.fillMaxSize()
        .then(LetterArcInputElement(arc, { x, y, down -> jump(x, y, animate = down) }, { held = -1 }))
        .drawWithCache {
            // Letters are measured and placed once per size and skin; drawing only reads them.
            val px = density.density
            val layouts = LetterStrip.Letters.map { measurer.measure(it, textStyle) }
            val centres = Array(LetterStrip.Letters.size) { i ->
                val (x, y) = LetterArc.point(arc, LetterArc.angleOfLetter(arc, i))
                Offset(x * px - layouts[i].size.width / 2f, y * px - layouts[i].size.height / 2f)
            }
            val stroke = Stroke(skin.lineWidths.baseDp * px, cap = StrokeCap.Round)
            val sweep = arc.endDeg - arc.startDeg
            // The guide line runs just outside the letters, like the arcs' own rails.
            val guideR = (arc.radius + Metrics.letterArcGuideOffsetDp) * px
            val guideTopLeft = Offset(arc.originX * px - guideR, arc.originY * px - guideR)
            val guideSize = Size(guideR * 2, guideR * 2)
            onDrawBehind {
                drawArc(line, arc.startDeg, sweep, false, guideTopLeft, guideSize, style = stroke)
                for (i in layouts.indices) {
                    val color = when {
                        i == shown -> accent
                        first[i] >= 0 -> primary
                        else -> dim
                    }
                    drawText(layouts[i], color = color, topLeft = centres[i])
                }
            }
        }) {
        // TalkBack: one small node per letter, at the letter.
        LetterStrip.Letters.forEachIndexed { index, letter ->
            val enabled = first[index] >= 0
            val name = if (letter == "#") "numbers and symbols" else "letter $letter"
            val (x, y) = LetterArc.point(arc, LetterArc.angleOfLetter(arc, index))
            val half = Metrics.letterArcBandDp / 2f
            Box(Modifier.offset((x - half).dp, (y - half).dp).size((half * 2).dp).semantics {
                contentDescription = if (enabled) "Jump to $name" else "No apps under $name"
                if (enabled) onClick { steer.turnTo(first[index], animate = true); true } else disabled()
                if (index == shown) selected = true
            })
        }
    }
    if (held >= 0) {
        // The bubble sits outside the arc on the finger's angle, so the thumb (coming from the
        // bottom-right hub) never covers it.
        val size = Metrics.letterBubbleDp
        val (bx, by) = LetterArc.point(arc, fingerAngle, arc.outer + size / 2f + Metrics.gridDp * 2)
        Box(Modifier.offset((bx - size / 2f).dp, (by - size / 2f).dp).size(size.dp)
            .background(Color(skin.colors.surface.toInt()), CircleShape)
            .border(skin.lineWidths.activeDp.dp, accent, CircleShape)
            .clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            BasicText(LetterStrip.Letters[held], style = TextStyle(color = accent,
                fontSize = Metrics.letterBubbleTextSp.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
        }
    }
}
