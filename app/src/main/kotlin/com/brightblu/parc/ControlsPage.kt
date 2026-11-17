package com.brightblu.parc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.audio.VolumeRepository
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin

/**
 * T263: Sound + Quick toggles as one page. Volume keeps the page's sliders and output line;
 * Quick toggles keeps its switches and its fixed note. They share one scroll, so nothing is cut off.
 */
@Composable
internal fun ControlsHubPage(
    volume: VolumeRepository?,
    active: Boolean,
    tiles: List<String>,
    skin: Skin,
    demo: Boolean = false,
) {
    val sound = tiles.filter { it in HubCatalog.soundTiles }
    val quick = tiles.filter { it in QuickToggleCatalog.tiles }
    Column(Modifier.fillMaxWidth().hubPageScroll(), verticalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
        if (sound.isNotEmpty()) {
            BasicText("Volume", style = hudLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt())))
            SoundHubPage(volume, active, sound, expanded = false, skin = skin, scroll = false)
        }
        if (quick.isNotEmpty()) {
            BasicText("Quick toggles", style = hudLabelStyle(skin).copy(color = Color(skin.colors.accent.toInt())))
            QuickTogglesHubPage(quick, active, skin, demo = demo, scroll = false)
        }
    }
}
