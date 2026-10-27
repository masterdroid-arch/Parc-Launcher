package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import kotlin.math.abs

/** One DOWN owns the gesture. A scrolled page cannot acquire search by reaching its top later.
 * Pull is measured after resistance: the hub needs 220 dp of finger travel for 110 dp of pull.
 * The T42 detector makes the final distance/fling decision on UP, never during a drag.
 */
internal class PullState {
    private val detector = SearchSwipeDetector()
    private var x = 0f
    private var y = 0f
    private var fromHub = false
    private var eligible = false
    private var ticked = false
    var progress = 0f
        private set
    val hubAlpha get() = 1f - (1f - Metrics.searchPullMinHubAlpha) * progress

    fun start(x: Float, y: Float, time: Long, fromHub: Boolean = false,
        atScrollTop: Boolean = true, blocked: Boolean = false, onControl: Boolean = false) {
        this.x = x; this.y = y; this.fromHub = fromHub
        eligible = !blocked && !onControl && x.isFinite() && y.isFinite() &&
            y > Metrics.keepOutTopDp && (!fromHub || atScrollTop)
        ticked = false
        progress = 0f
        detector.start(x, y, time, blocked = !eligible)
    }

    fun cancel() {
        eligible = false
        progress = 0f
        detector.cancel()
    }

    fun panelScrolled(consumedY: Float) {
        if (fromHub && consumedY != 0f) cancel()
    }

    /** Returns a single threshold tick per gesture, including after retreat and re-crossing. */
    fun move(x: Float, y: Float): Boolean {
        val dy = y - this.y
        val dx = x - this.x
        val dominant = dy > Metrics.searchSwipeDominance * abs(dx)
        val pull = dy * if (fromHub) Metrics.searchHubPullResistance else 1f
        progress = if (eligible && x.isFinite() && y.isFinite() && dominant)
            (pull / Metrics.searchOpenSwipeDp).coerceIn(0f, 1f) else 0f
        if (progress < 1f || ticked) return false
        ticked = true
        return true
    }

    fun release(x: Float, y: Float, time: Long): Boolean {
        move(x, y)
        val resistance = if (fromHub) Metrics.searchHubPullResistance else 1f
        val committed = eligible && detector.move(this.x + (x - this.x) * resistance, this.y + (y - this.y) * resistance,
            time, released = true, allowFling = !fromHub)
        progress = if (committed) 1f else 0f
        eligible = false
        detector.cancel()
        return committed
    }
}
