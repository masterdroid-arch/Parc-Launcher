package com.brightblu.parc.arcade

import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.brightblu.parc.CellLabel
import com.brightblu.parc.FeelStore
import com.brightblu.parc.hudValueStyle
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.ring.FeelEvent
import com.brightblu.parc.ring.FeelPrefs
import com.brightblu.parc.ring.SoundTheme
import com.brightblu.parc.rememberFeelPlayer
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The mutable side of one Orbit session; lives in a `remember`. The draw reads [state] directly, so nothing recomposes per frame. */
private class OrbitRun {
    var state: OrbitState = OrbitSim.fresh(newSeed())
    var scoreText = "0"
    var dragX = 0f
    var dragY = 0f
    var dragActive = false
    fun restart() { state = OrbitSim.fresh(newSeed()); scoreText = "0" }
    private fun newSeed(): Long = System.nanoTime()
}

/**
 * Orbit: the first game in the arcade. The shell ([ArcadeHubPage]) only gives it a size, the skin, whether the hub is showing
 * ([active]) and a callback for the score when a run ends. Rules are in [OrbitSim]; this file is input, the frame loop and the drawing.
 * Nothing runs while [active] is false or the run is not live: the frame loop is a coroutine that exists only while a run is live.
 */
@Composable
internal fun OrbitGame(skin: Skin, active: Boolean, best: Int, onRunEnd: (Int) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val host = LocalArcadeHost.current
    val feel = rememberFeelPlayer()
    val feelPrefs by remember(context) { FeelStore(context).prefs }.collectAsState(initial = FeelPrefs())
    val run = remember { OrbitRun() }
    var phase by remember { mutableStateOf(OrbitPhase.Ready) }
    var paused by remember { mutableStateOf(false) }
    var shownScore by remember { mutableIntStateOf(0) }
    val frame = remember { mutableIntStateOf(0) }
    val latestEnd by rememberUpdatedState(onRunEnd)
    val stick = remember { OrbitStick(Metrics.aimDeadZoneDp) }
    val running = phase == OrbitPhase.Running && active && !paused

    // The page left the screen, or the hub closed: the run stops this instant and waits for a tap.
    LaunchedEffect(active) { if (!active && phase == OrbitPhase.Running) paused = true }
    DisposableEffect(running) {
        host?.playing = running
        if (!running) stick.reset()
        onDispose { host?.playing = false }
    }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = 0L
        var accumulator = 0f
        while (isActive) {
            withFrameNanos { now ->
                if (last == 0L) { last = now; return@withFrameNanos }
                accumulator += ((now - last) / 1_000_000_000f).coerceAtMost(0.1f)
                last = now
                while (accumulator >= OrbitRules.Dt && run.state.phase == OrbitPhase.Running) {
                    accumulator -= OrbitRules.Dt
                    val joystick = host != null && host.joystickEnabled()
                    val input = if (joystick) stick.read(host!!.stickX, host.stickY, host.stickActive)
                        else stick.read(run.dragX, run.dragY, run.dragActive)
                    val next = OrbitSim.step(run.state, input)
                    run.state = next
                    if (next.event and OrbitRules.EventCollected != 0) {
                        run.scoreText = next.score.toString()
                        shownScore = next.score
                        if (host?.hapticsEnabled?.invoke() == true) feel.play(view, FeelEvent.Tick, feelPrefs.copy(sound = SoundTheme.Off))
                    }
                    if (next.phase == OrbitPhase.Over) {
                        if (host?.hapticsEnabled?.invoke() == true) feel.play(view, FeelEvent.Reject, feelPrefs.copy(sound = SoundTheme.Off))
                        phase = OrbitPhase.Over
                        latestEnd(next.score)
                    }
                }
                frame.intValue++
            }
            if (run.state.phase != OrbitPhase.Running) break
        }
    }

    fun tapped() {
        when {
            paused -> paused = false
            phase == OrbitPhase.Ready -> { run.state = OrbitSim.begin(run.state); phase = OrbitPhase.Running }
            phase == OrbitPhase.Over -> { run.restart(); shownScore = 0; run.state = OrbitSim.begin(run.state); phase = OrbitPhase.Running }
        }
        frame.intValue++
    }
    val latestTap by rememberUpdatedState(::tapped)
    val density = LocalDensity.current.density
    val typeface = remember(context) { runCatching { context.resources.getFont(com.brightblu.parc.core.model.R.font.doto_variable) }.getOrNull() }
    val description = when {
        paused -> "Orbit, paused. Score $shownScore. Double tap to continue."
        phase == OrbitPhase.Ready -> "Orbit. Double tap to start."
        phase == OrbitPhase.Over -> "Orbit, run over. Score $shownScore. Best $best. Double tap to play again."
        else -> "Orbit, running. Score $shownScore."
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Metrics.lineGapDp.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
            val side = max(Metrics.arcadeFieldMinDp, min(maxWidth.value, maxHeight.value)).dp
            val painter = remember(skin, density, typeface) { OrbitPainter(skin, density, typeface) }
            Canvas(Modifier.size(side)
                .semantics { contentDescription = description; role = Role.Button; onClick { latestTap(); true } }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val wasRunning = run.state.phase == OrbitPhase.Running && !paused
                        val steersHere = wasRunning && host?.joystickEnabled?.invoke() != true
                        var lifted = false
                        while (!lifted) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) { lifted = true; break }
                            if (steersHere) {
                                run.dragX = (change.position.x - down.position.x) / density
                                run.dragY = (change.position.y - down.position.y) / density
                                run.dragActive = true
                            }
                        }
                        run.dragActive = false; run.dragX = 0f; run.dragY = 0f
                        if (lifted && !wasRunning) latestTap()
                    }
                }) {
                frame.intValue // Subscribed in draw only: a frame redraws the field and recomposes nothing.
                painter.draw(drawContext.canvas.nativeCanvas, run.state, size.width, size.height, run.scoreText, phase == OrbitPhase.Running)
            }
        }
        val line = when {
            paused -> "Paused · tap to continue"
            phase == OrbitPhase.Ready -> "Tap to start"
            phase == OrbitPhase.Over -> "Run over · $shownScore · best ${max(best, shownScore)} · tap to play again"
            else -> ""
        }
        if (line.isNotEmpty()) BasicText(line, style = hudValueStyle(skin), maxLines = 1)
        if (phase == OrbitPhase.Ready) CellLabel(
            if (host?.joystickEnabled?.invoke() == true) "Steer with the hub. Push up or down to hop a ring." else "Drag here to steer. Up or down hops a ring.", skin)
    }
}

