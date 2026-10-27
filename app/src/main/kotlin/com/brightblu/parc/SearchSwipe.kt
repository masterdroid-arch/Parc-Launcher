package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import kotlin.math.abs

/** One gesture; consumed list scrolling disqualifies it even if it subsequently reaches the top. */
internal class SearchSwipe {
    private var startX = 0f
    private var startY = 0f
    private var eligible = false
    private var fired = false
    private var listScrolled = false
    private var overscroll = 0f

    fun start(x: Float, y: Float, fromHub: Boolean = false) {
        startX = x; startY = y
        eligible = !fromHub && y > Metrics.keepOutTopDp
        fired = false; listScrolled = false; overscroll = 0f
    }

    fun move(x: Float, y: Float, consumed: Boolean): Boolean =
        !consumed && !listScrolled && trigger(y - startY, x - startX)

    fun scroll(availableY: Float, consumedY: Float): Boolean {
        if (consumedY != 0f) listScrolled = true
        if (listScrolled) return false
        overscroll = (overscroll + availableY).coerceAtLeast(0f)
        return trigger(overscroll, 0f)
    }

    private fun trigger(dy: Float, dx: Float): Boolean {
        if (!eligible || fired || dy < Metrics.searchSwipeDp || dy <= abs(dx)) return false
        fired = true
        return true
    }
}
