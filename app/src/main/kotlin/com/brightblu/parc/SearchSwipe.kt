package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import kotlin.math.abs

/**
 * T157: when a drag on the open search sheet closes it. Pure numbers, in dp and dp per second, finger down is positive.
 * A drag up never closes it. A drag down closes it from `searchSwipeDp`, or from `searchFlickMinDp` when it is a fast
 * flick, and only when it is more down than sideways. Whether the list under the finger may still scroll is not decided
 * here: a drag the list scrolls is "consumed" and never reaches this rule (see [SearchSwipe]).
 */
internal object SheetDismissRule {
    fun dismisses(dy: Float, dx: Float, velocityDpPerSecond: Float = 0f): Boolean {
        if (dy <= 0f || dy <= abs(dx)) return false
        if (dy >= Metrics.searchSwipeDp) return true
        return dy >= Metrics.searchFlickMinDp && velocityDpPerSecond >= Metrics.searchFlingVelocityDpPerSecond
    }
}

/**
 * One gesture on the open search sheet; consumed list scrolling disqualifies it even if it subsequently reaches the top.
 * The result list scrolls with the finger; the sheet closes with a drag down only when the list could not use that drag
 * (it sat at its end or had nothing to scroll), or when the drag started off the list (field, links, the space above).
 */
internal class SearchSwipe {
    private var startX = 0f
    private var startY = 0f
    private var eligible = false
    private var fired = false
    private var listScrolled = false
    private var overscroll = 0f

    /** [anywhere]: the sheet is open, so a drag may start in the top band too (that band opens search when it is closed). */
    fun start(x: Float, y: Float, fromHub: Boolean = false, anywhere: Boolean = false) {
        startX = x; startY = y
        eligible = !fromHub && (anywhere || y > Metrics.keepOutTopDp)
        fired = false; listScrolled = false; overscroll = 0f
    }

    fun move(x: Float, y: Float, consumed: Boolean, velocityDpPerSecond: Float = 0f): Boolean =
        !consumed && !listScrolled && trigger(y - startY, x - startX, velocityDpPerSecond)

    fun scroll(availableY: Float, consumedY: Float): Boolean {
        if (consumedY != 0f) listScrolled = true
        if (listScrolled) return false
        overscroll = (overscroll + availableY).coerceAtLeast(0f)
        return trigger(overscroll, 0f, 0f)
    }

    private fun trigger(dy: Float, dx: Float, velocity: Float): Boolean {
        if (!eligible || fired || !SheetDismissRule.dismisses(dy, dx, velocity)) return false
        fired = true
        return true
    }
}
