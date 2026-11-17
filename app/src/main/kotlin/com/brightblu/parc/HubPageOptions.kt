package com.brightblu.parc

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.brightblu.parc.feature.settings.ui.*
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcBlocks
import com.brightblu.parc.model.ParcDialogArea
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.Springs
import com.brightblu.parc.model.parcBottomInset
import com.brightblu.parc.model.parcRowPadding
import com.brightblu.parc.model.readableAccent

internal val LocalHubSettings = staticCompositionLocalOf<(SettingsPage) -> Unit> { {} }

/** Existing actions only: this sheet does not change what tapping a page does. */
internal data class HubPageOptionSpec(val shows: String, val tap: String, val fullSettings: SettingsPage)
internal val hubPageOptions = linkedMapOf(
    "notes" to HubPageOptionSpec("Your pinned note and recent notes, with checklist lines.", "Tap + to Add a note. Tap a note to edit; tap a checklist box to tick it.", SettingsPage.HubPages),
    "timers" to HubPageOptionSpec("Timers, stopwatch and your next alarm.", "Tap a preset to start a timer, or use the labelled timer and stopwatch controls.", SettingsPage.HubPages),
    "agenda" to HubPageOptionSpec("Upcoming events from your chosen calendars.", "Tap an event to open it in your calendar app.", SettingsPage.Agenda),
    "apps" to HubPageOptionSpec("Screen time, new apps and the biggest apps on your phone.", "Tap a listed app to open it. Uninstall is a separate labelled action.", SettingsPage.HubPages),
    "comms" to HubPageOptionSpec("Messages from your selected Comms apps.", "Tap a conversation to open it. Reply and Mark as read use their own controls when available.", SettingsPage.Messages),
    "media" to HubPageOptionSpec("The current media session and playback controls.", "Tap a player to select it; use its labelled playback controls.", SettingsPage.HubPages),
    "focus" to HubPageOptionSpec("Your current Focus mode, time remaining and available modes.", "Tap Start or End to control a session. Strict modes keep their existing checks.", SettingsPage.Focus),
).apply {
    // T263: the three merged pages. Old page options resolve here through HubCatalog.canonicalPage.
    put(DevicePage.Id, HubPageOptionSpec("Power, network, storage and thermal readings from your phone.",
        "Readings are readouts. History graphs and warning levels are in Full settings.", SettingsPage.StatsAlerts))
    put(ControlsPage.Id, HubPageOptionSpec("Volume, audio output and the quick toggles in one place.",
        "Drag a volume control to change it. Tap Torch or Do Not Disturb to switch it; the other toggles open the system panel or settings screen.", SettingsPage.HubPages))
    put(CommonplacePage.Id, HubPageOptionSpec("Kept passages on paper cards: quotes, book highlights and shared text, in one searchable list.",
        "Type to search. Tap a book highlight to reopen it in the reader. Hold a quote for Edit, Copy, Hide or Delete. Use + to add a quote.", SettingsPage.HubPages))
    // Pages added after 1.1 are appended here, so the order of the older entries stays as it was.
    put(TodayPage.Id, HubPageOptionSpec("The time, date and alarm, a quiet summary from your visible pages, and a passage on a heavy day.",
        "Tap the clock to open alarms. Long press the clock for clock options. Tap a line to open its page. Lead with a quote is here.", SettingsPage.HubPages))
    put(StepsPage.Id, HubPageOptionSpec("Steps today, the last seven days and period totals, counted by the phone.",
        "Tap the period to show the next one. Stride length and the daily goal are in these options.", SettingsPage.HubPages))
    put(RhythmPage.Id, HubPageOptionSpec("Your day as a 24-hour ring of screen time and pickups.",
        "Use the arrows to look at earlier days. The hours that count for the phone-free stretch are in these options.", SettingsPage.HubPages))
    put(SkyPage.Id, HubPageOptionSpec("The sun's path, golden and blue hours, and the moon, worked out on the phone.",
        "Nothing to tap. The place comes from the weather home place, or from a latitude and longitude typed in these options.", SettingsPage.HubPages))
    put(YearPage.Id, HubPageOptionSpec("The year as one dot per day, and countdowns to dates you add.",
        "Tap \"Shade by\" to shade past days by steps, screen time, data or pickups. Tap a dot for that day. The dot colour and the dates are in these options.", SettingsPage.HubPages))
    put(RecordsPage.Id, HubPageOptionSpec("Your personal bests from the last days, with streaks.",
        "Nothing to tap. Records need a week of history. The step goal for the goal streak is set in the Steps page options.", SettingsPage.HubPages))
    if (WeatherVariant.Available) put(WeatherVariant.PageId, HubPageOptionSpec("Now, today's high and low, the next hours and rain.",
        "Tap City or use these options to choose the city. The forecast comes from Open-Meteo.", SettingsPage.HubPages))
    put(LibraryPage.Id, HubPageOptionSpec("The books you chose, with covers, how far you have read and your shelves.",
        "Tap a shelf to show it and the sort label to change the order. Hold a book to add it to a shelf, mark it finished or unread, see Details or remove it. Find books and Shelves are in these options.", SettingsPage.HubPages))
    PersonalExtras.pages.forEach { put(it.id, it.options) }
    // T200: only ever shown once a game is unlocked (a locked arcade has no tab, no settings row and no options sheet).
    put(com.brightblu.parc.arcade.ArcadePage.Id, HubPageOptionSpec("The games you have unlocked, each with your best score.",
        "Tap a game to play it. Back returns to the list.", SettingsPage.HubPages))
}

