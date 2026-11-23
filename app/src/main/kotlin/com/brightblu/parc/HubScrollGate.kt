package com.brightblu.parc

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brightblu.parc.model.Metrics

/**
 * Tracks the currently composed panel scrollables, including the Comms timeline.
 *
 * T378: each entry also reports its scroll progress and how much of the content its viewport shows.
 * The values are functions, so draw code can read them without recomposing the page on every frame.
 */
internal fun interface ScrollReading { fun read(): Float }

internal class HubScrollGate {
    private class Entry(
        val state: ScrollableState,
        val progress: ScrollReading,
        val viewportFraction: ScrollReading,
    )

    // Snapshot state, so the bottom fade and the thumb (drawn from this) follow pages coming and going.
    private val entries = mutableStateListOf<Entry>()

    val atTop get() = entries.all { !it.state.canScrollBackward }

    /** True while any composed page scrollable has more content below. Index loop: read from draw. */
    val canScrollForward: Boolean get() {
        for (i in entries.indices) if (entries[i].state.canScrollForward) return true
        return false
    }

    /**
     * The scroller the thumb follows: the one with the furthest progress while more is below, else
     * the last composed one (so the thumb can finish its settle at the end of a page). Both getters
     * return plain floats so draw code can read them every frame without allocating.
     */
    private fun active(): Entry? {
        if (entries.isEmpty()) return null
        var chosen = entries.last()
        var best = -1f
        for (i in entries.indices) {
            val entry = entries[i]
            if (!entry.state.canScrollForward) continue
            val progress = entry.progress.read()
            if (progress >= best) { best = progress; chosen = entry }
        }
        return chosen
    }

    val progress: Float get() {
        val entry = active() ?: return 0f
        return entry.progress.read()
    }
    val viewportFraction: Float get() {
        val entry = active() ?: return 1f
        return entry.viewportFraction.read()
    }

    fun add(state: ScrollableState, progress: ScrollReading, viewportFraction: ScrollReading) {
        if (entries.none { it.state === state }) entries.add(Entry(state, progress, viewportFraction))
    }

    fun remove(state: ScrollableState) { entries.removeAll { it.state === state } }
}

internal val LocalHubScrollGate = staticCompositionLocalOf<HubScrollGate?> { null }

@Composable
internal fun RegisterHubScroll(state: ScrollState) {
    val gate = LocalHubScrollGate.current
    DisposableEffect(gate, state) {
        gate?.add(
            state,
            { HubScrollThumb.fraction(state.value, state.maxValue) },
            { HubScrollThumb.viewportFraction(state.viewportSize, state.maxValue) },
        )
        onDispose { gate?.remove(state) }
    }
}

private fun LazyListState.thumb(progress: Boolean): Float {
    val info = layoutInfo
    val items = info.visibleItemsInfo
    val first = items.firstOrNull()
    val last = items.lastOrNull()
    if (first == null || last == null) return if (progress) 0f else 1f
    return HubScrollThumb.lazyReading(progress, canScrollForward, canScrollBackward,
        info.totalItemsCount, items.size, first.index, first.offset, last.offset + last.size,
        info.viewportStartOffset, info.viewportEndOffset)
}

private fun LazyGridState.thumb(progress: Boolean): Float {
    val info = layoutInfo
    val items = info.visibleItemsInfo
    val first = items.firstOrNull()
    val last = items.lastOrNull()
    if (first == null || last == null) return if (progress) 0f else 1f
    return HubScrollThumb.lazyReading(progress, canScrollForward, canScrollBackward,
        info.totalItemsCount, items.size, first.index, first.offset.y, last.offset.y + last.size.height,
        info.viewportStartOffset, info.viewportEndOffset)
}

@Composable
internal fun RegisterHubScroll(state: LazyListState) {
    val gate = LocalHubScrollGate.current
    DisposableEffect(gate, state) {
        gate?.add(state, { state.thumb(true) }, { state.thumb(false) })
        onDispose { gate?.remove(state) }
    }
}

@Composable
internal fun RegisterHubScroll(state: LazyGridState) {
    val gate = LocalHubScrollGate.current
    DisposableEffect(gate, state) {
        gate?.add(state, { state.thumb(true) }, { state.thumb(false) })
        onDispose { gate?.remove(state) }
    }
}

internal val LocalHubFullScreen = staticCompositionLocalOf { false }

// Keep the card's position while the larger viewport measures and clamps its own scroll state.
@Composable
internal fun rememberHubScrollState(): ScrollState {
    val card = rememberScrollState()
    val active = if (LocalHubFullScreen.current) rememberScrollState(card.value) else card
    RegisterHubScroll(active)
    return active
}

