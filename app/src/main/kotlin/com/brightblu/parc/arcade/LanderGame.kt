package com.brightblu.parc.arcade

import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The mutable side of one Lander session; lives in a `remember`. The draw reads [state] directly, so nothing recomposes per frame. */
private class LanderRun {
    private var seed = System.nanoTime()
    var state: LanderState = LanderSim.fresh(seed)
    var levelText: String = state.level.toString()
    var dragX = 0f
    var dragY = 0f
    var dragActive = false
    var thrusting = false

    fun restart() {
        seed = System.nanoTime(); state = LanderSim.fresh(seed); levelText = state.level.toString(); thrusting = false
    }

    fun nextLevel() {
        state = LanderSim.nextLevel(state); levelText = state.level.toString(); thrusting = false
    }
}

/**
 * T211: Lander, the second game in the arcade. A small ship falls toward a flat pad; the stick tilts it and spends its
 * limited fuel to thrust. Touch the pad slowly enough to land, and the score is the fuel left plus the softness of the
 * touch. Terrain and wind come from the level's seed and get harder as the levels go up. Rules are in [LanderSim]; this
 * file is input, the frame loop and the drawing. Nothing runs while [active] is false or the run is not live.
 */
@Composable
internal fun LanderGame(skin: Skin, active: Boolean, best: Int, onRunEnd: (Int) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val host = LocalArcadeHost.current
    val feel = rememberFeelPlayer()
    val feelPrefs by remember(context) { FeelStore(context).prefs }.collectAsState(initial = FeelPrefs())
    val run = remember { LanderRun() }
    var phase by remember { mutableStateOf(LanderPhase.Ready) }
    var paused by remember { mutableStateOf(false) }
    var shownScore by remember { mutableIntStateOf(0) }
    var shownLevel by remember { mutableIntStateOf(1) }
    val frame = remember { mutableIntStateOf(0) }
    val latestEnd by rememberUpdatedState(onRunEnd)
    val stick = remember { LanderStick(Metrics.aimDeadZoneDp) }
    val running = phase == LanderPhase.Running && active && !paused

    // The page left the screen, or the hub closed: the run stops this instant and waits for a tap.
    LaunchedEffect(active) { if (!active && phase == LanderPhase.Running) paused = true }
    DisposableEffect(running) {
        host?.playing = running
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
                while (accumulator >= LanderRules.Dt && run.state.phase == LanderPhase.Running) {
                    accumulator -= LanderRules.Dt
                    val joystick = host != null && host.joystickEnabled()
                    val input = if (joystick) stick.read(host!!.stickX, host.stickY, host.stickActive)
                        else stick.read(run.dragX, run.dragY, run.dragActive)
                    val next = LanderSim.step(run.state, input)
                    run.state = next
                    run.thrusting = input.thrust && next.phase == LanderPhase.Running && next.fuel > 0f
                    if (next.phase == LanderPhase.Landed) {
                        if (host?.hapticsEnabled?.invoke() == true) feel.play(view, FeelEvent.Confirm, feelPrefs.copy(sound = SoundTheme.Off))
                        shownScore = next.score
                        phase = LanderPhase.Landed
                        latestEnd(next.score)
                    } else if (next.phase == LanderPhase.Crashed) {
                        if (host?.hapticsEnabled?.invoke() == true) feel.play(view, FeelEvent.Reject, feelPrefs.copy(sound = SoundTheme.Off))
                        phase = LanderPhase.Crashed
                    }
                }
                frame.intValue++
            }
            if (run.state.phase != LanderPhase.Running) break
        }
    }

    fun tapped() {
        when {
            paused -> paused = false
            phase == LanderPhase.Ready -> {
                run.state = LanderSim.begin(run.state); shownLevel = run.state.level; phase = LanderPhase.Running
            }
            phase == LanderPhase.Landed -> {
                run.nextLevel(); run.state = LanderSim.begin(run.state); shownLevel = run.state.level
                shownScore = 0; phase = LanderPhase.Running
            }
            phase == LanderPhase.Crashed -> {
                run.restart(); run.state = LanderSim.begin(run.state); shownLevel = 1; shownScore = 0; phase = LanderPhase.Running
            }
        }
        frame.intValue++
    }
    val latestTap by rememberUpdatedState(::tapped)
    val density = LocalDensity.current.density
    val painter = remember(skin, density, context) {
        LanderPainter(skin, density,
            context.resources.getFont(com.brightblu.parc.core.model.R.font.figtree_variable),
            context.resources.getFont(com.brightblu.parc.core.model.R.font.doto_variable))
    }
    val joystickOn = host?.joystickEnabled?.invoke() == true
    val description = when {
        paused -> "Lander, paused. Level $shownLevel. Double tap to continue."
        phase == LanderPhase.Ready -> "Lander. Level $shownLevel. Land ${LanderPresentation.landingLimit()}. Double tap to start."
        phase == LanderPhase.Landed -> "Lander, landed. Score $shownScore. Double tap for the next level."
        phase == LanderPhase.Crashed -> "Lander, crashed. Double tap to try again."
        else -> "Lander, running. Level $shownLevel. " +
            if (joystickOn) "Push up to thrust and left or right to tilt." else "Drag up to thrust and left or right to tilt."
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Metrics.lineGapDp.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val side = max(Metrics.arcadeFieldMinDp, min(maxWidth.value, maxHeight.value)).dp
            Canvas(Modifier.size(side)
                .semantics { contentDescription = description; role = Role.Button; onClick { latestTap(); true } }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val wasRunning = run.state.phase == LanderPhase.Running && !paused
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
                painter.draw(drawContext.canvas.nativeCanvas, run.state, size.width, size.height, run.levelText,
                    run.thrusting && phase == LanderPhase.Running && !paused)
            }
        }
        val headline: String
        val hint: String
        when {
            paused -> { headline = "Paused · level $shownLevel"; hint = "Tap to continue" }
            phase == LanderPhase.Ready -> {
                headline = "Level $shownLevel · tap to start"
                hint = (if (joystickOn) "Push up to thrust, left and right to tilt. " else "Drag up to thrust, left and right to tilt. ") +
                    "Land on the pad ${LanderPresentation.landingLimit()}."
            }
            phase == LanderPhase.Landed -> {
                headline = "Landed · score $shownScore"
                hint = "Fuel left plus softness. Tap for level ${shownLevel + 1}."
            }
            phase == LanderPhase.Crashed -> {
                headline = "Crashed · tap to try again"
                hint = "Touch the pad ${LanderPresentation.landingLimit()}. Fuel left adds to the score."
            }
            else -> {
                headline = "Level $shownLevel · best ${max(best, shownScore)}"
                hint = (if (joystickOn) "Push up to thrust · left and right tilt" else "Drag up to thrust · left and right tilt") +
                    " · land ${LanderPresentation.landingLimit()}"
            }
        }
        BasicText(headline, style = hudValueStyle(skin), maxLines = 1)
        CellLabel(hint, skin)
    }
}

