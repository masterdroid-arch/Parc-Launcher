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
internal fun Modifier.hubPageScroll(state: ScrollState = rememberHubScrollState()): Modifier =
    verticalScroll(state).padding(bottom = HubPageScroll.endPaddingDp.dp)

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

    /** T262: the plain "More below" sign follows the same rule as the fade. */
    fun scrollHintVisible(value: Int, maxValue: Int): Boolean = fadeVisible(value, maxValue)

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

/**
 * T235: the Home search pull belongs to the empty space outside the panel. Inside a hub panel it
 * may start only when the page is already at its top and the finger went down on the grip; a
 * vertical drag anywhere on a scrollable page is the page's own scroll.
 */
internal object HubPullPriority {
    fun startsSearchPull(fromHub: Boolean, atScrollTop: Boolean, onGrip: Boolean,
        blocked: Boolean = false, onControl: Boolean = false): Boolean =
        !blocked && !onControl && (!fromHub || (atScrollTop && onGrip))

    /** The grip sits on the panel's bottom edge, inside the card padding. */
    fun onGrip(yDp: Float, panelBottomDp: Float, cardBottomInsetDp: Float, gripHeightDp: Float): Boolean {
        if (!yDp.isFinite() || !panelBottomDp.isFinite()) return false
        val bottom = panelBottomDp - cardBottomInsetDp.coerceAtLeast(0f)
        val top = bottom - gripHeightDp.coerceAtLeast(0f)
        return yDp >= top && yDp <= bottom
    }
}

/** One remembered first visible cell of a hub page. */
internal data class HubScrollPosition(val index: Int, val offset: Int) {
    init {
        require(index >= 0)
        require(offset >= 0)
    }
}

/**
 * T235: page scroll positions outlive the page composition (a tab switch, the reader covering the
 * hub, or the hub closing). It is deliberately in memory only: the first visit opens at the top.
 */
internal class HubPageScrollMemory {
    private val positions = mutableMapOf<String, HubScrollPosition>()

    fun get(pageId: String): HubScrollPosition? = positions[pageId]

    fun put(pageId: String, index: Int, offset: Int) {
        if (index <= 0 && offset <= 0) positions.remove(pageId)
        else positions[pageId] = HubScrollPosition(index.coerceAtLeast(0), offset.coerceAtLeast(0))
    }

    fun clear() = positions.clear()
}

internal val LocalHubPageScrollMemory = staticCompositionLocalOf<HubPageScrollMemory?> { null }

/** The pure side of the Library shelf's place: clamp a remembered cell to the current book count. */
internal object LibraryScroll {
    fun restore(saved: HubScrollPosition?, itemCount: Int): HubScrollPosition {
        if (saved == null || itemCount <= 0) return HubScrollPosition(0, 0)
        val last = itemCount - 1
        val index = saved.index.coerceIn(0, last)
        val offset = if (index == saved.index) saved.offset else 0
        return HubScrollPosition(index, offset)
    }
}
