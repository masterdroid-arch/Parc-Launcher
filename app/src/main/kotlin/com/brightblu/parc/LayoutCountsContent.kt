package com.brightblu.parc

import androidx.compose.runtime.*
import com.brightblu.parc.feature.settings.ui.*
import com.brightblu.parc.model.*
import com.brightblu.parc.ring.*

/**
 * Home layout / Arcs (OPUS1 design pass): the live home on top uses the counts being chosen, so
 * moving a slider shows the new arcs immediately. Stored values and their migration are unchanged.
 */
@Composable
internal fun LayoutCountsPage(skin: Skin, services: LauncherServices, look: LookSettings, counts: LayoutCounts,
    aligned: Boolean, onBack: () -> Unit, onChange: (LayoutCounts) -> Unit) {
    val geometry = services.geometry
    val automatic = remember(geometry, aligned) { RingLayout(geometry.widthDp, geometry.heightDp,
        geometry.insets, geometry.options.copy(alignAcrossArcs = aligned)) }
    val preview = remember(geometry, counts, aligned) { RingLayout(geometry.widthDp, geometry.heightDp,
        geometry.insets, geometry.options.copy(arcsShown = counts.arcs, iconsPerArc = counts.icons, alignAcrossArcs = aligned)) }
    val maximum = automatic.arcs.maxOf { it.slotCapacity }.coerceAtLeast(3)
    PreviewSettingsPage(skin, "Arcs", onBack, preview = {
        HomePreview(services, skin, look, counts = counts, aligned = aligned)
    }) {
        SettingsGroup("Arcs on Home", skin,
            footer = "Visible now: ${preview.arcs.joinToString(" / ") { it.slotCapacity.toString() }} icons per arc. " +
                "Nothing is removed: turn an arc to reach anything beyond what fits.") {
            SettingsVisualChoice("Arcs shown", counts.arcs - 3, listOf("3", "4"), skin,
                "How many rings of apps and folders") { onChange(counts.copy(arcs = it + 3)) }
            val values = listOf(0) + (3..maximum).toList()
            SettingsVisualChoice("Icons per arc", values.indexOf(counts.icons).coerceAtLeast(0),
                values.map { if (it == 0) "Auto" else it.toString() }, skin,
                "Auto fits as many as the screen allows", divider = false) { onChange(counts.copy(icons = values[it])) }
        }
    }
}