/** Move among visible pages; hidden pages must not make a Move appear to do nothing. */
internal fun HubConfig.moveVisiblePage(page: String, delta: Int): HubConfig {
    val id = HubCatalog.canonicalPage(page)
    val visible = pages
    val from = visible.indexOf(id)
    if (from < 0) return this
    val to = (from + delta).coerceIn(0, visible.lastIndex)
    if (from == to) return this
    val order = orderedPages.toMutableList()
    val a = order.indexOf(id)
    val b = order.indexOf(visible[to])
    order[a] = visible[to]; order[b] = id
    return copy(pageOrder = order)
}

@Composable
internal fun HubPageOptionsSheet(page: String, skin: Skin, config: HubConfig,
    update: ((HubConfig) -> HubConfig) -> Unit, onDismiss: () -> Unit,
    onFullSettings: (SettingsPage) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ParcDialogArea {
            Column(Modifier.fillMaxSize().padding(Metrics.itemGapDp.dp)
                .background(Color(skin.colors.bg.toInt()), RoundedCornerShape(Metrics.menuCornerDp.dp))
                .padding(start = Metrics.cardInsetDp.dp, end = Metrics.cardInsetDp.dp, bottom = Metrics.cardInsetDp.dp)) {
                SettingRow("Back", skin, SettingKind.Action, icon = "‹", onClick = onDismiss)
                HubPageOptionsContent(page, skin, config, update, onFullSettings, Modifier.weight(1f))
            }
        }
    }
}

/**
 * T229: one page-options layout for every hub page. A header card says what the page is; "This page"
 * carries the Show switch, the compact position pair and the hub size segmented control; the page's
 * own settings follow under a small label; tiles (when there are any) come next; "More" keeps the three
 * quiet one-line destinations at the bottom. No repeated instruction, no "Shows" list of other pages.
 */
