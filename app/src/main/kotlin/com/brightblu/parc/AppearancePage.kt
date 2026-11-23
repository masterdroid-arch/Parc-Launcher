package com.brightblu.parc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brightblu.parc.feature.settings.ui.SettingKind
import com.brightblu.parc.feature.settings.ui.SettingRow
import com.brightblu.parc.feature.settings.ui.SettingsCard
import com.brightblu.parc.feature.settings.ui.SettingsGroup as SettingsCardGroup
import com.brightblu.parc.feature.settings.ui.SettingsOptionChips
import com.brightblu.parc.feature.settings.ui.SettingsSegmented
import com.brightblu.parc.feature.settings.ui.SettingsVisualChoice
import com.brightblu.parc.model.HubColour
import com.brightblu.parc.model.LookSettings
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin

/**
 * T374 (mockup 02): which hub card the in-place controls edit. Only the two cards exist; with the stats hub off there is one.
 * Each card keeps its own colour and opacity row ids, so every saved value stays where it was.
 */
internal enum class HubCardTarget(val label: String, val colourId: String, val opacityId: String) {
    Arc("Arc hub", "ArcHubColour", "ArcHubOpacity"),
    Stats("Stats hub", "StatsHubColour", "StatsHubOpacity");

    companion object {
        /** The cards the segments offer: the arc hub always, the stats hub while it is shown. */
        fun available(statsVisible: Boolean): List<HubCardTarget> = if (statsVisible) entries else listOf(Arc)
    }
}

/**
 * The Appearance group page: a live Home sample, the three everyday rows (the same rows as the root tiles), the hub
 * cards' colour and opacity in place, then the two pages that hold the rest.
 */
@Composable
internal fun AppearanceGroupPage(
    skin: Skin, glassSkin: Skin, activeSkin: Skin, snapshot: SettingsSnapshot, services: LauncherServices, look: LookSettings,
    onBack: () -> Unit, onOpenPage: (SettingsPage) -> Unit, onShortcut: (SettingsShortcut) -> Unit,
    onValue: (SettingDef, Int) -> Unit,
) {
    SettingsPageFrame(skin, onBack = onBack) {
        SettingsContent(skin, SettingsGroup.Appearance.title) {
            HomeSample(services, activeSkin, glassSkin, look, HomeSampleFocus.Arcs, snapshot.counts, snapshot.aligned, snapshot.leftHand)
            Spacer(Modifier.height(Metrics.rowGapDp.dp))
            SettingsShortcutRows(SettingsGroup.Appearance, snapshot, skin, onShortcut)
            HubCardControls(snapshot, skin, onValue)
            Spacer(Modifier.height(Metrics.rowGapDp.dp))
            SettingsCard(skin) {
                val pages = listOf(SettingsPage.AppearanceIcons, SettingsPage.Hubs)
                pages.forEachIndexed { index, page ->
                    SettingRow(page.title, skin, SettingKind.Page, page.summary, divider = index < pages.lastIndex) { onOpenPage(page) }
                }
            }
            Spacer(Modifier.height(Metrics.rowGapDp.dp))
        }
    }
}

/** The card chooser, the chosen card's colour and its named opacity. The writes are the registry's own. */
@Composable
internal fun HubCardControls(snapshot: SettingsSnapshot, skin: Skin, onValue: (SettingDef, Int) -> Unit) {
    val cards = HubCardTarget.available(snapshot.statsVisible)
    var picked by rememberSaveable { mutableIntStateOf(0) }
    val card = cards[picked.coerceIn(cards.indices)]
    SettingsCardGroup("Card appearance", skin) {
        if (cards.size > 1) SettingsSegmented("", skin, cards.map { it.label }, cards.indexOf(card)) { picked = it }
        val opacity = SettingsRegistry.byId.getValue(card.opacityId)
        SettingsVisualChoice(opacity.fullTitle, opacity.read?.invoke(snapshot) ?: 0, opacity.options(snapshot), skin, opacity.help,
            divider = true) { onValue(opacity, it) }
        val colour = SettingsRegistry.byId.getValue(card.colourId)
        val colourIndex = colour.read?.invoke(snapshot) ?: 0
        SettingsOptionChips("${card.label} colour", skin, colour.options(snapshot), colourIndex, colour.help, divider = false, sample = { i, _ ->
            val argb = HubColour.entries[i].argb ?: if (card == HubCardTarget.Arc) snapshot.skin.colors.moduleSurface else snapshot.skin.colors.surface
            Box(Modifier.fillMaxSize().background(Color((argb or 0xFF000000).toInt()), CircleShape))
        }) { onValue(colour, it) }
    }
}
