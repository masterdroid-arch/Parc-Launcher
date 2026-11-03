package com.brightblu.parc.model

import kotlin.math.max

/**
 * T166: the pure side of the spacing system. Every size and inset is in dp and comes from [Metrics],
 * so the page, card, section and row blocks in ParcBlocks.kt and their tests share one set of numbers.
 */
object ParcSpacing {
    /** The padding around a page's content, in dp. */
    data class PageInsets(val startDp: Float, val topDp: Float, val endDp: Float, val bottomDp: Float)

    /**
     * What a page needs around its content. [statusTopDp] is the larger of the status bar and the display
     * cutout; [navBottomDp] is the navigation bar; the side insets are zero in portrait.
     */
    fun page(statusTopDp: Float, navBottomDp: Float, startInsetDp: Float = 0f, endInsetDp: Float = 0f) = PageInsets(
        startDp = Metrics.screenGutterDp + startInsetDp.coerceAtLeast(0f),
        topDp = pageTopDp(statusTopDp),
        endDp = Metrics.screenGutterDp + endInsetDp.coerceAtLeast(0f),
        bottomDp = pageBottomDp(navBottomDp),
    )

    /** Page content starts below the top keep-out, and a card inset below any status bar or cutout. */
    fun pageTopDp(statusTopDp: Float): Float =
        max(Metrics.keepOutTopDp, statusTopDp.coerceAtLeast(0f) + Metrics.cardInsetDp)

    /** The last row scrolls clear of the gesture strip, and a section gap above a navigation bar. */
    fun pageBottomDp(navBottomDp: Float): Float =
        max(Metrics.keepOutBottomDp, navBottomDp.coerceAtLeast(0f) + Metrics.sectionGapDp)

    /** Width inside a card whose edges are [cardWidthDp] apart. */
    fun cardContentWidthDp(cardWidthDp: Float): Float = (cardWidthDp - 2 * Metrics.cardInsetDp).coerceAtLeast(0f)

    /** Height inside a card whose edges are [cardHeightDp] apart. */
    fun cardContentHeightDp(cardHeightDp: Float): Float = (cardHeightDp - 2 * Metrics.cardInsetDp).coerceAtLeast(0f)

    /** A card of plain content is this much taller than the content. */
    fun cardHeightDp(contentDp: Float): Float = contentDp + 2 * Metrics.cardInsetDp

    /** A Settings row whose content is [contentDp] tall: 12 dp above and below, never under 56 dp. */
    fun settingsRowHeightDp(contentDp: Float): Float =
        max(Metrics.settingsRowMinHeightDp, contentDp + 2 * Metrics.rowGapDp)

    /**
     * Width a Settings row leaves for its title block: 20 dp at both ends, a row gap between the parts that
     * are present, and the widths of the leading sample and the trailing switch or value.
     */
    fun settingsTextWidthDp(rowWidthDp: Float, leadingDp: Float = 0f, trailingDp: Float = 0f): Float {
        val neighbours = (if (leadingDp > 0f) 1 else 0) + (if (trailingDp > 0f) 1 else 0)
        return (rowWidthDp - 2 * Metrics.settingsRowInsetDp - leadingDp - trailingDp - neighbours * Metrics.rowGapDp)
            .coerceAtLeast(0f)
    }

    /**
     * A card that stacks Settings rows adds this above its first and below its last row, so the first and
     * last row's content sits one card inset from the card edge (row gap 12 + this 4 = 16).
     */
    fun rowCardVerticalPaddingDp(): Float = Metrics.cardInsetDp - Metrics.rowGapDp

    /** Space taken by a section header: the gap above it and the gap under it, without its own text height. */
    fun sectionHeaderBlockDp(): Float = Metrics.sectionGapDp + Metrics.sectionHeaderGapDp

    /** A header's own top gap: it sits [Metrics.sectionGapDp] below the block above, minus the gap its container adds. */
    fun sectionHeaderTopDp(blockGapDp: Float = 0f): Float = (Metrics.sectionGapDp - blockGapDp.coerceAtLeast(0f)).coerceAtLeast(0f)

    /** The space before a card that has no header: a row gap, minus the gap its container adds. */
    fun groupLeadDp(blockGapDp: Float = 0f): Float = (Metrics.rowGapDp - blockGapDp.coerceAtLeast(0f)).coerceAtLeast(0f)

    /**
     * The stats hub's padding (T158). The hub is always drawn as a card, so every page, the tab strip and the
     * grip row keep one card inset from all four card edges; actions inside them are at least
     * [Metrics.actionEdgeGapDp] from an edge. A panel with no surface adds nothing.
     */
    fun panelHorizontalInsetDp(framed: Boolean): Float = if (framed) Metrics.cardInsetDp else 0f
    fun panelVerticalInsetDp(framed: Boolean): Float = if (framed) Metrics.cardInsetDp else 0f

    /** The hub card's inset on one side: start, top, end, bottom are all the same. */
    data class HubInsets(val startDp: Float, val topDp: Float, val endDp: Float, val bottomDp: Float)
    fun hubCard(): HubInsets = panelHorizontalInsetDp(true).let { h ->
        panelVerticalInsetDp(true).let { v -> HubInsets(h, v, h, v) }
    }

    /** Space between the rows of a hub page. */
    fun hubRowGapDp(): Float = Metrics.hudCellGapDp

    /**
     * A stats cell is a card of its own: [Metrics.cardInsetDp] on every side. A cell narrower than
     * [Metrics.narrowCardDp] (three Quick toggles in a row, about 100 dp each) is a tile, not a card: it takes an item
     * gap at the sides and a row gap above and below, because a 16 dp inset would break words such as "Opens settings".
     */
    fun cellHorizontalInsetDp(narrow: Boolean): Float = if (narrow) Metrics.itemGapDp else Metrics.cardInsetDp
    fun cellVerticalInsetDp(narrow: Boolean): Float = if (narrow) Metrics.rowGapDp else Metrics.cardInsetDp

    /** The width each page dot gets: one touch target, or an equal share of the row when seven do not fit. */
    fun dotWidthDp(rowWidthDp: Float, count: Int): Float =
        if (count <= 0) Metrics.minTouchDp else minOf(Metrics.menuRowHeightDp, rowWidthDp / count)

    /** A touch target is at least [Metrics.minTouchDp] each way. */
    fun touchDp(contentDp: Float): Float = max(Metrics.minTouchDp, contentDp)

    /**
     * Distance from the card edge to the first visible pixel of a centred glyph of [glyphDp] in an action
     * whose touch box starts [Metrics.actionEdgeGapDp] from that edge and is [boxDp] wide.
     */
    fun actionGlyphInsetDp(boxDp: Float, glyphDp: Float): Float =
        Metrics.actionEdgeGapDp + (touchDp(boxDp) - glyphDp).coerceAtLeast(0f) / 2f
}
