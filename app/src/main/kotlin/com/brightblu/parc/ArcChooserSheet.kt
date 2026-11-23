package com.brightblu.parc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brightblu.parc.feature.settings.ui.SettingsTheme
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.readableAccent

/**
 * T312: the one shared "Add to arc" chooser. A quiet bottom sheet listing the arcs from the hub
 * outward, each named with how full it is. A tap on a non-full arc adds the app there; a tap on a
 * full arc says so in plain words and offers a shortcut to Settings > Home > Arcs.
 *
 * T376: it now lives on the shared [SettingsSheet] (handle, fixed Done action and safe space above the
 * gesture bar), like Wallpaper and the page sheet. Each row keeps its count visible — a full arc reads
 * "Full" in a quiet colour, a free arc reads "X of Y" — and the sheet subtitle names how many arcs are
 * full so the full state is never hidden.
 */
@Composable
internal fun ArcChooserSheet(
    appLabel: String,
    choices: List<ArcChoice>,
    skin: Skin,
    onPick: (ArcChoice) -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    var fullNotice by remember(choices) { mutableStateOf<String?>(null) }
    val allFull = choices.isNotEmpty() && choices.all { it.full }
    SettingsTheme(skin) {
        SettingsSheet("Add $appLabel to an arc", skin, onClose, subtitle = ArcChooser.fullSubtitle(choices)) {
            if (allFull) {
                ArcChooserMessage(ArcChooser.AllFullMessage, skin)
                ArcChooserSettingsRow(skin, onOpenSettings)
            } else {
                Column(Modifier.fillMaxWidth()) {
                    choices.forEach { choice ->
                        ArcChooserRow(choice, skin) {
                            if (choice.full) fullNotice = ArcChooser.fullMessage(choice.name)
                            else onPick(choice)
                        }
                    }
                }
                fullNotice?.let { notice ->
                    ArcChooserMessage(notice, skin)
                    ArcChooserSettingsRow(skin, onOpenSettings)
                }
            }
        }
    }
}

/** One arc row: name on the left, its full or occupied/capacity count on the right. */
@Composable
private fun ArcChooserRow(choice: ArcChoice, skin: Skin, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = Metrics.touchTargetDp.dp)
        .clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = Metrics.settingsRowInsetDp.dp, vertical = Metrics.rowGapDp.dp)) {
        BasicText(choice.name, Modifier.align(Alignment.CenterStart), maxLines = 1,
            overflow = TextOverflow.Ellipsis, style = settingsText(skin))
        BasicText(if (choice.full) "Full" else "${choice.occupied} of ${choice.capacity}",
            Modifier.align(Alignment.CenterEnd), maxLines = 1,
            style = settingsText(skin, if (choice.full) skin.colors.textSecondary else skin.colors.readableAccent))
    }
}

@Composable
private fun ArcChooserMessage(text: String, skin: Skin) {
    Box(
        Modifier.fillMaxWidth()
            .heightIn(min = Metrics.menuRowHeightDp.dp)
            .padding(horizontal = Metrics.settingsRowInsetDp.dp, vertical = Metrics.itemGapDp.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            text,
            style = settingsText(skin, skin.colors.textSecondary, true),
        )
    }
}

@Composable
private fun ArcChooserSettingsRow(skin: Skin, onOpenSettings: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .heightIn(min = Metrics.menuRowHeightDp.dp)
            .clickable(role = Role.Button, onClick = onOpenSettings)
            .padding(horizontal = Metrics.settingsRowInsetDp.dp, vertical = Metrics.itemGapDp.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            "Settings",
            style = settingsText(skin, skin.colors.readableAccent),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
