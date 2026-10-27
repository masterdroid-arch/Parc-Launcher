package com.brightblu.parc

import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.focus.*
import com.brightblu.parc.feature.settings.ui.*
import com.brightblu.parc.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The same production destination is used from Settings and the Home Focus page. */
@Composable
internal fun FocusSettingsPage(
    skin: Skin, focus: FocusState, modes: List<FocusMode>, repository: FocusRepository,
    apps: List<AppEntry>, onBack: () -> Unit,
    folders: List<Folder> = emptyList(), skins: List<Skin> = emptyList(),
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val dragThreshold = Metrics.touchTargetDp * LocalDensity.current.density
    val modeBounds = remember { mutableMapOf<String, androidx.compose.ui.geometry.Rect>() }
    val modeSaver = remember { Saver<FocusMode?, String>(
        save = { it?.let { mode -> FocusJson.encodeModes(listOf(mode)) } ?: "" },
        restore = { FocusJson.decodeModes(it).firstOrNull() }) }
    var editing by rememberSaveable(stateSaver = modeSaver) { mutableStateOf<FocusMode?>(null) }
    var original by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var more by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf(false) }
    var reordering by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var issue by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var undoRevision by remember { mutableIntStateOf(0) }
    var undoVisible by remember { mutableStateOf(false) }
    var undoModeName by remember { mutableStateOf<String?>(null) }
    var exit by remember { mutableStateOf<FocusExit?>(null) }
    val timing = remember { FocusEditTiming { android.os.SystemClock.elapsedRealtime() } }
    val saveLock = remember { Mutex() }
    val installed = remember(apps) { apps.distinctBy { it.packageName }.sortedBy { it.label } }
    fun update(next: FocusMode) { if (focus.strictSession) { issue = "End the strict Mode before editing"; return }; editing = next; timing.changed(); revision++; notice = "Saving…"; issue = "" }
    suspend fun save(): Boolean = withContext(NonCancellable) { saveLock.withLock {
        val draft = editing ?: return@withLock true
        val savingGeneration = timing.generation
        val cleanName = name.trim()
        issue = focusEditIssue(draft, cleanName, original, repository.modes.value)
        if (issue.isNotEmpty()) return@withLock false
        val clean = draft.copy(name = cleanName)
        val stored = repository.modes.value.firstOrNull { it.name == original }
        if (stored != null && clean.copy(ledger = stored.ledger) == stored) { notice = "Saved"; timing.saved(); return@withLock true }
        if (!repository.save(clean, original)) { issue = "End the strict Mode before editing"; return@withLock false }
        if (undoModeName == original) undoModeName = cleanName
        original = cleanName
        if (editing == draft) editing = clean
        timing.saved(savingGeneration)
        if (timing.generation == savingGeneration) notice = "Saved"
        true
    } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val flush by rememberUpdatedState<suspend () -> Unit>({ save(); Unit })
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) scope.focusAction({ issue = it }) { flush() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { if (editing != null) { timing.changed(); revision++ } }
    // Navigation flushes pending changes; an in-flight write cannot be cancelled by a newer edit.
    LaunchedEffect(revision) {
        if (editing != null && revision > 0) {
            delay(Metrics.focusSaveDebounceMs)
            if (timing.ready()) try { save() } catch (_: Exception) { issue = "Could not save. Try Done again." }
        }
    }
    fun open(item: FocusMode) { editing = item; original = item.name; name = item.name; page = ""; more = false; issue = ""; notice = "" }
    fun navigate(next: String) { if (focus.strictSession) { issue = "End the strict Mode before editing"; return }; scope.focusAction({ issue = it }) { if (save()) { page = next; more = false; query = "" } } }
    fun back() {
        if (editing != null) scope.focusAction({ issue = it }) {
            if (save()) when {
                page == "Always allowed" -> { page = "Apps"; query = "" }
                page == "Customise" -> page = "How strict"
                page == "Messages" -> { page = "Quiet notifications"; query = "" }
                page == "Focus skin" -> page = "Look"
                page.isNotEmpty() -> page = ""
                else -> editing = null
            }
        } else if (presets) presets = false else onBack()
    }
    fun offerUndo(action: () -> Unit) { timing.offerUndo(action); undoVisible = true; undoRevision++ }
    LaunchedEffect(undoRevision) { if (undoVisible) { delay(Metrics.focusUndoMs); undoVisible = false } }
    BackHandler { back() }
    exit?.let { FocusExitDialog(repository, it, skin, onDismiss = { exit = null }) }
    SettingsPageFrame(skin, ::back) {
        Box(Modifier.fillMaxSize()) {
        key(editing != null, page, presets) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.textGutterDp.dp).padding(bottom = Metrics.keepOutBottomDp.dp),
                verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
                val mode = editing
                if (issue.isNotEmpty()) FocusCopy(issue, skin)
                // OPUS6 (audit F4): say so when saved modes could not be read; they are kept as they are.
                val unreadable by repository.unreadableModes.collectAsState()
                if (unreadable != 0) FocusCopy(focusUnreadableNotice(unreadable), skin)
                if (mode != null) {
                    FocusCopy(when (page) {
                        "" -> "What should ${mode.name} help you do?"
                        "Name and colour" -> "What will you call this Mode?"
                        "Apps" -> "What should Focus do with apps?"
                        "Always allowed" -> "Which apps should always be available?"
                        "When" -> "When should this Mode start?"
                        "How strict" -> "How much help do you want staying focused?"
                        "Customise" -> "What should happen when you need a pause?"
                        "Quiet notifications" -> "What should stay quiet?"
                        "Messages" -> "Which messages should stay out of Parc?"
                        "Focus skin" -> "Which colours should Home use?"
                        else -> "How should Home look?"
                    }, skin, true)
                    if (notice.isNotEmpty()) FocusCopy(notice, skin)
                    when (page) {
                        "" -> {
                            FocusRow("${mode.glyph} ${mode.name}", "Name and colour", false, skin) { navigate("Name and colour") }
                            FocusCopy(focusModeSentence(mode, apps, folders), skin)
                            FocusControls(mode, focus, skin, start = { scope.focusAction({ issue = it }) { if (save()) repository.quickStart(original) } }, pause = { exit = FocusExit.Pass }, end = { exit = FocusExit.End })
                            listOf("Apps" to focusAppsValue(mode, apps, folders), "When" to focusWhenValue(mode),
                                "How strict" to focusLevel(mode).name, "Quiet notifications" to focusQuietValue(mode),
                                "Look" to focusLookValue(mode)).forEach { (label, detail) ->
                                FocusRow(label, detail, false, skin) { navigate(label) }
                            }
                            FocusRow("More options", "Delete this Mode", more, skin) { more = !more }
                            if (more && !focus.strictSession) FocusRow("Delete Mode", "Undo is available for 8 seconds", false, skin, SettingKind.Action) {
                                scope.focusAction({ issue = it }) { if (save()) {
                                    val old = repository.modes.value.firstOrNull { it.name == original } ?: mode
                                    val position = repository.modes.value.indexOfFirst { it.name == original }
                                    repository.delete(original); editing = null
                                    offerUndo { scope.focusAction({ issue = it }) { repository.restoreDeleted(old, position) } }
                                } }
                            }
                        }
                        "Name and colour" -> {
                            FocusText(name, skin, "Mode name") { if (!focus.strictSession) { name = it.take(FocusDisplay.MaxNameLength); update(mode) } }
                            FocusCopy("Choose a symbol", skin)
                            FocusGlyphs.chunked(3).forEach { choices -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
                                choices.forEach { (glyph, label) -> FocusChoice("$glyph $label", mode.glyph == glyph, skin, Modifier.weight(1f)) { update(mode.copy(glyph = glyph)) } }
                            } }
                            FocusCopy("Choose a colour", skin)
                            FocusRow("Match my skin", "Follows your Home colours", mode.accent == null, skin, SettingKind.Choice) { update(mode.copy(accent = null)) }
                            FocusColours.chunked(4).forEach { choices -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
                                choices.forEach { (label, colour) -> Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(Metrics.focusControlDp.dp).background(Color(colour.toInt()), CircleShape)
                                        .border((if (mode.accent == colour) skin.lineWidths.activeDp * 2 else skin.lineWidths.baseDp).dp, Color(skin.colors.textPrimary.toInt()), CircleShape)
                                        .semantics { contentDescription = label; selected = mode.accent == colour; role = Role.RadioButton }
                                        .clickable { update(mode.copy(accent = colour)) })
                                    FocusCopy(label, skin)
                                } }
                            } }
                            FocusPreviewChip(mode.copy(name = name.ifBlank { "Mode" }), skins.firstOrNull { it.id == mode.skinId } ?: skin)
                        }
                        "Apps", "Always allowed" -> {
                            if (page == "Apps") {
                                FocusRow("Hide the distracting ones", "For example, hide social apps; everything else stays", mode.appPolicy == FocusAppPolicy.BlockList, skin, SettingKind.Choice) { update(mode.copy(appPolicy = FocusAppPolicy.BlockList)) }
                                FocusRow("Allow only what I need", "For example, keep reading and notes; hide everything else", mode.appPolicy == FocusAppPolicy.AllowList, skin, SettingKind.Choice) { update(mode.copy(appPolicy = FocusAppPolicy.AllowList)) }
                                FocusCopy(focusAppsValue(mode, apps, folders), skin)
                                FocusRow("Always allowed: Phone, Messages, Maps", "${focusAppCount(mode.alwaysAllowed.size)} selected · choose your own list", false, skin) { navigate("Always allowed") }
                                val suggested = folders.filter { it.name.contains("social", true) || it.name.contains("game", true) }
                                if (suggested.isNotEmpty()) FocusRow("Social and games", "Suggested · ${focusAppCount(suggested.sumOf { it.appIds.size })}", suggested.all { it.id.value in mode.folderIds }, skin, SettingKind.Toggle) {
                                    val ids = suggested.map { it.id.value }.toSet()
                                    update(mode.copy(folderIds = if (ids.all { it in mode.folderIds }) mode.folderIds - ids else mode.folderIds + ids))
                                }
                                folders.forEach { folder -> FocusRow(folder.name, focusAppCount(folder.appIds.size), folder.id.value in mode.folderIds, skin, SettingKind.Toggle) { update(mode.copy(folderIds = toggle(mode.folderIds, folder.id.value))) } }
                            } else FocusCopy("These apps stay available even when other apps are hidden. ${mode.alwaysAllowed.size} selected.", skin)
                            FocusText(query, skin, "Search apps") { query = it }
                            filterFocusApps(installed, query).forEach { app ->
                                val selection = if (page == "Apps") mode.hiddenPackages else mode.alwaysAllowed
                                FocusRow(app.label, "", app.packageName in selection, skin, SettingKind.Toggle) {
                                    update(if (page == "Apps") mode.copy(hiddenPackages = toggle(selection, app.packageName)) else mode.copy(alwaysAllowed = toggle(selection, app.packageName)))
                                }
                            }
                            FocusRow("Clear apps", "Undo is available for 8 seconds", false, skin, SettingKind.Action) {
                                val old = mode
                                update(if (page == "Apps") mode.copy(hiddenPackages = emptySet(), folderIds = emptySet()) else mode.copy(alwaysAllowed = emptySet()))
                                val clearingApps = page == "Apps"
                                undoModeName = original
                                offerUndo {
                                    val targetName = undoModeName ?: old.name
                                    fun restore(current: FocusMode) = restoreFocusApps(current, old, clearingApps)
                                    val current = editing
                                    if (current != null && original == targetName) update(restore(current))
                                    else scope.focusAction({ issue = it }) {
                                        repository.modes.value.firstOrNull { it.name == targetName }?.let { repository.save(restore(it)) }
                                    }
                                }
                            }
                        }
                        "When" -> {
                            val scheduled = FocusPolicies.schedules(mode).any { it.enabled && it.days.isNotEmpty() }
                            FocusRow("Only when I start it", "Start from the Mode screen or Home", !scheduled, skin, SettingKind.Choice) {
                                update(mode.copy(schedule = mode.schedule.copy(enabled = false), extraSchedules = mode.extraSchedules.map { it.copy(enabled = false) }))
                            }
                            FocusRow("On a schedule", "Starts and ends automatically on the days you choose", scheduled, skin, SettingKind.Choice) {
                                update(mode.copy(schedule = mode.schedule.copy(enabled = true, days = mode.schedule.days.ifEmpty { FocusDay.entries.take(5).toSet() })))
                            }
                            if (scheduled) FocusPolicies.schedules(mode).forEachIndexed { index, time ->
                                fun change(next: FocusSchedule) = update(if (index == 0) mode.copy(schedule = next) else mode.copy(extraSchedules = mode.extraSchedules.mapIndexed { i, old -> if (i == index - 1) next else old }))
                                FocusRow("Time ${index + 1}", if (time.overnight) "Ends the next day" else "Starts and ends the same day", time.enabled, skin, SettingKind.Toggle) { change(time.copy(enabled = !time.enabled)) }
                                FocusDay.entries.chunked(4).forEach { days -> Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) { days.forEach { day ->
                                    FocusChoice(day.label, day in time.days, skin) { change(time.copy(days = toggle(time.days, day))) }
                                } } }
                                FocusRow("Start time", time.startLabel, false, skin, SettingKind.Action) {
                                    TimePickerDialog(context, { _, hour, minute -> change(time.copy(startMinute = hour * 60 + minute)) }, time.startMinute / 60, time.startMinute % 60, true).show()
                                }
                                FocusRow("End time", time.endLabel, false, skin, SettingKind.Action) {
                                    TimePickerDialog(context, { _, hour, minute -> change(time.copy(endMinute = hour * 60 + minute)) }, time.endMinute / 60, time.endMinute % 60, true).show()
                                }
                                if (index > 0) FocusRow("Remove time", "Other times stay saved", false, skin, SettingKind.Action) { update(mode.copy(extraSchedules = mode.extraSchedules.filterIndexed { i, _ -> i != index - 1 })) }
                            }
                            FocusRow("Add another time", "For example, a second evening session", false, skin, SettingKind.Action) { update(mode.copy(extraSchedules = mode.extraSchedules + FocusSchedule(FocusDay.entries.take(5).toSet(), 18 * 60, 20 * 60))) }
                            FocusCopy("If two Modes overlap, the one higher in the list wins. Reorder on the Focus page.", skin)
                        }
                        "How strict" -> {
                            listOf(FocusLevel.Relaxed to "Opening a hidden app asks once. Pause as often as you need.",
                                FocusLevel.Firm to "Wait 5 seconds, then use one of 2 daily emergency passes lasting 5 minutes.",
                                FocusLevel.Strict to "A challenge is needed. You cannot edit or switch Modes while it runs.").forEach { (level, text) ->
                                FocusRow(level.name, text, focusLevel(mode) == level, skin, SettingKind.Choice) { update(applyFocusLevel(mode, level)) }
                            }
                            FocusCopy("Current choice: ${focusLevel(mode).name}", skin)
                            FocusRow("Customise", "Pass length and count, wait times, challenges and accountability code", false, skin) { navigate("Customise") }
                            FocusCopy("Parc only controls Parc. You can still switch launcher or uninstall, so this is a speed bump and a promise to yourself.", skin)
                        }
                        "Customise" -> FocusFrictionSettings(mode, skin) { update(it) }
                        "Quiet notifications" -> {
                            FocusRow("Quiet badges", "Hide the dots and counts on app icons", mode.silenceBadges, skin, SettingKind.Toggle) { update(mode.copy(silenceBadges = !mode.silenceBadges)) }
                            FocusRow("Do Not Disturb", "Ask Android to quiet interruptions. Allow access in Parc Settings → Permissions first.", mode.syncDnd, skin, SettingKind.Toggle) { update(mode.copy(syncDnd = !mode.syncDnd)) }
                            FocusRow("Messages in Parc", if (mode.hiddenComms.isEmpty()) "All shown. Choose apps whose messages should stay hidden in Parc." else "Hidden from ${focusAppCount(mode.hiddenComms.size)}. Choose which apps.", false, skin) { navigate("Messages") }
                            FocusCopy("Android’s notification shade stays available.", skin)
                        }
                        "Messages" -> {
                            FocusCopy("Hide messages in Parc from these apps. Android’s notification shade stays available.", skin)
                            FocusText(query, skin, "Search apps") { query = it }
                            filterFocusApps(installed, query).forEach { app -> FocusRow(app.label, "", app.packageName in mode.hiddenComms, skin, SettingKind.Toggle) { update(mode.copy(hiddenComms = toggle(mode.hiddenComms, app.packageName))) } }
                        }
                        "Focus skin" -> {
                            FocusHomePreview(mode, skins.firstOrNull { it.id == mode.skinId } ?: skin)
                            FocusRow("Use my usual skin", "Home keeps its current colours", mode.skinId == null, skin, SettingKind.Choice) { update(mode.copy(skinId = null)) }
                            skins.forEach { item -> FocusRow(item.name, "Preview these Home colours", mode.skinId == item.id, skin, SettingKind.Choice) { update(mode.copy(skinId = item.id)) } }
                        }
                        "Look" -> {
                            FocusHomePreview(mode, skins.firstOrNull { it.id == mode.skinId } ?: skin)
                            FocusRow("Greyscale icons", "Remove icon colours while this Mode runs", mode.iconTreatment == FocusIconTreatment.Greyscale, skin, SettingKind.Toggle) { update(mode.copy(iconTreatment = if (mode.iconTreatment == FocusIconTreatment.Greyscale) FocusIconTreatment.Normal else FocusIconTreatment.Greyscale)) }
                            FocusRow("Dim icons", "Make app icons quieter", mode.iconTreatment == FocusIconTreatment.Dimmed, skin, SettingKind.Toggle) { update(mode.copy(iconTreatment = if (mode.iconTreatment == FocusIconTreatment.Dimmed) FocusIconTreatment.Normal else FocusIconTreatment.Dimmed)) }
                            FocusRow("Focus skin", skins.firstOrNull { it.id == mode.skinId }?.name ?: if (mode.skinId == null) "Use my usual skin" else "Saved skin", false, skin) { navigate("Focus skin") }
                            FocusRow("More options", "Main apps and Home pages", more, skin) { more = !more }
                            if (more) {
                                FocusRow("Main and allowed apps only", "Keep your main apps and the apps chosen in this Mode", mode.mainAndAllowedOnly, skin, SettingKind.Toggle) { update(mode.copy(mainAndAllowedOnly = !mode.mainAndAllowedOnly)) }
                                HubCatalog.pages.forEach { home -> val pages = mode.hubPages ?: HubCatalog.pages.toSet()
                                    FocusRow(home.replaceFirstChar { it.uppercase() }, if (home == "comms") "Show messages and calls on Home" else "Show this Home page", home in pages, skin, SettingKind.Toggle) { update(mode.copy(hubPages = toggle(pages, home))) }
                                }
                                FocusRow("Use my usual Home pages", "Follow Parc Settings", mode.hubPages == null, skin, SettingKind.Action) { update(mode.copy(hubPages = null)) }
                            }
                        }
                    }
                    FocusRow("Done", "Changes save automatically", false, skin, SettingKind.Action) { back() }
                } else {
                    FocusCopy(if (presets || modes.isEmpty()) "What would you like to focus on?" else "Focus Modes", skin, true)
                    if (presets || modes.isEmpty()) {
                        FocusCopy("Choose a starting point. Check what it does, then Start when you are ready.", skin)
                        FocusPreset.entries.forEach { preset -> FocusRow(preset.name, FocusPresetDescriptions.getValue(preset), false, skin, SettingKind.Action) {
                            val base = applyFocusLevel(preset.mode(essentialFocusPackages(apps)), FocusLevel.Firm)
                            val unique = generateSequence(1) { it + 1 }.map { if (it == 1) base.name else "${base.name} $it" }.first { candidate -> modes.none { it.name == candidate } }
                            val suggested = if (preset in listOf(FocusPreset.Work, FocusPreset.Study, FocusPreset.Weekend)) folders.filter { it.name.contains("social", true) || it.name.contains("game", true) }.map { it.id.value }.toSet() else emptySet()
                            val next = base.copy(name = unique, folderIds = suggested)
                            scope.focusAction({ issue = it }) { if (repository.save(next)) { presets = false; open(next) } else issue = "End the strict Mode before creating another" }
                        } }
                    } else {
                        if (focus.breakUntilMillis != null) FocusCopy("Paused · Focus resumes automatically", skin)
                        val ordered = modes.sortedByDescending { it.priority }
                        fun move(item: FocusMode, delta: Int) { scope.focusAction({ issue = it }) { repository.move(item.name, delta) } }
                        ordered.forEachIndexed { index, item ->
                            Column(Modifier.onGloballyPositioned { modeBounds[item.name] = it.boundsInRoot() }
                                .pointerInput(item.name, ordered, dragThreshold) {
                                    var drag = 0f
                                    var start = 0f
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { point -> drag = 0f; start = (modeBounds[item.name]?.top ?: 0f) + point.y },
                                        onDrag = { change, amount -> change.consume(); drag += amount.y },
                                        onDragEnd = {
                                            if (kotlin.math.abs(drag) > dragThreshold) {
                                                val delta = focusDropDelta(ordered.map { it.name }, modeBounds.filterValues { it.width > 0f && it.height > 0f }.mapValues { it.value.center.y }, item.name, start + drag)
                                                if (delta != 0) move(item, delta)
                                            }
                                        })
                                }) {
                                SettingsCard(skin) {
                                    Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { open(item) }) {
                                        FocusModeChip(item, skins.firstOrNull { it.id == item.skinId } ?: skin, if (focus.activeName == item.name) FocusDisplay.clockLine(focus) else "${item.glyph} ${item.name}")
                                        Box(Modifier.padding(horizontal = Metrics.textGutterDp.dp)) { FocusCopy(focusModeSentence(item, apps, folders), skin) }
                                    }
                                }
                                FocusRow("Enable ${item.name}", "Schedules and Start are available when enabled", item.enabled, skin, SettingKind.Toggle) { scope.focusAction({ issue = it }) { repository.save(item.copy(enabled = !item.enabled)) } }
                                FocusControls(item, focus, skin, start = { scope.focusAction({ issue = it }) { repository.quickStart(item.name) } }, pause = { exit = FocusExit.Pass }, end = { exit = FocusExit.End })
                                if (reordering) Row {
                                    if (index > 0) Box(Modifier.weight(1f)) { FocusRow("Move up", "${item.name}", false, skin, SettingKind.Action) { move(item, -1) } }
                                    if (index < ordered.lastIndex) Box(Modifier.weight(1f)) { FocusRow("Move down", "${item.name}", false, skin, SettingKind.Action) { move(item, 1) } }
                                }
                            }
                        }
                        if (modes.size > 1) FocusRow("Reorder Modes", "If two Modes overlap, the one higher in the list wins. Hold and drag a card, or tap here for buttons.", reordering, skin, SettingKind.Toggle) { reordering = !reordering }
                        FocusRow("New Mode", "Work, Study, Sleep, Drive, Weekend or Custom", false, skin) { presets = true }
                    }
                }
            }
        }
        if (undoVisible) Box(Modifier.align(Alignment.BottomCenter).padding(Metrics.textGutterDp.dp)) {
            FocusRow("Undo", "Restore the last change", false, skin, SettingKind.Action) { timing.undo(); undoVisible = false }
        }
        }
    }
}

