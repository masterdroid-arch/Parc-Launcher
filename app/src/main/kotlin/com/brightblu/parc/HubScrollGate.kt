package com.brightblu.parc

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brightblu.parc.model.Metrics

/** Tracks the currently composed panel scrollables, including the Comms timeline. */
internal class HubScrollGate {
    // Snapshot state, so the bottom fade (drawn from this) follows pages coming and going.
    private val states = mutableStateListOf<ScrollableState>()
    val atTop get() = states.all { !it.canScrollBackward }
    /** True while any composed page scrollable has more content below. Index loop: read from draw. */
    val canScrollForward: Boolean get() {
        for (i in states.indices) if (states[i].canScrollForward) return true
        return false
    }
    fun add(state: ScrollableState) { if (state !in states) states.add(state) }
    fun remove(state: ScrollableState) { states.remove(state) }
}

internal val LocalHubScrollGate = staticCompositionLocalOf<HubScrollGate?> { null }

@Composable
internal fun RegisterHubScroll(state: ScrollableState) {
    val gate = LocalHubScrollGate.current
    DisposableEffect(gate, state) {
        gate?.add(state)
        onDispose { gate?.remove(state) }
    }
}

@Composable
internal fun rememberHubScrollState(): ScrollState = rememberScrollState().also { RegisterHubScroll(it) }

/**
 * T167: the one way a stats page scrolls. The end padding lives inside the scrolled content, so at the
 * end of the scroll the last row stands [HubPageScroll.endPaddingDp] above the panel's bottom edge:
 * clear of the fade (and 16 dp beyond it).
 */
@Composable
internal fun Modifier.hubPageScroll(): Modifier =
    verticalScroll(rememberHubScrollState()).padding(bottom = HubPageScroll.endPaddingDp.dp)

internal enum class HubDragLock { Undecided, Horizontal, Vertical }

/** Pure rules for the scrolling stats pages; the composables above and in HubPages only apply them. */
internal object HubPageScroll {
    /** Fade plus the gap that must remain between the last row and the fade. */
    const val endPaddingDp = Metrics.hubFadeHeightDp + Metrics.hubScrollEndGapDp

    /** Distance the content can move: its height (end padding included) minus the viewport. */
    fun maxScrollDp(contentDp: Float, viewportDp: Float): Float = (contentDp - viewportDp).coerceAtLeast(0f)

    /** Gap between the last row's bottom edge and the top of the fade when scrolled to the end. */
    fun gapAboveFadeAtEndDp(contentDp: Float, viewportDp: Float): Float {
        val lastRowBottom = contentDp - endPaddingDp
        val atEnd = lastRowBottom - maxScrollDp(contentDp, viewportDp)
        return (viewportDp - Metrics.hubFadeHeightDp) - atEnd
    }

    /** The fade shows only while something is below the viewport. */
    fun fadeVisible(value: Int, maxValue: Int): Boolean = value < maxValue

    /** Which way a drag goes once it has left the touch slop. A steep or diagonal drag scrolls. */
    fun lock(dx: Float, dy: Float, slop: Float): HubDragLock {
        val ax = kotlin.math.abs(dx)
        val ay = kotlin.math.abs(dy)
        return when {
            maxOf(ax, ay) < slop -> HubDragLock.Undecided
            ax > ay * Metrics.hubDirectionLockRatio -> HubDragLock.Horizontal
            else -> HubDragLock.Vertical
        }
    }
}
