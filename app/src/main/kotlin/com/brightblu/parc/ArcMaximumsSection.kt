package com.brightblu.parc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.brightblu.parc.feature.settings.ui.SettingsChoice
import com.brightblu.parc.model.Skin

/**
 * T312: the per-arc "Maximum apps" rows, one per shown arc, rendered inline in Settings > Home > Arcs.
 * Auto keeps the screen's visible count; a number is the arc's own ceiling. An arc holding more than
 * its maximum still keeps every item and says "over by n".
 */
@Composable
internal fun ArcMaximumsSection(
    snapshot: SettingsSnapshot,
    skin: Skin,
    onSet: (Map<String, Int>) -> Unit,
) {
    val options = remember { listOf("Auto") + (1..ArcMaximums.Max).map { it.toString() } }
    val arcIds = listOf("A1", "A2", "A3", "A4").take(snapshot.counts.arcs)
    arcIds.forEachIndexed { i, arcId ->
        val name = ArcChooser.arcName(i, arcIds.size)
        val current = snapshot.arcMaximums[arcId] ?: ArcMaximums.Auto
        val over = snapshot.arcOver[arcId]
        SettingsChoice(
            title = name,
            value = if (current == ArcMaximums.Auto) "Auto" else current.toString(),
            options = options,
            skin = skin,
            description = over?.let { "over by $it" }.orEmpty(),
        ) { index ->
            onSet(snapshot.arcMaximums + (arcId to index.coerceIn(0, ArcMaximums.Max)))
        }
    }
}
