package com.brightblu.parc

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.brightblu.parc.feature.settings.ui.*
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.Springs
import kotlinx.coroutines.isActive

/**
 * 1.2.1: the main Hub pages settings screen. One row per hub page, in Home order: the page name, a Shown/Hidden switch and
 * a drag handle. Tapping the row (not the handle, not the switch) opens that page's own options. A row is dragged only
 * after a press and hold on its handle ([HoldToDrag]); a quick touch or a swipe anywhere, the handle included, scrolls the
 * list. While a row is lifted the list scrolls itself near its top and bottom edges, and on release the row settles into
 * its slot with the sheet spring.
 *
 * [scroll] is the state of the page's vertical scroll; [viewport] gives the on-screen bounds of that scroll area.
 */
@Composable
internal fun HubPagesMainList(skin: Skin, config: HubConfig, update: ((HubConfig) -> HubConfig) -> Unit,
    scroll: ScrollState, viewport: () -> Rect?, onOpen: (String) -> Unit) {
    val undo = rememberEditorUndo()
    val haptics = LocalHapticFeedback.current
    val slop = LocalViewConfiguration.current.touchSlop
    val density = LocalDensity.current.density
    var order by remember(config.orderedPages) { mutableStateOf(config.orderedPages) }
    val starts = remember(order) { FloatArray(order.size) }
    val sizes = remember(order) { FloatArray(order.size) }
    var dragPage by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var scrollAtLift by remember { mutableIntStateOf(0) }
    var fingerWindowY by remember { mutableFloatStateOf(0f) }
    var released by remember { mutableStateOf(false) }
    var settleFrom by remember { mutableFloatStateOf(0f) }
    var settleTo by remember { mutableFloatStateOf(0f) }
    var lockedTarget by remember { mutableIntStateOf(0) }
    val settle = remember { Animatable(0f) }
    val spec = spring<Float>(Springs.sheet.damping, Springs.sheet.stiffness)
    fun liveOffset() = dragDy + (scroll.value - scrollAtLift)
    fun dragOffset() = if (released) settleFrom + (settleTo - settleFrom) * settle.value else liveOffset()
    val from = order.indexOf(dragPage)
    val target = if (from < 0) 0 else if (released) lockedTarget else HubReorder.insertIndex(starts, sizes, from, liveOffset())
    val newStarts = if (from >= 0) HubReorder.reflow(starts, sizes, from, target) else null
    // A held row owns the finger: the Home gestures stand down (HubDragPriority).
    var priorityHeld by remember { mutableStateOf(false) }
    fun takePriority() { if (!priorityHeld) { priorityHeld = true; HubDragPriority.begin() } }
    fun releasePriority() { if (priorityHeld) { priorityHeld = false; HubDragPriority.end() } }
    DisposableEffect(Unit) { onDispose { releasePriority() } }
    fun letGo() {
        releasePriority()
        val moved = dragPage ?: return
        val f = order.indexOf(moved)
        if (released || f < 0) return
        val offset = liveOffset()
        val to = HubReorder.insertIndex(starts, sizes, f, offset)
        settleFrom = offset
        settleTo = HubReorder.reflow(starts, sizes, f, to)[f] - starts[f]
        lockedTarget = to
        released = true
    }
    // After the release the held row settles into its slot, then the new order is saved in one step.
    LaunchedEffect(released) {
        if (!released) return@LaunchedEffect
        settle.animateTo(1f, spec)
        val moved = dragPage
        val f = order.indexOf(moved)
        if (moved != null && f >= 0 && lockedTarget != f) {
            order = HubReorder.moveTo(order, moved, lockedTarget)
            update { it.movePageTo(moved, lockedTarget) }
        }
        dragPage = null; dragDy = 0f; released = false
        settle.snapTo(0f)
    }
    // The list scrolls itself while the held row is near the top or bottom of the visible area.
    LaunchedEffect(dragPage, released) {
        if (dragPage == null || released) return@LaunchedEffect
        val zone = Metrics.settingsDragEdgeZoneDp * density
        val perSecond = Metrics.settingsDragScrollDpPerSecond * density
        var last = 0L
        while (isActive) {
            val now = withFrameNanos { it }
            val view = viewport()
            if (last != 0L && view != null) {
                val step = HubReorder.edgeScroll(fingerWindowY, fingerWindowY, view.top, view.bottom, zone, perSecond * (now - last) / 1e9f)
                if (step != 0f) scroll.scrollBy(step)
            }
            last = now
        }
    }
    if (undo.action != null) SettingsCard(skin) {
        SettingRow("Undo", skin, SettingKind.Action, description = "Undo the last change", divider = false) { undo.run() }
    }
    BasicText("Use the switch to show a page, the gear for its options. Press and hold ≡, then drag, to move a page.",
        style = settingsText(skin, skin.colors.textSecondary, true),
        modifier = Modifier.padding(horizontal = Metrics.settingsRowInsetDp.dp))
    BasicText("On Home: " + config.pages.joinToString(" · "), style = settingsText(skin, skin.colors.accent, true),
        modifier = Modifier.padding(horizontal = Metrics.settingsRowInsetDp.dp))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
        order.forEachIndexed { index, page -> key(page) {
            val shown = page !in config.hiddenPages
            val held = page == dragPage
            val name = hubPageName(page)
            val shift = remember { Animatable(0f) }
            val shiftTarget = if (newStarts != null && !held && index < starts.size) newStarts[index] - starts[index] else 0f
            LaunchedEffect(shiftTarget, dragPage != null) { if (dragPage != null) shift.animateTo(shiftTarget, spec) else shift.snapTo(0f) }
            var handle by remember { mutableStateOf<LayoutCoordinates?>(null) }
            Box(Modifier.zIndex(if (held) 1f else 0f).onGloballyPositioned {
                if (index < starts.size) { starts[index] = it.positionInParent().y; sizes[index] = it.size.height.toFloat() }
            }) {
                Box(Modifier.graphicsLayer { translationY = if (held) dragOffset() else if (dragPage != null) shift.value else 0f }) {
                    SettingsCard(skin) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).heightIn(min = Metrics.touchTargetDp.dp)
                                .padding(start = Metrics.settingsRowInsetDp.dp, top = Metrics.rowGapDp.dp, bottom = Metrics.rowGapDp.dp),
                                verticalArrangement = Arrangement.Center) {
                                BasicText(name, style = settingsText(skin))
                                BasicText(if (shown) "Shown" else "Hidden",
                                    style = settingsText(skin, skin.colors.textSecondary, true),
                                    modifier = Modifier.padding(top = Metrics.lineGapDp.dp))
                            }
                            SettingsSwitch(shown, skin, "$name, shown on Home") { on ->
                                update { it.showPage(page, on) }
                                if (!on) undo.offer { update { it.showPage(page, true) } }
                            }
                            // T375: the page's own options have their own 48 dp target beside its switch.
                            Box(Modifier.size(Metrics.touchTargetDp.dp)
                                .clickable(role = Role.Button, onClickLabel = "Open $name options") { onOpen(page) }
                                .semantics { contentDescription = "$name options" }, contentAlignment = Alignment.Center) {
                                SettingsGroupGlyph(SettingsIcon.Gear, skin)
                            }
                            // The handle: only a press and hold here lifts the row. TalkBack gets the two step actions instead.
                            Box(Modifier.width(Metrics.settingsHandleWidthDp.dp).heightIn(min = Metrics.touchTargetDp.dp)
                                .onGloballyPositioned { handle = it }
                                .semantics {
                                    contentDescription = "Reorder $name page. Press and hold, then drag"
                                    customActions = listOf(
                                        CustomAccessibilityAction("Move earlier") { if (index > 0) { update { it.movePage(page, -1) }; true } else false },
                                        CustomAccessibilityAction("Move later") { if (index < order.lastIndex) { update { it.movePage(page, 1) }; true } else false })
                                }
                                .pointerInput(page, order) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        if (dragPage != null) return@awaitEachGesture
                                        var moved = 0f
                                        var scrolled = false
                                        val stillDown = withTimeoutOrNull(Metrics.longPressMs) {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == down.id }
                                                if (change == null) { scrolled = true; break }
                                                moved = (change.position - down.position).getDistance()
                                                val decision = HoldToDrag.decide(change.uptimeMillis - down.uptimeMillis, moved, slop,
                                                    onHandle = true, released = !change.pressed)
                                                if (decision == HoldToDrag.Decision.Scroll || change.isConsumed) { scrolled = true; break }
                                            }
                                        }
                                        // Not held: a tap or a swipe. Nothing was consumed, so the list scrolls as normal.
                                        if (scrolled || stillDown != null ||
                                            HoldToDrag.decide(Metrics.longPressMs, moved, slop, onHandle = true) != HoldToDrag.Decision.Start) return@awaitEachGesture
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        takePriority()
                                        dragDy = 0f; scrollAtLift = scroll.value; released = false; dragPage = page
                                        fingerWindowY = handle?.localToWindow(down.position)?.y ?: 0f
                                        try {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                val dy = change.position.y - change.previousPosition.y
                                                change.consume()
                                                if (!change.pressed) break
                                                dragDy += dy
                                                handle?.localToWindow(change.position)?.let { fingerWindowY = it.y }
                                            }
                                        } finally { letGo() }
                                    }
                                }, contentAlignment = Alignment.Center) {
                                BasicText("≡", style = settingsText(skin, skin.colors.accent))
                            }
                        }
                    }
                }
            }
        } }
    }
}
