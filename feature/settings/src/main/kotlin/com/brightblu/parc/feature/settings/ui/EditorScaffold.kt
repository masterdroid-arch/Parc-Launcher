package com.brightblu.parc.feature.settings.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcBlocks
import com.brightblu.parc.model.parcBottomInset
import com.brightblu.parc.model.Skin

/** Stable identities are local education state, deliberately absent from Parc's backup allow-list. */
enum class EditorSpec(val title: String, val instruction: String, val empty: String) {
    MainApps("Main apps: the 4 apps on the inner ring", "Tap an app to Add or Remove. Use Move arrows to reorder; changes save automatically.", "No main apps. Add an app from the list below."),
    Folders("Folders: groups of apps on Home", "Tap a folder to edit. Hold and drag an app to Move it; changes save automatically.", "No folders. Tap Add folder to start."),
    HiddenApps("Hidden apps: apps left out of Parc", "Tap a switch to Remove an app from Parc or bring it back; changes save automatically.", "No matching apps. Clear the search to choose an app."),
    Rotation("Rotation: positions of apps on the arcs", "On Home, drag along an arc to Move its apps. Tap an app to open it; positions save automatically.", "Return to Home to rotate an arc."),
    HubPages("Hub pages: what appears above the arcs", "Tap a switch to Add or Remove a page. Use Move arrows to reorder; changes save automatically.", "No pages shown. Add a page using its switch below."),
    Agenda("Agenda: calendars shown on the hub", "Tap Days ahead or a calendar switch to choose what appears; changes save automatically.", "No calendars. Add one in your calendar app, then return here."),
    Alerts("Alerts: warnings and history on the hub", "Tap a switch or threshold to choose a warning; changes save automatically.", "No alerts enabled. Turn on Stats alerts and choose a reading below."),
    Skin("Skin editor: colours used across Parc", "Tap a colour, then drag its controls. Valid names and colours save automatically.", "No custom skins. Add one by choosing a built-in skin below."),
    CommsApps("Comms apps: messages shown on the hub", "Tap a switch to Add or Remove an app's messages; changes save automatically.", "No message apps selected. Add one using its switch below."),
    QuickReplies("Quick replies: text you can send from Comms", "Type a reply and tap Add. Tap Remove to delete a reply; changes save automatically.", "No quick replies. Type a reply below and tap Add.");
}

const val EditorHintStoreName = "editor_hints_local"

interface EditorHints {
    fun seen(id: String): Boolean
    fun markSeen(id: String)
}
fun EditorHints.firstUse(id: String): Boolean {
    if (seen(id)) return false
    markSeen(id)
    return true
}

class LocalEditorHints(context: Context) : EditorHints {
    private val prefs = context.applicationContext.getSharedPreferences(EditorHintStoreName, Context.MODE_PRIVATE)
    override fun seen(id: String) = prefs.getBoolean(id, false)
    override fun markSeen(id: String) { prefs.edit().putBoolean(id, true).apply() }
}

/**
 * Header and instruction stay outside the scrolling body. They wrap at large text sizes. The page frame
 * around it ([ParcPage] or the Settings page frame) owns the gutter and the gesture-bar space (T166), so the
 * scaffold adds only the spacing between its own parts.
 */
@Composable
fun EditorScaffold(
    spec: EditorSpec, skin: Skin, modifier: Modifier = Modifier,
    empty: Boolean = false, undo: (() -> Unit)? = null,
    preview: (@Composable () -> Unit)? = null,
    scroll: Boolean = true,
    title: String = spec.title, instruction: String = spec.instruction,
    educationId: String = spec.name,
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val hints = remember(context) { LocalEditorHints(context) }
    var hint by remember(educationId) { mutableStateOf(hints.firstUse(educationId)) }
    Column(modifier.fillMaxSize()) {
        BasicText(title, style = settingsText(skin).copy(fontSize = skin.typography.title.sizeSp.sp))
        BasicText(instruction, style = settingsText(skin, skin.colors.textSecondary, true),
            modifier = Modifier.padding(vertical = Metrics.sectionHeaderGapDp.dp))
        if (undo != null) SettingsCard(skin) {
            SettingRow("Undo", skin, SettingKind.Action, description = "Undo the last change", divider = false, onClick = undo)
        }
        ParcBlocks(Metrics.rowGapDp, Modifier.weight(1f).then(if (scroll) Modifier.verticalScroll(rememberScrollState()).parcBottomInset() else Modifier)) {
            if (hint) SettingsCard(skin) {
                SettingRow("First time here", skin, SettingKind.Action, description = instruction,
                    descriptionMaxLines = Int.MAX_VALUE, icon = "✓", divider = false) {
                    hints.markSeen(educationId); hint = false
                }
            }
            preview?.invoke()
            if (empty) BasicText(spec.empty, style = settingsText(skin, skin.colors.textSecondary),
                modifier = Modifier.padding(vertical = Metrics.sectionHeaderGapDp.dp))
            content()
        }
    }
}

/** Pinned, expiring Undo shared by immediate-save editors. */
@Stable
class EditorUndo {
    var action by mutableStateOf<(() -> Unit)?>(null)
        private set
    private var serial by mutableLongStateOf(0L)
    private val timing = EditorTiming { android.os.SystemClock.elapsedRealtime() }
    fun offer(restore: () -> Unit) { serial++; timing.offerUndo(restore); action = { timing.undo(); Unit } }
    fun run() { val restore = action; action = null; restore?.invoke() }
    @Composable internal fun Expire() {
        val version = serial
        LaunchedEffect(version) { kotlinx.coroutines.delay(Metrics.focusUndoMs); if (version == serial) action = null }
    }
}
@Composable fun rememberEditorUndo(): EditorUndo = remember { EditorUndo() }.also { it.Expire() }
