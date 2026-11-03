package com.brightblu.parc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.brightblu.parc.feature.settings.ui.*
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.parcRowPadding
import com.brightblu.parc.model.Skin

internal val LocalHubSettings = staticCompositionLocalOf<(SettingsPage) -> Unit> { {} }

/** Existing actions only: this sheet does not change what tapping a page does. */
internal data class HubPageOptionSpec(val shows: String, val tap: String, val fullSettings: SettingsPage)
internal val hubPageOptions = linkedMapOf(
    "notes" to HubPageOptionSpec("Your pinned note and recent notes, with checklist lines.", "Tap + to Add a note. Tap a note to edit; tap a checklist box to tick it.", SettingsPage.HubPages),
    "timers" to HubPageOptionSpec("Timers, stopwatch and your next alarm.", "Tap a preset to start a timer, or use the labelled timer and stopwatch controls.", SettingsPage.HubPages),
    "agenda" to HubPageOptionSpec("Upcoming events from your chosen calendars.", "Tap an event to open it in your calendar app.", SettingsPage.Agenda),
    "apps" to HubPageOptionSpec("Screen time, new apps and the biggest apps on your phone.", "Tap a listed app to open it. Uninstall is a separate labelled action.", SettingsPage.HubPages),
    "comms" to HubPageOptionSpec("Messages from your selected Comms apps.", "Tap a conversation to open it. Reply and Mark as read use their own controls when available.", SettingsPage.Comms),
    "clock" to HubPageOptionSpec("Time, with your chosen clock style, date and alarm line.", "Tap the clock to open your clock app. Long press for clock options.", SettingsPage.Look),
    "system" to HubPageOptionSpec("Battery, temperature, memory, storage and connection readings.", "Readings are readouts. History graphs and warning levels are in Full settings.", SettingsPage.StatsAlerts),
    "power" to HubPageOptionSpec("Charging, battery health, temperature and screen-on time.", "Readings are readouts. History graphs and warning levels are in Full settings.", SettingsPage.StatsAlerts),
    "network" to HubPageOptionSpec("Traffic, today's totals, top apps and connection information.", "Readings are readouts. Grant usage access appears when it is needed.", SettingsPage.HubPages),
    "sound" to HubPageOptionSpec("Media, ring, alarm and call volume, and the audio output.", "Drag a volume control to change that volume.", SettingsPage.HubPages),
    "media" to HubPageOptionSpec("The current media session and playback controls.", "Tap a player to select it; use its labelled playback controls.", SettingsPage.HubPages),
    "focus" to HubPageOptionSpec("Your current Focus mode, time remaining and available modes.", "Tap Start or End to control a session. Strict modes keep their existing checks.", SettingsPage.Focus),
    QuickToggleCatalog.PageId to HubPageOptionSpec("Torch, Do Not Disturb and shortcuts to Wi-Fi, Bluetooth, mobile data, airplane mode, auto-rotate, battery saver and screen timeout.",
        "Tap Torch or Do Not Disturb to switch it. The other tiles open the system panel or settings screen, because Android does not let apps switch them.", SettingsPage.HubPages),
).apply {
    if (WeatherVariant.Available) put(WeatherVariant.PageId, HubPageOptionSpec("Now, today's high and low, the next hours, rain, wind, sunrise and sunset; more days at Tall size.",
        "Tap City or use these options to choose the city. The forecast comes from Open-Meteo.", SettingsPage.HubPages))
}

/** Move among visible pages; hidden pages must not make a Move appear to do nothing. */
internal fun HubConfig.moveVisiblePage(page: String, delta: Int): HubConfig {
    val visible = pages
    val from = visible.indexOf(page)
    if (from < 0) return this
    val to = (from + delta).coerceIn(0, visible.lastIndex)
    if (from == to) return this
    val order = orderedPages.toMutableList()
    val a = order.indexOf(page)
    val b = order.indexOf(visible[to])
    order[a] = visible[to]; order[b] = page
    return copy(pageOrder = order)
}