/**
 * Draws Orbit on a pure black square. Everything it needs is made once per skin and size; [draw] allocates nothing: it only moves
 * numbers and calls the native canvas with ready paints. Lights use the skin's accent; the player, the barriers and the dots use its text colours.
 */
private class OrbitPainter(skin: Skin, private val density: Float, typeface: Typeface?) {
    private fun paint(color: Long, alpha: Float, style: Paint.Style = Paint.Style.FILL) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toInt(); this.alpha = (255 * alpha).toInt(); this.style = style; strokeWidth = density
    }
    private val dim = paint(skin.colors.textSecondary, Metrics.arcadeDimAlpha)
    private val mark = paint(skin.colors.textSecondary, Metrics.arcadeMarkAlpha)
    private val barrier = paint(skin.colors.textSecondary, 1f)
    private val light = paint(skin.colors.accent, 1f)
    private val halo = paint(skin.colors.accent, 1f, Paint.Style.STROKE)
    private val player = paint(skin.colors.textPrimary, 1f)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skin.colors.textPrimary.toInt(); textAlign = Paint.Align.CENTER
        if (typeface != null) this.typeface = typeface
    }
    private val fractions = floatArrayOf(Metrics.arcadeRingInnerFraction, Metrics.arcadeRingMiddleFraction, Metrics.arcadeRingOuterFraction)
    private var sizeKey = 0f
    private var counts = IntArray(OrbitRules.Rings)
    private var cosTab = Array(OrbitRules.Rings) { FloatArray(0) }
    private var sinTab = Array(OrbitRules.Rings) { FloatArray(0) }
    private val rad = (PI / 180.0).toFloat()

    private fun ensure(side: Float) {
        if (side == sizeKey) return
        sizeKey = side
        for (k in 0 until OrbitRules.Rings) {
            val radius = side * fractions[k]
            val n = max(24, (2f * PI.toFloat() * radius / (Metrics.arcadeDotPitchDp * density)).toInt())
            counts[k] = n
            cosTab[k] = FloatArray(n) { cos(2f * PI.toFloat() * it / n) }
            sinTab[k] = FloatArray(n) { sin(2f * PI.toFloat() * it / n) }
        }
    }

    fun draw(c: NativeCanvas, s: OrbitState, width: Float, height: Float, scoreText: String, live: Boolean) {
        c.drawColor(0xFF000000.toInt())
        val side = min(width, height)
        ensure(side)
        val cx = width / 2f
        val cy = height / 2f
        val dimR = Metrics.arcadeDotDp * density
        val markR = Metrics.arcadeMarkDp * density
        for (k in 0 until OrbitRules.Rings) {
            val r = side * fractions[k]
            val n = counts[k]
            val cs = cosTab[k]; val sn = sinTab[k]
            for (i in 0 until n) c.drawCircle(cx + r * cs[i], cy + r * sn[i], dimR, dim)
            // Bright marks turn with the ring, so its speed and direction can be seen.
            var a = 0f
            while (a < 360f) {
                val angle = (s.phases[k] + a) * rad
                c.drawCircle(cx + r * cos(angle), cy + r * sin(angle), markR, mark)
                a += Metrics.arcadeMarkEveryDeg
            }
        }
        val barrierR = Metrics.arcadeBarrierDotDp * density
        for (i in 0 until s.barrierCount) {
            val k = s.barrierRing[i]
            val r = side * fractions[k]
            val centre = s.phases[k] + s.barrierLocal[i]
            val arcPx = 2f * OrbitRules.BarrierHalf * rad * r
            val steps = max(2, (arcPx / (Metrics.arcadeBarrierPitchDp * density)).toInt())
            for (j in 0..steps) {
                val angle = (centre - OrbitRules.BarrierHalf + 2f * OrbitRules.BarrierHalf * j / steps) * rad
                c.drawCircle(cx + r * cos(angle), cy + r * sin(angle), barrierR, barrier)
            }
        }
        val pulse = 0.5f + 0.5f * sin(2f * PI.toFloat() * (s.tick % Metrics.arcadeLightPulseTicks) / Metrics.arcadeLightPulseTicks)
        for (slot in 0 until OrbitRules.LightSlots) {
            val k = s.lightRing[slot]
            if (k < 0) continue
            val r = side * fractions[k]
            val angle = (s.phases[k] + s.lightLocal[slot]) * rad
            val x = cx + r * cos(angle); val y = cy + r * sin(angle)
            c.drawCircle(x, y, Metrics.arcadeLightDp * density, light)
            halo.alpha = (255 * (0.25f + 0.5f * pulse)).toInt()
            c.drawCircle(x, y, Metrics.arcadeLightHaloDp * density * (0.9f + 0.2f * pulse), halo)
        }
        var pr = side * fractions[s.ring]
        if (s.hopTicks > 0) {
            val t = 1f - s.hopTicks.toFloat() / OrbitRules.HopAnimationTicks
            val eased = t * t * (3f - 2f * t)
            val from = side * fractions[s.hopFrom]
            pr = from + (pr - from) * eased
        }
        val pa = s.playerAngle() * rad
        c.drawCircle(cx + pr * cos(pa), cy + pr * sin(pa), Metrics.arcadePlayerDp * density, player)
        text.textSize = side * Metrics.arcadeScoreFraction
        text.alpha = (255 * (if (live) Metrics.arcadeScoreDimAlpha else 1f)).toInt()
        c.drawText(scoreText, cx, cy + text.textSize * 0.35f, text)
    }
}
