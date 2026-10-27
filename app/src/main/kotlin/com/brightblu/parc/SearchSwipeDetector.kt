package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import kotlin.math.abs

/** Pure dp/ms gesture decision. Ownership is fixed on DOWN; one result per gesture.
 * A fling is evaluated on release using mean velocity from DOWN, so a single noisy
 * sample or a quick tail after a long hold cannot turn a short drag into a fling.
 *
 * T69 reuses the T42/T59 thresholds for the opposite direction: [upward] measures the same
 * distances and velocity against the finger moving up, with the same 1.5x vertical dominance.
 */
internal class SearchSwipeDetector(private val upward: Boolean = false) {
    private var startX = 0f
    private var startY = 0f
    private var startTime = 0L
    private var eligible = false

    fun start(x: Float, y: Float, timeMillis: Long, blocked: Boolean = false, onControl: Boolean = false) {
        startX = x
        startY = y
        startTime = timeMillis
        eligible = !blocked && !onControl && x.isFinite() && y.isFinite() && y > Metrics.keepOutTopDp
    }

    fun cancel() { eligible = false }

    fun move(x: Float, y: Float, timeMillis: Long, released: Boolean = false, allowFling: Boolean = true): Boolean {
        if (!eligible) return false
        val dy = if (upward) startY - y else y - startY
        val dx = x - startX
        val elapsed = timeMillis - startTime
        val distance = dy >= Metrics.searchOpenSwipeDp
        val fling = allowFling && released && dy >= Metrics.searchFlingMinDp && elapsed > 0 &&
            dy * 1000f / elapsed > Metrics.searchFlingVelocityDpPerSecond
        val accepted = x.isFinite() && y.isFinite() && dy > Metrics.searchSwipeDominance * abs(dx) &&
            (distance || fling)
        if (accepted || released) eligible = false
        return accepted
    }
}