@Composable
internal fun HubPageOptionsSheet(page: String, skin: Skin, config: HubConfig,
    update: ((HubConfig) -> HubConfig) -> Unit, onDismiss: () -> Unit,
    onFullSettings: (SettingsPage) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(Metrics.itemGapDp.dp)
            .background(Color(skin.colors.bg.toInt()), RoundedCornerShape(Metrics.menuCornerDp.dp))
            .padding(start = Metrics.cardInsetDp.dp, end = Metrics.cardInsetDp.dp, bottom = Metrics.cardInsetDp.dp)) {
            SettingRow("Back", skin, SettingKind.Action, icon = "‹", onClick = onDismiss)
            HubPageOptionsContent(page, skin, config, update, onFullSettings, Modifier.weight(1f))
        }
    }
}

@Composable
internal fun HubPageOptionsContent(page: String, skin: Skin, config: HubConfig,
    update: ((HubConfig) -> HubConfig) -> Unit, onFullSettings: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = hubPageOptions.getValue(page)
    val name = hubPageName(page)
    val undo = rememberEditorUndo()
    val shown = page !in config.hiddenPages
    val visible = config.pages
    val index = visible.indexOf(page)
    val tiles = config.tiles(page)
    val visibleTiles = tiles.filterNot { it in config.hiddenTiles[page].orEmpty() }
    val preview = if (tiles.isEmpty()) spec.shows else visibleTiles.joinToString(" · ").ifEmpty { "No tiles shown. Add one using a switch below." }
    val instruction = (if (tiles.isEmpty()) "Use Move to reorder or Remove to hide this page. " else "Tap switches to choose what appears. Use Move or Remove for this page. ") + "Changes save automatically."
    EditorScaffold(EditorSpec.HubPages, skin, modifier, title = "$name: page options", instruction = instruction,
        educationId = "HubPageOptions.$page", undo = if (undo.action != null) ({ undo.run() }) else null,
        preview = {
            SettingsCard(skin) {
                BasicText("Shows: $preview", Modifier.parcRowPadding(), style = settingsText(skin))
                BasicText("On Home: " + visible.joinToString(" · "), Modifier.parcRowPadding(), style = settingsText(skin, skin.colors.accent, true))
            }
        }) {
        if (page == "clock") ClockOptionChoices(skin, config, update)
        if (page == WeatherVariant.PageId) WeatherVariant.Options(skin)
        HubSizeChoices(skin, config, update)
        SettingsCard(skin) { SettingRow("Edit pages", skin, SettingKind.Page, divider = false) { onFullSettings(SettingsPage.HubPages) } }
        SettingsCard(skin) {
            SettingRow("What a tap does", skin, SettingKind.Page, spec.tap,
                descriptionMaxLines = Int.MAX_VALUE, value = "Current behaviour", divider = false) { onFullSettings(spec.fullSettings) }
        }
        if (!shown) BasicText("Page hidden from Home. Add it below or use Undo.", style = settingsText(skin))
        SettingsCard(skin) {
            SettingRow(if (shown) "Remove page" else "Add page", skin, SettingKind.Action,
                description = if (shown) "Hide $name from Home; its content is kept. Undo brings it back." else "Show $name on Home again.",
                descriptionMaxLines = Int.MAX_VALUE, icon = if (shown) "−" else "+") {
                update { it.showPage(page, !shown) }
                if (shown) undo.offer { update { it.showPage(page, true) } }
            }
            SettingRow("Move left", skin, SettingKind.Action, "Show $name before the previous visible page", enabled = shown && index > 0, icon = "←") {
                update { it.moveVisiblePage(page, -1) }
            }
            SettingRow("Move right", skin, SettingKind.Action, "Show $name after the next visible page", enabled = shown && index < visible.lastIndex, icon = "→", divider = false) {
                update { it.moveVisiblePage(page, 1) }
            }
        }
        tiles.forEachIndexed { tileIndex, tile ->
            val tileShown = tile !in config.hiddenTiles[page].orEmpty()
            SettingsCard(skin) {
                SettingRow(hubTileLabel(page, tile), skin, SettingKind.Toggle,
                    description = "Show this on $name", checked = tileShown) {
                    update { it.showTile(page, tile, !tileShown) }
                    if (tileShown) undo.offer { update { it.showTile(page, tile, true) } }
                }
                SettingRow("Move $tile up", skin, SettingKind.Action, enabled = tileIndex > 0, icon = "↑") {
                    update { it.move(page, tile, -1) }
                }
                SettingRow("Move $tile down", skin, SettingKind.Action, enabled = tileIndex < tiles.lastIndex, icon = "↓", divider = false) {
                    update { it.move(page, tile, 1) }
                }
            }
        }
        SettingsCard(skin) {
            SettingRow("Full settings", skin, SettingKind.Page, divider = false,
                description = when (spec.fullSettings) {
                    SettingsPage.HubPages -> "All hub pages, visibility and order"
                    SettingsPage.Look -> "Icons and clock: style, date and alarm"
                    SettingsPage.Agenda -> "Agenda: calendars and days ahead"
                    SettingsPage.Comms -> "Comms apps, history and quick replies"
                    SettingsPage.Focus -> "Focus modes and schedules"
                    else -> "Stats alerts and history"
                }) { onFullSettings(spec.fullSettings) }
        }
    }
}

