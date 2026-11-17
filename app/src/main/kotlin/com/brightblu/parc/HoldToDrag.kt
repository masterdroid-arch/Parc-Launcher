package com.brightblu.parc

import com.brightblu.parc.model.Metrics

/**
 * 1.2.1: the one decision "does this touch start a drag?", shared by the settings list (rows) and the hub tab strip.
 * A drag starts only when a finger that went down on a drag handle stays there (within the touch slop) for the hold time.
 * Anything else, a quick touch, a swipe that starts moving at once, or a touch anywhere but the handle, is left alone so
 * the list scrolls as normal. Pure: the composables only feed it what they measure.
 */
internal object HoldToDrag {
    enum class Decision {
        /** Not decided yet: the finger is down, has not moved past the slop and has not been held long enough. */
        Wait,
        /** The hold completed: lift the row and let the finger move it. */
        Start,
        /** Not a drag: scrolling, a tap, or a touch outside the handle. Never takes over this finger. */
        Scroll,
    }

    /**
     * @param heldMs how long the finger has been down
     * @param movedPx how far it has moved from where it went down
     * @param slopPx the touch slop
     * @param onHandle whether the finger went down on the drag handle
     * @param released whether the finger has already lifted
     */
    fun decide(heldMs: Long, movedPx: Float, slopPx: Float, onHandle: Boolean, released: Boolean = false,
        holdMs: Long = Metrics.longPressMs): Decision = when {
        !onHandle -> Decision.Scroll
        movedPx > slopPx -> Decision.Scroll
        released -> Decision.Scroll
        heldMs >= holdMs -> Decision.Start
        else -> Decision.Wait
    }
}
