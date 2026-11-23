package com.brightblu.parc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brightblu.parc.feature.settings.ui.SettingsChoice
import com.brightblu.parc.feature.settings.ui.SettingsInfo
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin

/**
 * T312: the per-arc "Arc capacities" rows, one per shown arc, rendered inline in Settings > Home > Arcs.
 * Auto keeps the screen's visible count; a number is the arc's own ceiling. An arc holding more than
 * its maximum still keeps every item and says "over by n".
 *
 * T379: the row's one-line helper is drawn once above the arcs; the longer explanation sits behind Info.
 */
@Composable
internal fun ArcMaximumsSection(
    snapshot: SettingsSnapshot,
    skin: Skin,
    help: String,
    info: String,
    onSet: (Map<String, Int>) -> Unit,
) {
    val options = remember { listOf("Auto") + (1..ArcMaximums.Max).map { it.toString() } }
    val arcIds = listOf("A1", "A2", "A3", "A4").take(snapshot.counts.arcs)
    Row(Modifier.fillMaxWidth().padding(horizontal = Metrics.settingsRowInsetDp.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
        BasicText(help, Modifier.weight(1f), style = settingsText(skin, skin.colors.textSecondary, true))
        if (info.isNotBlank()) SettingsInfo("Arc capacities", info, skin)
    }
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
