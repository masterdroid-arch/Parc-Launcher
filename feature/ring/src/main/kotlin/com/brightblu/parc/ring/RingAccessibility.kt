package com.brightblu.parc.ring

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.brightblu.parc.model.*
import kotlin.math.cos
import kotlin.math.sin

/** Semantic geometry is prepared outside draw, in arc/slot order regardless of sparse map insertion. */
internal data class AccessibleRingSlot(val slot: RingSlot, val hit: SlotHit, val description: String)

internal fun accessibleRingSlots(state: RingState): List<AccessibleRingSlot> {
    if (state.searchOpen || state.accessibilityHidden) return emptyList()
    return state.arcs.sortedBy { it.index }.flatMap { arc ->
        val offset = state.rotations[arc.id] ?: 0f
        val count = state.slots.count { it.position.arcId == arc.id }
        state.slots.filter { slot ->
            slot.position.arcId == arc.id &&
                RingRotation.edgeAlpha(RingRotation.windowPosition(ringItemIndex(slot, state.slots, arc.slotCapacity), offset, count, arc.slotCapacity), arc.slotCapacity) > 0f
        }.sortedBy { slot -> RingRotation.windowPosition(ringItemIndex(slot, state.slots, arc.slotCapacity), offset, count, arc.slotCapacity) }.map { slot ->
            val angle = Math.toRadians(RingRotation.windowAngleDeg(arc, ringItemIndex(slot, state.slots, arc.slotCapacity), offset, count).toDouble())
            val kind = when (slot.slot) {
                is Slot.App -> "app"
                is Slot.Folder -> "folder"
                is Slot.Action -> "action"
                is Slot.Link -> "link"
                is Slot.Shortcut -> "shortcut"
            }
            AccessibleRingSlot(slot, SlotHit(slot.position, slot.slot,
                arc.originXDp + cos(angle).toFloat() * arc.radiusDp,
                arc.originYDp + sin(angle).toFloat() * arc.radiusDp),
                "${slot.label}, $kind, ${slot.unreadCount.coerceAtLeast(0)} unread")
        }
    }
}

/** Transparent semantics siblings leave the renderer's single Canvas and pointer handling intact. */
@Composable
internal fun RingAccessibility(state: RingState, callbacks: RingCallbacks) {
    if (state.searchOpen || state.accessibilityHidden) return
    val nodes = remember(state) { accessibleRingSlots(state) }
    val latest by rememberUpdatedState(callbacks)
    Box(Modifier.fillMaxSize().semantics { isTraversalGroup = true }) {
        nodes.forEachIndexed { index, node ->
            key(state.layer, state.arcs.firstOrNull()?.folderId, node.slot.position) {
                Box(Modifier.offset((node.hit.xDp - Metrics.touchTargetDp / 2).dp,
                    (node.hit.yDp - Metrics.touchTargetDp / 2).dp).size(Metrics.touchTargetDp.dp)
                    .semantics {
                        contentDescription = node.description
                        role = Role.Button
                        traversalIndex = index.toFloat()
                        if (!node.slot.enabled) disabled()
                        else {
                            onClick {
                                latest.onTap(node.hit)
                                true
                            }
                            if (node.slot.slot is Slot.App) customActions = listOf(
                                CustomAccessibilityAction("Show menu") { latest.onLongPress(node.hit); true })
                        }
                    })
            }
        }
        val arc = state.arcs.firstOrNull()
        if (arc != null) {
            val hubLabel = if (state.layer == Layer.Home) "Show all apps" else "Go home"
            Box(Modifier.offset((arc.originXDp - Metrics.hubTouchDp / 2).dp,
                (arc.originYDp - Metrics.hubTouchDp / 2).dp).size(Metrics.hubTouchDp.dp).semantics {
                contentDescription = hubLabel
                role = Role.Button
                traversalIndex = nodes.size.toFloat()
                onClick { latest.onHubTap(); true }
            })
            val button = remember(state.arcs) { RingSearchButtonPlacement(state.arcs) }
            Box(Modifier.offset(button.left.dp, (button.centerY - button.radius).dp)
                .size(Metrics.searchButtonTouchDp.dp).semantics {
                    contentDescription = "Search apps"
                    role = Role.Button
                    traversalIndex = nodes.size + 1f
                    onClick { latest.onSearch(); true }
                })
        }
    }
}
