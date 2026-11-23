package com.brightblu.parc

import androidx.compose.runtime.compositionLocalOf
import com.brightblu.parc.model.Metrics

/** T378: how many whole rows a page shows and how tall each one is at the current panel height. */
internal data class HubPanelDensity(val rows: Int, val tileHeightDp: Float)

/** Panel placement shares the renderer's live arc bottom bound; content never changes it. */
internal object HubPanelLayout {
    fun heightDp(size: HubSize, standardHeightDp: Float): Float =
        if (size == HubSize.Compact) minOf(Metrics.hubCompactHeightDp, standardHeightDp) else standardHeightDp
    /** The panel never reaches past the module card: its bottom stays the gap above the card top. */
    fun bottomDp(arcBottomDp: Float, cardTopDp: Float): Float =
        minOf(arcBottomDp, cardTopDp - Metrics.moduleCardPanelGapDp)

    /**
     * Rows that fit with a gap between them. A row needs its cell plus the gap that follows it, so
     * counting cells alone over-fills a page and leaves the last row cut off by the panel edge.
     */
    fun rowsThatFit(availableDp: Float, cellDp: Float, gapDp: Float = Metrics.hudCellGapDp): Int {
        if (!availableDp.isFinite() || availableDp <= 0f || cellDp <= 0f) return 0
        return ((availableDp + gapDp) / (cellDp + gapDp)).toInt().coerceAtLeast(0)
    }

    /**
     * T378: page density. Compact keeps one row; Tall shows every tile and scrolls if it must;
     * Standard shows as many whole rows as fit at the text size, then grows them to use the space.
     * The viewport therefore ends between rows instead of clipping one.
     */
    fun density(availableDp: Float, tileCount: Int, size: HubSize, fontScale: Float,
        maxDp: Float = Metrics.hubTileMaxHeightDp): HubPanelDensity {
        if (tileCount <= 0) return HubPanelDensity(0, tileHeightDp(availableDp, 0, maxDp))
        val rows = when (size) {
            HubSize.Compact -> 1
            HubSize.Tall -> tileCount
            HubSize.Standard -> rowsThatFit(
                availableDp, Metrics.hudCellHeightDp * fontScale.coerceAtLeast(1f), Metrics.hudCellGapDp)
                .coerceIn(1, tileCount)
        }
        return HubPanelDensity(rows, tileHeightDp(availableDp, rows, maxDp))
    }

    fun visibleTiles(tiles: List<String>, size: HubSize, heightDp: Float, fontScale: Float): List<String> =
        tiles.take(density(heightDp, tiles.size, size, fontScale).rows)

    fun topDp(statusBarInsetDp: Float): Float = statusBarInsetDp.coerceAtLeast(0f) + Metrics.hubTopGapDp
    fun tileHeightDp(availableDp: Float, rows: Int, maxDp: Float = Metrics.hubTileMaxHeightDp): Float {
        if (rows <= 0) return Metrics.hudCellHeightDp
        return ((availableDp - Metrics.hudCellGapDp * (rows - 1)) / rows)
            .coerceIn(Metrics.hudCellHeightDp, maxDp)
    }
}
internal val LocalHubTileHeight = compositionLocalOf { Metrics.hudCellHeightDp }
internal val LocalHubContentHeight = compositionLocalOf { Metrics.hudCellHeightDp }

/** SkinStyle uses zero to inherit the clock typography weight. Compose requires 1..1000. */
internal fun hubClockWeight(style: com.brightblu.parc.model.ClockStyle, skin: com.brightblu.parc.model.Skin): Int =
    com.brightblu.parc.model.LookRules.clockWeight(style,
        skin.style.clockWeight.takeIf { it > 0 } ?: skin.typography.clock.weight).coerceIn(1, 1000)
