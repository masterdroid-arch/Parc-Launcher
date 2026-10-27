package com.brightblu.parc.ring

import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.Metrics

/**
 * OPUS8 (T133): when a hold on the ring field counts as a long-press on EMPTY space (which opens
 * the small Home menu). Pure, so every clash is unit-tested:
 *
 * - only on Home (not in a folder, All apps or the retired Recents) and never in edit mode, where
 *   a tap on an empty spot already means "add here";
 * - never when the finger starts on the hub (hub hold and the joystick aim own it), on an app or
 *   folder slot (its own menu), or on the search button;
 * - the All-apps letter arc never reaches the renderer (OPUS5 `ignoreDown`), and the hub panel and
 *   menus are separate layers above the field, so they never get here either;
 * - the hold is dropped as soon as the finger travels past the tap slop (search pull-down, swipe
 *   up, turning an arc), when a ring starts turning, or when a second finger lands.
 */
object EmptySpacePress {
    fun armed(layer: Layer, editing: Boolean, onHub: Boolean, onSlot: Boolean, onSearchButton: Boolean,
        offered: Boolean): Boolean =
        offered && layer == Layer.Home && !editing && !onHub && !onSlot && !onSearchButton

    /** Whether a hold that was armed is still a hold after this much travel (dp). */
    fun holds(travelDp: Float, rotating: Boolean, fingers: Int): Boolean =
        travelDp <= SlopDp && !rotating && fingers <= 1

    /** The same slop the renderer uses to tell a tap from a drag. */
    val SlopDp: Float get() = Metrics.gridDp * 2
}
