package com.brightblu.parc

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.Springs
import com.brightblu.parc.model.textFamily

/**
 * T186: tells the hub's page-swipe handler (an ancestor that sees touches first) that a tab is held for reordering, so
 * horizontal moves of that finger are not a page swipe.
 */
internal class HubReorderGate { var active: Boolean = false }

/**
 * M13 review: a drag that reorders (a held hub tab, or a row handle in the Hub pages settings list) owns its finger. The
 * Home screen's search pull and swipe up, and the settings exit swipe, look here and stand down. [lifts] counts how many
 * drags have started, so a gesture that began before a lift can tell one happened even after it ended.
 */
internal object HubDragPriority {
    private var depth = 0
    var lifts = 0
        private set
    val active: Boolean get() = depth > 0
    fun begin() { depth++; lifts++ }
    fun end() { if (depth > 0) depth-- }
    /** True when a reorder drag is running now or started since [liftsAtDown] was read. */
    fun owns(liftsAtDown: Int): Boolean = active || lifts != liftsAtDown
    fun resetForTest() { depth = 0; lifts = 0 }
}

/**
 * T240: the page-name strip is above the hub's page pager. A touch that begins in the strip belongs to the
 * strip's own horizontal scroll, so a sideways drag there never turns the page; a hold is still the tab drag
 * (T186), and a tap is still the tab's click. The strip's outer row is exactly one [Metrics.menuRowHeightDp]
 * high and is the first child of the hub card.
 */
internal object HubTabGesture {
    fun startsOnStrip(yPx: Float, stripBottomPx: Float): Boolean =
        yPx.isFinite() && stripBottomPx.isFinite() && stripBottomPx > 0f && yPx >= 0f && yPx <= stripBottomPx
}

/** T242: when a hold on a page name means "quick options" and not "I moved it". */
internal object HubTabHold {
    fun isQuickOptions(droppedOnOwnSlot: Boolean, travelPx: Float, slopPx: Float): Boolean =
        droppedOnOwnSlot && travelPx.isFinite() && travelPx <= slopPx
}

/**
 * T158 (DESIGN 3.4): the hub's tabs, one pill per visible page. The selected pill is `surface` with a share of
 * `accent`; it slides to the new tab on `Springs.sheet` (read only in the draw lambda), and the strip scrolls
 * the same way to keep it in view. With the system animator scale at 0 both jump. Pills are half the 48 dp
 * touch row, so their corner is half their height.
 *
 * T186: press and hold a tab (`Metrics.longPressMs`) to lift it; drag sideways, the other tabs slide aside on
 * `Springs.sheet`, the strip scrolls itself near an edge, release to drop. A tap, a swipe or a scroll that starts
 * before the hold ends is not touched. [onReorder] gets the whole new tab order; null turns dragging off. TalkBack
 * gets "Move earlier" and "Move later" on each tab.
 */