/**
 * Draws Lander on a pure black square. Everything it needs is made once per skin and size; [draw] allocates nothing:
 * it only moves numbers and calls the native canvas with ready paints. Terrain is faint, the pad and the flame use the
 * skin's accent, and the ship and the gauge dots use its text colours. Fuel, speed and wind are drawn as small labelled
 * gauges so no value is a bare number.
 */
private class LanderPainter(skin: Skin, private val density: Float, wordFace: Typeface, numberFace: Typeface) {
    private fun paint(color: Long, alpha: Float, style: Paint.Style = Paint.Style.FILL) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toInt(); this.alpha = (255 * alpha).toInt(); this.style = style; strokeWidth = density
    }
    private val dim = paint(skin.colors.textSecondary, Metrics.arcadeDimAlpha)
    private val mark = paint(skin.colors.textSecondary, Metrics.arcadeMarkAlpha)
    private val pad = paint(skin.colors.accent, 1f)
    private val ship = paint(skin.colors.textPrimary, 1f)
    private val flame = paint(skin.colors.accent, 0.9f)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skin.colors.textSecondary.toInt(); textAlign = Paint.Align.LEFT
        typeface = wordFace
    }
    private val rightLabel = Paint(label).apply { textAlign = Paint.Align.RIGHT }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skin.colors.textPrimary.toInt(); textAlign = Paint.Align.RIGHT
        typeface = numberFace
    }
    private val limitLabel = "LAND UNDER ${(LanderRules.MaxLandingSpeed * 100f).roundToInt()}"
    private val gaugeDots = 10

    fun draw(c: NativeCanvas, s: LanderState, width: Float, height: Float, levelText: String, thrusting: Boolean) {
        c.drawColor(0xFF000000.toInt())
        val side = min(width, height)
        if (side <= 0f) return
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        fun px(x: Float) = left + x * side
        fun py(y: Float) = top + (1f - y) * side

        val groundR = Metrics.arcadeDotDp * density
        val pitchPx = Metrics.arcadeDotPitchDp * density
        var step = 0f
        while (step <= side) {
            val x = step / side
            c.drawCircle(left + step, py(LanderSim.groundHeight(s, x)), groundR, dim)
            step += pitchPx
        }

        val padR = Metrics.arcadeLanderPadDotDp * density
        val padPitch = Metrics.arcadeLanderPadPitchDp * density
        var padX = s.padCentre - s.padWidth / 2f
        val padEnd = s.padCentre + s.padWidth / 2f
        while (padX <= padEnd) {
            c.drawCircle(px(padX), py(s.padY), padR, pad)
            padX += padPitch / side
        }
        // A small centre mark so the pad reads as a landing place rather than a line.
        c.drawCircle(px(s.padCentre), py(s.padY + 0.022f), padR * 0.8f, pad)

        val shipR = Metrics.arcadeLanderShipDp * density
        val sx = px(s.x)
        val sy = py(s.y)
        val tilt = s.tilt * shipR * 0.9f
        c.drawCircle(sx + tilt, sy - shipR, shipR * 0.7f, ship)
        c.drawCircle(sx - shipR, sy + shipR * 0.7f, shipR, ship)
        c.drawCircle(sx + shipR, sy + shipR * 0.7f, shipR, ship)
        c.drawCircle(sx + tilt * 0.25f, sy + shipR * 0.2f, shipR * 0.7f, ship)
        if (thrusting) {
            val flameR = Metrics.arcadeLanderFlameDp * density
            c.drawCircle(sx - shipR * 0.4f, sy + shipR * 1.5f, flameR, flame)
            c.drawCircle(sx, sy + shipR * 2.2f, flameR * 1.3f, flame)
            c.drawCircle(sx + shipR * 0.4f, sy + shipR * 1.5f, flameR, flame)
        }

        drawGauges(c, s, left, top, side, levelText)
    }

    private fun drawGauges(c: NativeCanvas, s: LanderState, left: Float, top: Float, side: Float, levelText: String) {
        val margin = side * Metrics.arcadeLanderHudMarginFraction
        val labelSize = side * Metrics.arcadeLanderLabelFraction
        val valueSize = side * Metrics.arcadeLanderValueFraction
        val dotR = Metrics.arcadeLanderGaugeDotDp * density
        val pitch = Metrics.arcadeLanderGaugePitchDp * density
        label.textSize = labelSize
        rightLabel.textSize = labelSize
        value.textSize = valueSize
        val labelY = top + margin + labelSize * 0.8f
        val gaugeY = labelY + pitch * 0.9f

        // Fuel: ten dots, lit from the left.
        label.textAlign = Paint.Align.LEFT
        c.drawText("FUEL", left + margin, labelY, label)
        val lit = (s.fuel / LanderRules.MaxFuel * gaugeDots).roundToInt().coerceIn(0, gaugeDots)
        for (i in 0 until gaugeDots) {
            c.drawCircle(left + margin + i * pitch, gaugeY, dotR, if (i < lit) ship else dim)
        }

        // Speed: a dot on a track with the landing limit marked halfway (the track spans twice the limit).
        rightLabel.textAlign = Paint.Align.RIGHT
        c.drawText("SPEED", left + side - margin, labelY, rightLabel)
        val trackStart = left + side * 0.62f
        val trackEnd = left + side - margin
        val trackSpan = trackEnd - trackStart
        for (i in 0 until gaugeDots) {
            val f = i / (gaugeDots - 1f)
            c.drawCircle(trackStart + f * trackSpan, gaugeY, dotR, dim)
        }
        val limitF = LanderRules.MaxLandingSpeed / (LanderRules.MaxLandingSpeed * 2f)
        c.drawCircle(trackStart + limitF * trackSpan, gaugeY, dotR * 1.6f, pad)
        val speedF = (s.speed / (LanderRules.MaxLandingSpeed * 2f)).coerceIn(0f, 1f)
        c.drawCircle(trackStart + speedF * trackSpan, gaugeY, dotR * 1.2f, mark)
        c.drawText(limitLabel, left + side - margin, gaugeY + pitch, rightLabel)

        // Wind: a dot either side of the middle, so its direction reads without a number.
        val windY = gaugeY + pitch * 1.5f + labelSize
        label.textAlign = Paint.Align.LEFT
        c.drawText("WIND", left + margin, windY, label)
        val windStart = left + margin
        val windSpan = side * 0.30f
        for (i in 0 until gaugeDots) {
            val f = i / (gaugeDots - 1f)
            c.drawCircle(windStart + f * windSpan, windY + pitch * 0.9f, dotR, dim)
        }
        val windF = 0.5f + (s.wind / LanderRules.WindMax).coerceIn(-0.5f, 0.5f)
        c.drawCircle(windStart + windF * windSpan, windY + pitch * 0.9f, dotR * 1.3f, mark)

        // Level: a label and the number, away from the pad.
        c.drawText("LEVEL", left + side - margin, windY, rightLabel)
        c.drawText(levelText, left + side - margin, windY + pitch * 0.9f + valueSize * 0.5f, value)
    }
}
