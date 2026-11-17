package com.brightblu.parc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.brightblu.parc.feature.settings.ui.SettingKind
import com.brightblu.parc.feature.settings.ui.SettingRow
import com.brightblu.parc.feature.settings.ui.SettingsChoice
import com.brightblu.parc.feature.settings.ui.SettingsGroup
import com.brightblu.parc.feature.settings.ui.SettingsOptionChips
import com.brightblu.parc.feature.settings.ui.SettingsVisualChoice
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.HubColour
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin

/** T177: the plain-words line at the top of every page. */
@Composable
internal fun SettingsIntro(skin: Skin, text: String) {
    BasicText(text, style = settingsText(skin, skin.colors.textSecondary),
        modifier = Modifier.padding(horizontal = Metrics.settingsRowInsetDp.dp))
}

/**
 * T175/T177/T220: the rows of one Settings sub-page, generated from [SettingsRegistry]. Nothing else draws a setting
 * row: a page is its registry rows in registry order, grouped by [SettingDef.card]. [custom] draws the few rows whose
 * control is [SettingControl.Custom] (the skin carousel, the background chooser, the build stamp).
 */
@Composable
internal fun RegistryRows(
    page: SettingsPage,
    snapshot: SettingsSnapshot,
    skin: Skin,
    target: String?,
    onValue: (SettingDef, Int) -> Unit,
    onOpen: (SettingDef) -> Unit,
    custom: @Composable (SettingDef) -> Unit,
    footer: (String) -> String = { "" },
    /** Registry rows another part of the page already draws (for example a list editor). */
    hide: Set<String> = emptySet(),
) {
    val rows = SettingsRegistry.shown(page, snapshot).filter { it.id !in hide }
    rows.map { it.card }.distinct().forEach { card ->
        val groupRows = rows.filter { it.card == card }
        // About owns its heading and build-stamp card; do not nest that card inside another card.
        groupRows.filter { it.id == "About" }.forEach { def ->
            SettingsAnchor(def.id, target) { custom(def) }
        }
        val inCard = groupRows.filterNot { it.id == "About" }
        if (inCard.isEmpty()) return@forEach
        SettingsGroup(card, skin, footer = footer(card)) {
            inCard.forEachIndexed { index, def ->
                SettingsAnchor(def.id, target) { RegistryRow(def, snapshot, skin, index < inCard.lastIndex, onValue, onOpen, custom) }
            }
        }
    }
}

@Composable
private fun RegistryRow(def: SettingDef, s: SettingsSnapshot, skin: Skin, divider: Boolean,
    onValue: (SettingDef, Int) -> Unit, onOpen: (SettingDef) -> Unit, custom: @Composable (SettingDef) -> Unit) {
    val options = def.options(s)
    val index = def.read?.invoke(s) ?: 0
    when (def.control) {
        SettingControl.Switch -> SettingRow(def.title, skin, SettingKind.Toggle, def.help, value = options.getOrElse(index) { "" },
            checked = index == 1, divider = divider) { onValue(def, 1 - index) }
        SettingControl.Choice -> if (options.size <= 4) SettingsOptionChips(def.title, skin, options, index, def.help, divider = divider) {
            onValue(def, it)
        } else SettingsChoice(def.title, options.getOrElse(index) { "" }, options, skin, def.help) { onValue(def, it) }
        SettingControl.Slider -> SettingsVisualChoice(def.title, index, options, skin, def.help, divider = divider) { onValue(def, it) }
        SettingControl.Colour -> {
            val arc = def.id == "ArcHubColour"
            SettingsOptionChips(def.title, skin, options, index, def.help, divider = divider, sample = { i, _ ->
                val colour = HubColour.entries[i].argb ?: if (arc) s.skin.colors.moduleSurface else s.skin.colors.surface
                Box(Modifier.fillMaxSize().background(Color((colour or 0xFF000000).toInt()), CircleShape))
            }) { onValue(def, it) }
        }
        SettingControl.Page -> SettingRow(def.title, skin, SettingKind.Page, def.help, value = def.value(s), divider = divider) { onOpen(def) }
        SettingControl.Action -> {
            val clearHistory = def.id == "CommsClearHistory"
            val storage = def.value(s)
            SettingRow(def.title, skin, SettingKind.Action,
                if (clearHistory && storage.isNotBlank()) "$storage · ${def.help}" else def.help,
                value = storage,
                enabled = !clearHistory || s.commsStoredCount > 0,
                destructive = clearHistory && s.commsStoredCount > 0,
                icon = when (def.id) { "AndroidSettings" -> "↗"; "CommsClearHistory" -> "−"; else -> "+" },
                divider = divider) { onOpen(def) }
        }
        SettingControl.Custom -> custom(def)
    }
}