@Composable
internal fun rememberHubListState(): LazyListState {
    val card = rememberLazyListState()
    return if (LocalHubFullScreen.current)
        rememberLazyListState(card.firstVisibleItemIndex, card.firstVisibleItemScrollOffset) else card
}

@Composable
internal fun rememberHubGridState(): LazyGridState {
    val card = rememberLazyGridState()
    return if (LocalHubFullScreen.current)
        rememberLazyGridState(card.firstVisibleItemIndex, card.firstVisibleItemScrollOffset) else card
}

/**
 * T167: the one way a stats page scrolls. The end padding lives inside the scrolled content, so at the
 * end of the scroll the last row stands [HubPageScroll.endPaddingDp] above the panel's bottom edge:
 * clear of the drawn fade's opaque band (and 16 dp beyond it).
 */
@Composable
internal fun Modifier.hubPageScroll(state: ScrollState = rememberHubScrollState()): Modifier =
    verticalScroll(state).padding(bottom = HubPageScroll.endPaddingDp.dp)

internal enum class HubDragLock { Undecided, Horizontal, Vertical }

/** Pure rules for the scrolling stats pages; the composables above and in HubPages only apply them. */
internal object HubPageScroll {
    /** The fade occupies only the last few dp of the viewport while more content is below. */
    const val fadeOpaqueDp = Metrics.hubFadeHeightDp

    /** Drawn fade plus the gap that must remain between the last row and the panel's bottom edge. */
    const val endPaddingDp = fadeOpaqueDp + Metrics.hubScrollEndGapDp

    /** Distance the content can move: its height (end padding included) minus the viewport. */
    fun maxScrollDp(contentDp: Float, viewportDp: Float): Float = (contentDp - viewportDp).coerceAtLeast(0f)

    /** Gap between the last row's bottom edge and the top of the fade's opaque band at the end. */
    fun gapAboveFadeAtEndDp(contentDp: Float, viewportDp: Float): Float {
        val lastRowBottom = contentDp - endPaddingDp
        val atEnd = lastRowBottom - maxScrollDp(contentDp, viewportDp)
        return (viewportDp - fadeOpaqueDp) - atEnd
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

/**
 * T378: the pure side of the trailing overflow thumb. A thumb is a share of the track as tall as the
 * viewport's share of the content, moved from the top by the scroll progress. It stays while more is
 * below and for one settle beat after the finger stops; at the end of a page it fades away.
 */
internal object HubScrollThumb {
    /** Progress of a classic scrollable: value over its maximum. A page that fits has no progress. */
    fun fraction(value: Int, maxValue: Int): Float {
        if (maxValue <= 0) return 0f
        return (value.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
    }

    /** How much of the content the viewport shows, which is the thumb's share of the track. */
    fun viewportFraction(viewportPx: Int, maxValuePx: Int): Float {
        val viewport = viewportPx.coerceAtLeast(0).toFloat()
        val content = viewport + maxValuePx.coerceAtLeast(0).toFloat()
        return if (content <= 0f) 1f else (viewport / content).coerceIn(0f, 1f)
    }

    /** Estimate lazy content length from measured cells, including partially visible rows. */
    fun lazyReading(progress: Boolean, forward: Boolean, backward: Boolean, count: Int, visible: Int,
        firstIndex: Int, firstTop: Int, lastBottom: Int, start: Int, end: Int): Float {
        val span = lastBottom - firstTop
        if (visible <= 0 || span <= 0 || count <= 0) return if (progress) 0f else 1f
        val pixelsPerItem = span.toFloat() / visible
        val total = pixelsPerItem * count
        val viewport = (end - start).coerceAtLeast(0)
        if (!progress) return (viewport / total).coerceIn(0f, 1f)
        if (!backward) return 0f
        if (!forward) return 1f
        val offset = firstIndex * pixelsPerItem + start - firstTop
        return (offset / (total - viewport).coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    /** The thumb never shrinks into a speck and never grows past the track. */
    fun heightDp(trackDp: Float, viewportFraction: Float): Float {
        if (trackDp <= 0f) return 0f
        val minimum = Metrics.hubScrollThumbMinDp.coerceAtMost(trackDp)
        return (trackDp * viewportFraction.coerceIn(0f, 1f)).coerceIn(minimum, trackDp)
    }

    /** The thumb's top edge, from 0 at the top of the page to track minus thumb at the end. */
    fun topDp(trackDp: Float, thumbDp: Float, progress: Float): Float =
        (trackDp - thumbDp).coerceAtLeast(0f) * progress.coerceIn(0f, 1f)

    fun visible(canScrollForward: Boolean, settling: Boolean, canScrollBackward: Boolean): Boolean =
        canScrollForward || (settling && canScrollBackward)
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