@Composable
internal fun HubSizeChoices(skin: Skin, config: HubConfig, update: ((HubConfig) -> HubConfig) -> Unit) {
    SettingsCard(skin) {
        BasicText("Hub size", Modifier.parcRowPadding(), style = settingsText(skin))
        HubSize.entries.forEachIndexed { index, size ->
            SettingRow(size.name, skin, SettingKind.Toggle, checked = config.size == size, divider = index < HubSize.entries.lastIndex) {
                update { it.copy(size = size) }
            }
        }
    }
}

@Composable
private fun ClockOptionChoices(skin: Skin, config: HubConfig, update: ((HubConfig) -> HubConfig) -> Unit) {
    var zonesOpen by remember { mutableStateOf(false) }
    SettingsCard(skin) {
        SettingRow("24-hour clock", skin, SettingKind.Toggle, checked = config.clock.hour24) {
            update { it.copy(clock = it.clock.copy(hour24 = !it.clock.hour24)) }
        }
        SettingRow("Show seconds", skin, SettingKind.Toggle, checked = config.clock.seconds) {
            update { it.copy(clock = it.clock.copy(seconds = !it.clock.seconds)) }
        }
        SettingRow("Second time zone", skin, SettingKind.Page, value = config.clock.zone.ifEmpty { "Off" }, divider = false) { zonesOpen = true }
    }
    if (zonesOpen) {
        val zones = remember { java.time.ZoneId.getAvailableZoneIds().sorted() }
        var query by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(onDismissRequest = { zonesOpen = false },
            containerColor = Color(skin.colors.surface.toInt()),
            title = { BasicText("Second time zone", style = settingsText(skin)) },
            text = { Column {
                androidx.compose.material3.OutlinedTextField(query, { query = it }, textStyle = settingsText(skin), label = { BasicText("Find a city or zone", style = settingsText(skin)) }, singleLine = true)
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f, fill = false)) {
                    item { SettingRow("Off", skin, SettingKind.Action) {
                        update { it.copy(clock = it.clock.copy(zone = "")) }; zonesOpen = false
                    } }
                    val filtered = zones.filter { it.replace('_', ' ').contains(query, ignoreCase = true) }
                    items(filtered.size) { index ->
                        val zone = filtered[index]
                        SettingRow(zone.replace('_', ' '), skin, SettingKind.Action) {
                            update { it.copy(clock = it.clock.copy(zone = zone)) }; zonesOpen = false
                        }
                    }
                }
            } },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { zonesOpen = false }) { BasicText("Done", style = settingsText(skin)) } })
    }
}
