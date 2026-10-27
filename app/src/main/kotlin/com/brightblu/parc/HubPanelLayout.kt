package com.brightblu.parc

import androidx.compose.runtime.compositionLocalOf
import com.brightblu.parc.model.Metrics

/** Panel placement shares the renderer's live arc bottom bound; content never changes it. */
internal object HubPanelLayout {
    fun heightDp(size: HubSize, standardHeightDp: Float): Float =
        if (size == HubSize.Compact) minOf(Metrics.hubCompactHeightDp, standardHeightDp) else standardHeightDp
    fun visibleTiles(tiles: List<String>, size: HubSize, heightDp: Float, fontScale: Float): List<String> =
        tiles.take(when {
            size == HubSize.Compact -> 1
            size == HubSize.Tall -> Int.MAX_VALUE
            else -> ((heightDp / (Metrics.hudCellHeightDp * fontScale.coerceAtLeast(1f))).toInt()).coerceAtLeast(1)
        })
    fun topDp(statusBarInsetDp: Float): Float = statusBarInsetDp.coerceAtLeast(0f) + Metrics.hubTopGapDp
    fun tileHeightDp(availableDp: Float, rows: Int): Float {
        if (rows <= 0) return Metrics.hudCellHeightDp
        return ((availableDp - Metrics.hudCellGapDp * (rows - 1)) / rows)
            .coerceIn(Metrics.hudCellHeightDp, Metrics.hubTileMaxHeightDp)
    }
}
internal val LocalHubTileHeight = compositionLocalOf { Metrics.hudCellHeightDp }
internal val LocalHubContentHeight = compositionLocalOf { Metrics.hudCellHeightDp }

/** SkinStyle uses zero to inherit the clock typography weight. Compose requires 1..1000. */
internal fun hubClockWeight(style: com.brightblu.parc.model.ClockStyle, skin: com.brightblu.parc.model.Skin): Int =
    com.brightblu.parc.model.LookRules.clockWeight(style,
        skin.style.clockWeight.takeIf { it > 0 } ?: skin.typography.clock.weight).coerceIn(1, 1000)

/** Reverse the temporary Tall reflow for menus; storage always uses the original position. */
internal fun hubSourcePosition(layout: com.brightblu.parc.model.ParcLayout,
    position: com.brightblu.parc.model.SlotPosition): com.brightblu.parc.model.SlotPosition {
    if (position.arcId != "A3") return position
    val start = (layout.arcs.firstOrNull { it.arc.id == "A3" }?.slots?.keys?.maxOrNull() ?: -1) + 1
    if (position.index < start) return position
    val source = layout.arcs.firstOrNull { it.arc.id == "A4" }?.slots?.toSortedMap()?.keys?.elementAtOrNull(position.index - start)
    return if (source == null) position else com.brightblu.parc.model.SlotPosition("A4", source)
}