@Composable
internal fun HubTabStrip(pages: List<String>, selected: String?, skin: Skin, modifier: Modifier = Modifier,
    reorderGate: HubReorderGate? = null, onLift: () -> Unit = {}, onSlot: () -> Unit = {},
    onReorder: ((List<String>) -> Unit)? = null, onHold: ((String) -> Unit)? = null, onSelect: (String) -> Unit) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current.density
    var order by remember(pages) { mutableStateOf(pages) }
    val starts = remember(order) { FloatArray(order.size) }
    val widths = remember(order) { FloatArray(order.size) }
    val tallest = remember(order) { FloatArray(1) } // pill height in px: grows with the text size
    var measured by remember(order) { mutableIntStateOf(0) }
    // T240: a tap asks the strip to put that name in the middle once its page is selected. The counter makes a tap
    // on the already-selected tab still run the scroll.
    var centreRequest by remember { mutableIntStateOf(0) }
    var centreId by remember { mutableStateOf<String?>(null) }
    // A tapped page that never becomes the selected one (hidden, or filtered out by Focus) must not keep the
    // strip waiting to centre it: once the selection is some other page the request is dropped.
    LaunchedEffect(selected, centreRequest) {
        val waiting = centreId
        if (waiting != null && selected != waiting) {
            kotlinx.coroutines.delay(HubTabs.CentreGiveUpMs)
            if (centreId == waiting && selected != waiting) centreId = null
        }
    }
    val slideX = remember { Animatable(0f) }
    val slideW = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val fill = remember(skin) { Color(HubTabs.selectedFill(skin.colors.surface, skin.colors.accent).toInt()) }
    val index = order.indexOf(selected)
    val spec = spring<Float>(Springs.sheet.damping, Springs.sheet.stiffness)
    // T186: the drag. dragId is the held tab; while it is set the tabs are laid out as before and only drawn shifted.
    var dragId by remember { mutableStateOf<String?>(null) }
    var fromIndex by remember { mutableIntStateOf(0) }
    var downContent by remember { mutableFloatStateOf(0f) }
    var fingerX by remember { mutableFloatStateOf(0f) }
    var released by remember { mutableStateOf(false) }
    var settleFrom by remember { mutableFloatStateOf(0f) }
    var settleTo by remember { mutableFloatStateOf(0f) }
    var lockedTarget by remember { mutableIntStateOf(0) }
    val settle = remember { Animatable(0f) }
    val shifts = remember { HashMap<String, Animatable<Float, AnimationVector1D>>() }
    fun liveOffset() = fingerX + scroll.value - downContent
    fun dragOffset() = if (released) settleFrom + (settleTo - settleFrom) * settle.value else liveOffset()
    val target by remember(order) { derivedStateOf {
        if (dragId == null) 0 else if (released) lockedTarget else HubReorder.insertIndex(starts, widths, fromIndex, liveOffset())
    } }
    val reorderable = onReorder != null && order.size > 1
    BoxWithConstraints(modifier.fillMaxWidth().height(Metrics.menuRowHeightDp.dp)) {
        val viewPx = maxWidth.value * density
        LaunchedEffect(index, measured, viewPx, centreRequest) {
            if (index < 0 || starts.size != order.size || widths[index] <= 0f || dragId != null) return@LaunchedEffect
            val centre = centreId == order[index]
            // The tapped page is not the selected one yet: wait for the selection to arrive, then centre it.
            if (centreId != null && !centre) return@LaunchedEffect
            val scrollTo = if (centre) {
                HubTabs.centreTarget(starts[index], widths[index], viewPx, scroll.maxValue.toFloat())
            } else {
                HubTabs.scrollTarget(starts[index], widths[index], scroll.value.toFloat(), viewPx)
            }
            if (!placed) {
                placed = true
                slideX.snapTo(starts[index]); slideW.snapTo(widths[index])
                scroll.scrollTo(if (centre) scrollTo.toInt()
                    else HubTabs.scrollTarget(starts[index], widths[index], 0f, viewPx).toInt())
            } else {
                coroutineScope {
                    launch { slideX.animateTo(starts[index], spec) }
                    launch { slideW.animateTo(widths[index], spec) }
                    launch { scroll.animateScrollTo(scrollTo.toInt(), spec) }
                }
            }
            if (centre) centreId = null
        }
        // T186: the strip scrolls itself while the held tab is near an edge.
        LaunchedEffect(dragId, released) {
            if (dragId == null || released) return@LaunchedEffect
            val zone = Metrics.hubTabEdgeZoneDp * density
            val perSecond = Metrics.hubTabEdgeScrollDpPerSecond * density
            var last = 0L
            while (isActive) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                if (last != 0L && fromIndex in starts.indices) {
                    val start = starts[fromIndex] + liveOffset() - scroll.value
                    val step = HubReorder.edgeScroll(start, start + widths[fromIndex], 0f, viewPx, zone, perSecond * (now - last) / 1e9f)
                    if (step != 0f) scroll.scrollBy(step)
                }
                last = now
            }
        }
        LaunchedEffect(dragId) { if (dragId != null) { var first = true; snapshotFlow { target }.collect { if (first) first = false else onSlot() } } }
        // T186: after the release the held tab settles into its slot, then the new order is committed in one step.
        LaunchedEffect(released) {
            if (!released) return@LaunchedEffect
            settle.animateTo(1f, spec)
            val from = fromIndex
            val to = lockedTarget
            if (to != from && from in order.indices) {
                val moved = order.toMutableList().also { it.add(to, it.removeAt(from)) }
                val sel = order.indexOf(selected)
                if (sel >= 0 && widths[sel] > 0f) {
                    val newStarts = HubReorder.reflow(starts, widths, from, to)
                    slideX.snapTo(newStarts[sel]); slideW.snapTo(widths[sel])
                }
                order = moved
                onReorder?.invoke(moved)
            }
            dragId = null; released = false
            settle.snapTo(0f)
        }
        val minPill = Metrics.hubTabPillHeightDp * density
        val slop = LocalViewConfiguration.current.touchSlop
        Row(Modifier.pointerInput(order, reorderable) {
            if (!reorderable) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (dragId != null) return@awaitEachGesture
                val contentX = down.position.x + scroll.value
                val hit = order.indices.firstOrNull { widths[it] > 0f && contentX >= starts[it] && contentX < starts[it] + widths[it] }
                    ?: return@awaitEachGesture
                // 1.2.1: the hold decision is HoldToDrag (pure, tested). An ordinary horizontal swipe moves past the touch slop
                // long before the hold time is over, so it is always a scroll or a page swipe and never lifts a tab.
                var cancelled = false
                val held = withTimeoutOrNull(Metrics.longPressMs) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed || HoldToDrag.decide(change.uptimeMillis - down.uptimeMillis,
                                (change.position - down.position).getDistance(), slop, onHandle = true, released = !change.pressed) == HoldToDrag.Decision.Scroll) {
                            cancelled = true; break
                        }
                    }
                }
                if (cancelled || held != null) return@awaitEachGesture
                // Held: from here the strip owns this finger. Taps, swipes and the strip's own scroll never see it.
                reorderGate?.active = true
                HubDragPriority.begin()
                fromIndex = hit; downContent = contentX; fingerX = down.position.x
                released = false
                dragId = order[hit]
                onLift()
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        event.changes.forEach { it.consume() }
                        if (!change.pressed) break
                        fingerX = change.position.x
                    }
                } finally {
                    reorderGate?.active = false
                    HubDragPriority.end()
                    if (dragId != null && !released) {
                        val offset = liveOffset()
                        val to = HubReorder.insertIndex(starts, widths, fromIndex, offset)
                        settleFrom = offset
                        settleTo = HubReorder.reflow(starts, widths, fromIndex, to)[fromIndex] - starts[fromIndex]
                        lockedTarget = to
                        released = true
                        // T242: a hold that never became a drag (the finger stayed on its own name) opens the
                        // quick options for that page instead of just putting the tab back.
                        if (HubTabHold.isQuickOptions(to == fromIndex, abs(fingerX - down.position.x), slop)) {
                            order.getOrNull(fromIndex)?.let { id -> onHold?.invoke(id) }
                        }
                    }
                }
            }
        }.horizontalScroll(scroll).drawBehind {
            val sel = index
            val dragging = dragId != null && sel in starts.indices
            if (dragging && widths[sel] > 0f) {
                val pillHeight = maxOf(minPill, tallest[0])
                val top = (size.height - pillHeight) / 2f
                val shift = if (order[sel] == dragId) dragOffset() else (shifts[order[sel]]?.value ?: 0f)
                drawRoundRect(fill, Offset(starts[sel] + shift, top), Size(widths[sel], pillHeight), CornerRadius(pillHeight / 2f))
            } else if (slideW.value > 0f) {
                val pillHeight = maxOf(minPill, tallest[0])
                val top = (size.height - pillHeight) / 2f
                drawRoundRect(fill, Offset(slideX.value, top), Size(slideW.value, pillHeight), CornerRadius(pillHeight / 2f))
            }
        }, verticalAlignment = Alignment.CenterVertically) {
            val newStarts = if (dragId != null && fromIndex in starts.indices) HubReorder.reflow(starts, widths, fromIndex, target) else null
            order.forEachIndexed { i, id -> key(id) {
                val isSelected = id == selected
                val held = id == dragId
                // T200: the arcade tab springs in when a game has just unlocked (once, the first time the strip draws it after that).
                val entrance = remember { Animatable(if (id == com.brightblu.parc.arcade.ArcadePage.Id && com.brightblu.parc.arcade.ArcadeReveal.take()) 0f else 1f) }
                LaunchedEffect(Unit) { if (entrance.value < 1f) entrance.animateTo(1f, spring(Springs.open.damping, Springs.open.stiffness)) }
                val shift = remember { Animatable(0f) }
                shifts[id] = shift
                val shiftTarget = if (newStarts != null && !held && i in starts.indices) newStarts[i] - starts[i] else 0f
                LaunchedEffect(shiftTarget, dragId != null) {
                    if (dragId != null) shift.animateTo(shiftTarget, spec) else shift.snapTo(0f)
                }
                Box(Modifier.height(Metrics.menuRowHeightDp.dp).zIndex(if (held) 1f else 0f)
                    .semantics {
                        contentDescription = "${hubPageName(id)} tab${if (isSelected) ", selected" else ""}"
                        if (reorderable) customActions = listOf(
                            CustomAccessibilityAction("Move earlier") {
                                if (i == 0) false else { val moved = order.toMutableList().also { it.add(i - 1, it.removeAt(i)) }; order = moved; onReorder?.invoke(moved); true }
                            },
                            CustomAccessibilityAction("Move later") {
                                if (i == order.lastIndex) false else { val moved = order.toMutableList().also { it.add(i + 1, it.removeAt(i)) }; order = moved; onReorder?.invoke(moved); true }
                            })
                    }
                    .clickable(role = Role.Tab) {
                        centreId = id
                        centreRequest++
                        onSelect(id)
                    }
                    .padding(horizontal = (Metrics.hubTabGapDp / 2f).dp)
                    .onGloballyPositioned {
                        val x = it.positionInParent().x
                        val w = it.size.width.toFloat()
                        if (i < starts.size && (starts[i] != x || widths[i] != w)) { starts[i] = x; widths[i] = w; measured++ }
                    },
                    contentAlignment = Alignment.Center) {
                  // Only the drawn pill moves; the touch row keeps its place so the measured positions stay true.
                  Box(Modifier.graphicsLayer {
                        translationX = if (held) dragOffset() else if (dragId != null) shift.value else 0f
                        val lift = (if (held) Metrics.hubTabLiftScale else 1f) * entrance.value
                        scaleX = lift; scaleY = lift
                        alpha = entrance.value.coerceIn(0f, 1f)
                    }.then(if (held && !isSelected) Modifier.background(Color(skin.colors.surface.toInt()), CircleShape) else Modifier)
                    .heightIn(min = Metrics.hubTabPillHeightDp.dp).padding(horizontal = Metrics.hubTabPillPadDp.dp)
                    .onGloballyPositioned {
                        val h = it.size.height.toFloat()
                        if (tallest[0] < h) { tallest[0] = h; measured++ }
                    }, contentAlignment = Alignment.Center) {
                    BasicText(hubPageName(id), maxLines = 1, overflow = TextOverflow.Clip, style = TextStyle(
                        fontFamily = skin.textFamily, fontSize = Metrics.hubTabNameSp.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = Color((if (isSelected) skin.colors.textPrimary else skin.colors.textSecondary).toInt())))
                  }
                }
            } }
        }
    }
}
