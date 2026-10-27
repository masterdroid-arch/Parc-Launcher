package com.brightblu.parc.ring

import com.brightblu.parc.model.Layer
import kotlin.math.abs

/**
 * OPUS8 (T133): app names on the FRONT icon of each arc, in the All-apps ring and in open folders
 * (the owner's answer 5: front icons only, so the ring stays calm). Home keeps today's names (folder
 * captions and the touched icon only).
 *
 * "Front" is window slot 0, the place a turning ring brings an item to (OPUS4): for a ring that
 * turns it is the item nearest slot 0 for the live offset; for an arc that does not turn it is the
 * occupied slot with the lowest index. Pure and allocation-free: the renderer calls these from its
 * draw loop with preallocated arrays.
 */
object FrontNames {
    /** Only the All-apps ring and folders show front names. */
    fun shown(layer: Layer): Boolean = layer == Layer.AllApps || layer == Layer.Folder

    /**
     * The item index at the front of one arc, or -1 for an empty arc. [lowestItem] is the lowest
     * occupied item index (used when the arc does not turn).
     */
    fun frontItem(rotates: Boolean, offset: Float, count: Int, lowestItem: Int): Int = when {
        count <= 0 -> -1
        rotates -> RingRotation.frontItem(offset, count)
        else -> lowestItem
    }

    /**
     * How visible the front name is while the ring turns: full when the item sits on slot 0,
     * gone half-way to the next slot, so two names never show at once.
     */
    fun fade(windowPosition: Float): Float = (1f - abs(windowPosition) * 2f).coerceIn(0f, 1f)

    /** True when a label rectangle (dp, top-left + size) touches a disc of [radius] at (cx, cy). */
    fun rectHitsDisc(left: Float, top: Float, width: Float, height: Float, cx: Float, cy: Float, radius: Float): Boolean {
        val nx = cx.coerceIn(left, left + width)
        val ny = cy.coerceIn(top, top + height)
        val dx = cx - nx
        val dy = cy - ny
        return dx * dx + dy * dy < radius * radius
    }

    /** True when two label rectangles overlap. */
    fun rectsOverlap(l1: Float, t1: Float, w1: Float, h1: Float, l2: Float, t2: Float, w2: Float, h2: Float): Boolean =
        l1 < l2 + w2 && l2 < l1 + w1 && t1 < t2 + h2 && t2 < t1 + h1
}
