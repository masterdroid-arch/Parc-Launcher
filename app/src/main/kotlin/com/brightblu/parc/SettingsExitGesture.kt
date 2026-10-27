package com.brightblu.parc

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import com.brightblu.parc.model.Metrics
import kotlin.math.abs

/** One clock domain (pointer uptime); a scroll which began before the end cannot dismiss. */
internal class SettingsExitGesture {
    private var startX = 0f
    private var startY = 0f
    private var startTime = 0L
    private var edge = false
    private var scrolling = false
    private var scrollBlocked = false
    private var travel = 0f
    private var finished = false
    fun down(x: Float, y: Float, height: Float, now: Long) {
        startX = x; startY = y; startTime = now
        edge = y >= height - Metrics.settingsEdgeZoneDp && y <= height - Metrics.keepOutBottomDp
        scrolling = false; scrollBlocked = false; travel = 0f; finished = false
    }
    fun release(x: Float, y: Float, now: Long): Boolean {
        val dy = startY - y
        val velocity = dy * 1000f / (now - startTime).coerceAtLeast(1)
        val exit = !finished && edge && dy >= Metrics.settingsExitTravelDp &&
            dy > abs(x - startX) * Metrics.settingsAxisRatio && velocity >= Metrics.settingsExitVelocityDp
        if (exit) finished = true
        return exit
    }
    fun scroll(consumed: Float, available: Float) {
        if (!scrolling) { scrolling = true; scrollBlocked = consumed != 0f }
        if (consumed != 0f) scrollBlocked = true
        if (available < 0f) travel -= available
    }
    fun fling(velocityY: Float): Boolean {
        val exit = !finished && scrolling && !scrollBlocked &&
            travel >= Metrics.settingsExitTravelDp && velocityY <= -Metrics.settingsExitVelocityDp
        if (exit) finished = true
        return exit
    }
}

internal class SettingsBackTarget { var action: (() -> Unit)? = null }
internal val LocalSettingsBackTarget = staticCompositionLocalOf<SettingsBackTarget?> { null }

@Composable
internal fun RegisterSettingsBack(onBack: () -> Unit) {
    val target = LocalSettingsBackTarget.current
    val latest by rememberUpdatedState(onBack)
    DisposableEffect(target) {
        val action = { latest() }
        target?.action = action
        onDispose { if (target?.action === action) target.action = null }
    }
}

@Composable
internal fun Modifier.settingsExit(onBack: () -> Unit): Modifier {
    val density = LocalDensity.current.density
    val latest by rememberUpdatedState(onBack)
    val gate = remember { SettingsExitGesture() }
    val connection = remember(density) { object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) gate.scroll(consumed.y / density, available.y / density)
            return Offset.Zero
        }
        override suspend fun onPreFling(available: Velocity): Velocity {
            if (gate.fling(available.y / density)) { latest(); return available }
            return Velocity.Zero
        }
    } }
    return nestedScroll(connection).pointerInput(density) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                if (change.changedToDownIgnoreConsumed())
                    gate.down(change.position.x / density, change.position.y / density, size.height / density, change.uptimeMillis)
                if (change.changedToUpIgnoreConsumed() && gate.release(change.position.x / density, change.position.y / density, change.uptimeMillis)) {
                    change.consume(); latest()
                }
            }
        }
    }
}