@Composable
internal fun HubPageOptionsContent(rawPage: String, skin: Skin, config: HubConfig,
    update: ((HubConfig) -> HubConfig) -> Unit, onFullSettings: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
    /** The "Edit pages" shortcut is for the sheet on Home; inside Settings the page list is one step back. */
    showEditPages: Boolean = true,
) {
    val page = HubCatalog.canonicalPage(rawPage)
    val spec = hubPageOptions.getValue(page)
    val name = hubPageName(page)
    val undo = rememberEditorUndo()
    val shown = page !in config.hiddenPages
    val visible = config.pages
    val index = visible.indexOf(page)
    val tiles = config.tiles(page)
    val layout = remember(page, tiles.size, showEditPages) {
        hubPageOptionLayout(page, tiles.size, showEditPages, extraPage(page) != null)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val hints = remember(context) { LocalEditorHints(context) }
    val hintId = "HubPageOptions.$page"
    var hint by remember(page) { mutableStateOf(hints.firstUse(hintId)) }
    fun hideHint() { hint = false }
    val settle = remember(page) { Animatable(0f) }
    LaunchedEffect(page) { settle.animateTo(1f, spring(Springs.sheet.damping, Springs.sheet.stiffness)) }
    Column(modifier.fillMaxSize()) {
        ParcBlocks(Metrics.rowGapDp, Modifier.weight(1f)
            .graphicsLayer {
                val scale = Metrics.pageOptionsSettleScale + (1f - Metrics.pageOptionsSettleScale) * settle.value
                scaleX = scale
                scaleY = scale
            }
            .verticalScroll(rememberScrollState()).parcBottomInset()) {
            SettingsPanel(skin) {
                BasicText(name, style = settingsText(skin).copy(fontSize = skin.typography.title.sizeSp.sp))
                BasicText(layout.summary, style = settingsText(skin, skin.colors.textSecondary, true),
                    modifier = Modifier.padding(top = Metrics.lineGapDp.dp))
            }
            if (hint) HubOptionHint(skin, "Changes save automatically.", ::hideHint)
            SettingsSection(HubOptionSection.ThisPage.title, skin)
            SettingsCard(skin) {
                SettingRow("Show on Home", skin, SettingKind.Toggle, checked = shown) {
                    update { it.showPage(page, !shown) }
                    if (shown) undo.offer { update { it.showPage(page, true) } }
                }
                if (undo.action != null) {
                    SettingRow("Undo", skin, SettingKind.Action, description = "Undo the last change") { undo.run() }
                }
                HubOptionMovePair(skin, "Show $name before the previous visible page", "Show $name after the next visible page",
                    canEarlier = shown && index > 0, canLater = shown && index < visible.lastIndex,
                    onEarlier = { update { it.moveVisiblePage(page, -1) } },
                    onLater = { update { it.moveVisiblePage(page, 1) } })
                HubPageSizeChoices(page, skin, config, update)
                if (!shown) BasicText("Page hidden from Home. Undo brings it back.", Modifier.parcRowPadding(),
                    style = settingsText(skin, skin.colors.textSecondary, true))
            }
            layout.ownSection?.let { section ->
                SettingsSection(section.title, skin)
                when (page) {
                    WeatherVariant.PageId -> WeatherVariant.Options(skin)
                    TodayPage.Id -> TodayOptionChoices(skin, config, update)
                    StepsPage.Id -> StepsOptionChoices(skin)
                    RhythmPage.Id -> RhythmOptionChoices(skin)
                    SkyPage.Id -> SkyOptionChoices(skin)
                    YearPage.Id -> YearOptionChoices(skin)
                    CommonplacePage.Id -> QuotesOptionChoices(skin)
                    LibraryPage.Id -> LibraryOptionChoices(skin)
                    else -> extraPage(page)?.optionChoices?.invoke(skin)
                }
            }
            if (tiles.isNotEmpty()) {
                SettingsSection(HubOptionSection.Tiles.title, skin)
                tiles.forEachIndexed { tileIndex, tile ->
                    val tileShown = tile !in config.hiddenTiles[page].orEmpty()
                    SettingsCard(skin) {
                        SettingRow(hubTileLabel(page, tile), skin, SettingKind.Toggle,
                            description = "Show this on $name", checked = tileShown) {
                            update { it.showTile(page, tile, !tileShown) }
                            if (tileShown) undo.offer { update { it.showTile(page, tile, true) } }
                        }
                        HubOptionMovePair(skin, "Show $tile before the previous tile", "Show $tile after the next tile",
                            canEarlier = tileIndex > 0, canLater = tileIndex < tiles.lastIndex,
                            onEarlier = { update { it.move(page, tile, -1) } },
                            onLater = { update { it.move(page, tile, 1) } })
                    }
                }
            }
            SettingsSection(HubOptionSection.More.title, skin)
            SettingsCard(skin) {
                layout.moreRows.forEachIndexed { rowIndex, row ->
                    val last = rowIndex == layout.moreRows.lastIndex
                    when (row) {
                        HubOptionMoreRow.WhatATapDoes -> SettingRow("What a tap does", skin, SettingKind.Page,
                            divider = !last) { onFullSettings(spec.fullSettings) }
                        HubOptionMoreRow.EditPages -> SettingRow("Edit pages", skin, SettingKind.Page,
                            divider = !last) { onFullSettings(SettingsPage.HubPages) }
                        HubOptionMoreRow.FullSettings -> SettingRow("Full settings", skin, SettingKind.Page,
                            divider = !last) { onFullSettings(spec.fullSettings) }
                    }
                }
            }
        }
    }
}

/** A small, dismissible one-time line; it is not a card and never repeats the page description. */
@Composable
private fun HubOptionHint(skin: Skin, text: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().parcRowPadding(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
        BasicText(text, Modifier.weight(1f), style = settingsText(skin, skin.colors.textSecondary, true))
        Box(Modifier.heightIn(min = Metrics.minTouchDp.dp).clickable(role = Role.Button, onClick = onDismiss)
            .padding(horizontal = Metrics.itemGapDp.dp), contentAlignment = Alignment.Center) {
            BasicText("Hide", style = settingsText(skin, skin.colors.readableAccent, true))
        }
    }
}

/** One number with its value on the row: `− 0.75 m +`, or `− 8,000 steps +`. */
@Composable
internal fun HubOptionStepper(label: String, value: String, skin: Skin,
    canDecrease: Boolean, canIncrease: Boolean, onDecrease: () -> Unit, onIncrease: () -> Unit,
    description: String = "", divider: Boolean = true,
) {
    Row(Modifier.fillMaxWidth().parcRowPadding(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
        Column(Modifier.weight(1f)) {
            BasicText(label, style = settingsText(skin))
            if (description.isNotBlank()) BasicText(description,
                style = settingsText(skin, skin.colors.textSecondary, true),
                modifier = Modifier.padding(top = Metrics.lineGapDp.dp))
        }
        HubOptionButton("−", skin, "Decrease $label", canDecrease, onDecrease)
        BasicText(value, Modifier.widthIn(min = Metrics.menuRowHeightDp.dp).padding(horizontal = Metrics.lineGapDp.dp)
            .semantics { contentDescription = "$label, $value" },
            style = settingsText(skin, skin.colors.readableAccent, true).copy(textAlign = TextAlign.Center), maxLines = 2)
        HubOptionButton("+", skin, "Increase $label", canIncrease, onIncrease)
    }
    if (divider) SettingsDivider(skin)
}

/** The compact pair of move buttons, one row, shared by the page itself and its tile order. */
@Composable
internal fun HubOptionMovePair(skin: Skin, earlierDescription: String, laterDescription: String,
    canEarlier: Boolean, canLater: Boolean, onEarlier: () -> Unit, onLater: () -> Unit,
    labels: Pair<String, String> = "Move earlier" to "Move later",
) {
    Row(Modifier.fillMaxWidth().parcRowPadding(), horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
        HubOptionButton(labels.first, skin, earlierDescription, canEarlier, onEarlier, Modifier.weight(1f))
        HubOptionButton(labels.second, skin, laterDescription, canLater, onLater, Modifier.weight(1f))
    }
    SettingsDivider(skin)
}

@Composable
private fun HubOptionButton(text: String, skin: Skin, description: String, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(skin.shape.surfaceCornerDp.dp)
    Box(modifier.heightIn(min = Metrics.touchTargetDp.dp).widthIn(min = Metrics.menuRowHeightDp.dp).clip(shape)
        .border(skin.shape.surfaceBorderDp.dp,
            Color((if (enabled) skin.colors.lineActive else skin.colors.lineBase).toInt()), shape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }
        .padding(horizontal = Metrics.itemGapDp.dp, vertical = Metrics.lineGapDp.dp),
        contentAlignment = Alignment.Center) {
        BasicText(text, style = settingsText(skin, if (enabled) skin.colors.textPrimary else skin.colors.textSecondary, true)
            .copy(textAlign = TextAlign.Center), maxLines = 2)
    }
}

/**
 * T258: this page's own size. The three buttons show the size the page has now; choosing one gives the
 * page its own size. "Same as every page" hands it back to the default (the global size in Hubs settings,
 * which the grip also changes for pages without their own).
 */
@Composable
internal fun HubPageSizeChoices(page: String, skin: Skin, config: HubConfig, update: ((HubConfig) -> HubConfig) -> Unit) {
    val own = page in config.pageSizes
    SettingsSegmented(if (own) "Size of this page" else "Size of this page (same as every page)", skin,
        HubSize.entries.map { it.name }, config.sizeFor(page).ordinal, divider = own) { index ->
        update { it.withPageSize(page, HubSize.entries[index]) }
    }
    if (own) SettingRow("Same as every page", skin, SettingKind.Action, divider = false,
        description = "Use the default size again") { update { it.withPageSize(page, null) } }
}

@Composable
internal fun HubSizeChoices(skin: Skin, config: HubConfig, update: ((HubConfig) -> HubConfig) -> Unit) {
    SettingsSegmented("Hub size", skin, HubSize.entries.map { it.name }, config.size.ordinal, divider = false) { index ->
        update { it.copy(size = HubSize.entries[index]) }
    }
}

@Composable
internal fun ClockOptionChoices(skin: Skin, config: HubConfig, update: ((HubConfig) -> HubConfig) -> Unit) {
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