private fun <T> toggle(values: Set<T>, value: T): Set<T> = if (value in values) values - value else values + value
@Composable internal fun FocusText(value: String, skin: Skin, description: String, change: (String) -> Unit) {
    BasicTextField(value, change, singleLine = true, textStyle = settingsText(skin),
        decorationBox = { inner -> Box { if (value.isEmpty()) BasicText(description, style = settingsText(skin, skin.colors.textSecondary)); inner() } },
        cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
        modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp)
            .background(Color(skin.colors.surface.toInt()), RoundedCornerShape(Metrics.menuCornerDp.dp))
            .padding(Metrics.gridDp.dp * 2).semantics { contentDescription = description })
}
@Composable private fun FocusCopy(text: String, skin: Skin, title: Boolean = false) {
    BasicText(text, style = settingsText(skin, if (title) skin.colors.textPrimary else skin.colors.textSecondary),
        modifier = Modifier.fillMaxWidth().padding(vertical = Metrics.gridDp.dp * 2).then(if (title) Modifier.semantics { heading() } else Modifier))
}
@Composable private fun FocusRow(label: String, detail: String, selected: Boolean, skin: Skin, kind: SettingKind = SettingKind.Page, click: () -> Unit) {
    SettingsCard(skin) { SettingRow(label, skin, kind, description = detail, checked = selected, value = if (selected && kind == SettingKind.Choice) "Selected" else "", descriptionMaxLines = Int.MAX_VALUE, onClick = click) }
}
@Composable private fun FocusChoice(label: String, chosen: Boolean, skin: Skin, modifier: Modifier = Modifier, click: () -> Unit) {
    Box(modifier.heightIn(min = Metrics.focusControlDp.dp).widthIn(min = Metrics.focusControlDp.dp)
        .background(Color((if (chosen) skin.colors.accent else skin.colors.surface).toInt()), RoundedCornerShape(Metrics.menuCornerDp.dp))
        .border((if (chosen) skin.lineWidths.activeDp * 2 else skin.lineWidths.baseDp).dp,
            Color((if (chosen) skin.colors.textPrimary else skin.colors.lineBase).toInt()), RoundedCornerShape(Metrics.menuCornerDp.dp))
        .semantics { contentDescription = label; selected = chosen; role = Role.RadioButton }.clickable(onClick = click)
        .padding(Metrics.gridDp.dp), contentAlignment = Alignment.Center) {
        BasicText(label, style = settingsText(skin, if (chosen) skin.colors.bg else skin.colors.textPrimary))
    }
}
@Composable private fun FocusControls(mode: FocusMode, state: FocusState, skin: Skin, start: () -> Unit, pause: () -> Unit, end: () -> Unit) {
    if (state.activeName == mode.name) {
        FocusCopy(focusRemaining(state), skin)
        FocusRow("Pause", "Emergency pass · opens all apps temporarily", false, skin, SettingKind.Action, pause)
        FocusRow("End", "Your chosen wait and challenge apply", false, skin, SettingKind.Action, end)
    } else if (mode.enabled && !state.strictSession && state.breakUntilMillis == null) FocusRow("Start", "${mode.name} · 1 hour", false, skin, SettingKind.Action, start)
}
@Composable internal fun FocusPreviewChip(mode: FocusMode, skin: Skin) {
    FocusModeChip(mode, skin, "${mode.glyph} ${mode.name} · until 14:07")
}
@Composable internal fun FocusModeChip(mode: FocusMode, skin: Skin, text: String) {
    Box(Modifier.fillMaxWidth().background(Color(skin.colors.surface.toInt()), RoundedCornerShape(Metrics.menuCornerDp.dp)).padding(Metrics.textGutterDp.dp)) {
        val tint = com.brightblu.parc.core.data.skins.ColorMath.ensureContrast(mode.accent ?: skin.colors.accent,
            listOf(skin.colors.surface), com.brightblu.parc.core.data.skins.ColorMath.TextContrast, skin.colors.textPrimary)
        BasicText(text, style = settingsText(skin, tint))
    }
}
@Composable private fun FocusHomePreview(mode: FocusMode, skin: Skin) {
    SettingsCard(skin) {
      Column(Modifier.padding(Metrics.gridDp.dp * 2)) {
        FocusCopy("Home preview · ${focusLookValue(mode)}", skin)
        FocusPreviewChip(mode, skin)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("Phone", "Notes", "Maps").forEach { label ->
                val tint = when (mode.iconTreatment) { FocusIconTreatment.Normal -> skin.colors.accent; FocusIconTreatment.Greyscale -> skin.colors.textSecondary; FocusIconTreatment.Dimmed -> skin.colors.lineBase }
                Box(Modifier.size(Metrics.focusControlDp.dp).background(Color(tint.toInt()), CircleShape), contentAlignment = Alignment.Center) {
                    BasicText(label.take(1), style = settingsText(skin, skin.colors.bg))
                }
            }
        }
      }
    }
}

/** OPUS6 (audit F4): the plain notice for saved Focus modes this version cannot read. */
internal fun focusUnreadableNotice(count: Int): String = when {
    count < 0 -> "Your saved Focus modes could not be read. They are kept unchanged, and changes here are not saved until this is fixed (restore a backup)."
    count == 1 -> "1 saved Focus mode could not be read. It is kept unchanged."
    else -> "$count saved Focus modes could not be read. They are kept unchanged."
}
