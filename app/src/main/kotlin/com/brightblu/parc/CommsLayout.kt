package com.brightblu.parc

import com.brightblu.parc.model.Metrics

/**
 * T233: the pure layout model for the Messages page. The page's old shape spent a large hero and a
 * full-width segment row before the list; this model keeps the header to one line and makes a
 * card's height depend on a capped number of message lines. The composable uses the same tokens,
 * so a test can reason about the rows a user actually sees.
 */
internal object CommsLayout {

    /** One compact header line, shared by the title and the inline Live / History tabs. */
    val headerDp: Float get() = Metrics.commsHeaderRowDp

    /** One quiet footer line, kept as the final list item so it never steals list height. */
    val footerDp: Float get() = Metrics.commsFooterDp

    /** Gap between the header, the cards and the footer. */
    val gapDp: Float get() = Metrics.commsSectionGapDp

    /**
     * The card viewport once the one-line header has scrolled away. The header is the first item of
     * the page list, so at Compact it never permanently steals the little height there is. Never
     * negative.
     */
    fun cardViewportDp(availableDp: Float, gapDp: Float = this.gapDp): Float =
        (availableDp - gapDp).coerceAtLeast(0f)

    /** A card is a header line, up to [Metrics.commsMaxLines] message lines, an optional count and actions. */
    fun cardHeightDp(messageLines: Int, hasCount: Boolean = false, fontScale: Float = 1f): Float {
        val scale = fontScale.coerceAtLeast(1f)
        val lines = messageLines.coerceIn(0, Metrics.commsMaxLines)
        val count = if (hasCount) Metrics.commsCountLineDp * scale + Metrics.commsCardInnerGapDp * scale else 0f
        return Metrics.cardInsetDp * 2f +
            Metrics.commsCardHeaderDp * scale + Metrics.commsCardInnerGapDp * scale +
            lines * Metrics.commsMessageLineDp * scale + count + Metrics.commsActionDp
    }

    /** The scrollable content: every card, the gaps between them and the T167 end padding. */
    fun contentHeightDp(rowHeightsDp: List<Float>, gapDp: Float = Metrics.itemGapDp,
        endPaddingDp: Float = HubPageScroll.endPaddingDp): Float {
        if (rowHeightsDp.isEmpty()) return 0f
        return rowHeightsDp.sum() + gapDp * (rowHeightsDp.size - 1) + endPaddingDp
    }

    /** How many whole cards fit from the top of the viewport. */
    fun rowsFullyVisible(viewportDp: Float, rowHeightsDp: List<Float>, gapDp: Float = Metrics.itemGapDp): Int {
        var used = 0f
        var visible = 0
        for (height in rowHeightsDp) {
            val next = if (visible == 0) height else used + gapDp + height
            if (next > viewportDp + 0.001f) break
            used = next
            visible++
        }
        return visible
    }

    /**
     * Every card at least partly inside the viewport at [scrollOffsetDp], including a card cut by
     * the top or bottom edge. Null when the list is empty or the viewport has no height.
     */
    fun visibleRange(viewportDp: Float, rowHeightsDp: List<Float>, gapDp: Float = Metrics.itemGapDp,
        scrollOffsetDp: Float = 0f): IntRange? {
        if (rowHeightsDp.isEmpty() || viewportDp <= 0f) return null
        val top = scrollOffsetDp.coerceAtLeast(0f)
        val bottom = top + viewportDp
        var start = -1
        var end = -1
        var y = 0f
        for ((index, height) in rowHeightsDp.withIndex()) {
            val itemBottom = y + height
            if (itemBottom > top + 0.001f && y < bottom - 0.001f) {
                if (start < 0) start = index
                end = index
            }
            y = itemBottom + gapDp
        }
        return if (start < 0) null else start..end
    }

    /** Gap between the last card and the top of the fade when the list is scrolled to the end. */
    fun gapAboveFadeAtEndDp(viewportDp: Float, rowHeightsDp: List<Float>, gapDp: Float = Metrics.itemGapDp): Float =
        HubPageScroll.gapAboveFadeAtEndDp(contentHeightDp(rowHeightsDp, gapDp), viewportDp)

    /** True when the last card can stand clear of the bottom fade with T167's 16 dp gap. */
    fun lastCardReachable(viewportDp: Float, rowHeightsDp: List<Float>, gapDp: Float = Metrics.itemGapDp): Boolean =
        gapAboveFadeAtEndDp(viewportDp, rowHeightsDp, gapDp) >= Metrics.hubScrollEndGapDp - 0.001f
}
