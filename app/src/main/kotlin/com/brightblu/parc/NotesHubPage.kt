package com.brightblu.parc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.brightblu.parc.feature.settings.ui.SettingsTheme
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import java.util.UUID

@Composable
internal fun NotesHubPage(skin: Skin) {
    val store = rememberNotesStore()
    var loadError by remember { mutableStateOf(false) }
    val notes by remember(store) { store.notes.catchNotes { loadError = true } }.collectAsState(initial = null)
    val undo by store.undoAvailable.collectAsState()
    val writeError by store.error.collectAsState()
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorText by rememberSaveable { mutableStateOf("") }
    var editorPin by rememberSaveable { mutableStateOf(false) }
    var editorTime by rememberSaveable { mutableStateOf(0L) }
    fun open(note: Note) { editorId = note.id; editorText = note.text; editorPin = note.pinned; editorTime = note.updatedAt }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Quick jottings", Modifier.weight(1f), style = noteStyle(skin).copy(fontSize = skin.typography.title.sizeSp.sp))
            NoteAction("+", skin, "Add Note", enabled = notes != null && !loadError) {
                open(Note(UUID.randomUUID().toString(), "", updatedAt = System.currentTimeMillis()))
            }
        }
        if (undo) Row(verticalAlignment = Alignment.CenterVertically) {
            NoteText("Note deleted", skin, Modifier.weight(1f))
            NoteAction("Undo", skin, action = store::undoDelete)
        }
        if (loadError || writeError) NoteText("Couldn’t save or read notes. Reopen the Note to retry.", skin)
        Column(Modifier.weight(1f).verticalScroll(rememberHubScrollState()), verticalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp)) {
            if (notes == null && !loadError) NoteText("Loading notes…", skin)
            else if (notes?.isEmpty() == true) NoteText(hubTemplates.getValue("notes").empty, skin)
            orderedNotes(notes.orEmpty()).take(if (LocalHubSize.current == HubSize.Compact) 1 else if (LocalHubSize.current == HubSize.Standard) 3 else Int.MAX_VALUE).forEach { note ->
                Column(Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp)
                    .background(Color(skin.colors.surface.toInt()), RoundedCornerShape(skin.shape.surfaceCornerDp.dp))
                    .clickable { open(note) }
                    .padding(Metrics.hudCellGapDp.dp)) {
                    if (note.pinned) NoteText("Pinned", skin, secondary = true)
                    val lines = noteLines(note.text).take(Metrics.notePreviewLines)
                    if (note.text.isBlank()) NoteText("Note", skin)
                    else lines.forEach { line ->
                        BasicText((when (line.checked) { true -> "☑ "; false -> "☐ "; null -> "" }) + line.text,
                            style = noteStyle(skin).copy(textDecoration = if (line.checked == true) TextDecoration.LineThrough else null),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    editorId?.let { id ->
        NoteEditor(Note(id, editorText, editorPin, editorTime), skin, store,
            changed = { editorText = it.text; editorPin = it.pinned; editorTime = it.updatedAt },
            close = { editorId = null })
    }
}

@Composable
private fun rememberNotesStore(): NotesStore {
    val context = LocalContext.current
    return remember(context) { NotesStore.get(context) }
}
private fun kotlinx.coroutines.flow.Flow<List<Note>>.catchNotes(onError: () -> Unit): kotlinx.coroutines.flow.Flow<List<Note>?> =
    kotlinx.coroutines.flow.flow {
        try { collect { emit(it) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { onError(); emit(null) }
    }

@Composable
internal fun NoteEditor(initial: Note, skin: Skin, store: NotesStore, changed: (Note) -> Unit, close: () -> Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    val saver = remember(initial.id, store) { NoteAutosave(store.scope, store::save) }
    val error by store.error.collectAsState()
    fun change(note: Note) {
        draft = note.copy(updatedAt = System.currentTimeMillis()); changed(draft); saver.change(draft)
    }
    fun finish() { saver.flush(); close() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, saver) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) saver.flush() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); saver.flush() }
    }
    Dialog(onDismissRequest = ::finish, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        SettingsTheme(skin) {
            Column(Modifier.fillMaxSize().background(Color(skin.colors.bg.toInt())).safeDrawingPadding().imePadding()
                .padding(horizontal = Metrics.textGutterDp.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    NoteText("Note", skin, Modifier.weight(1f))
                    NoteAction("Done", skin, action = ::finish)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(draft.pinned, { change(draft.copy(pinned = it)) }, Modifier.semantics { contentDescription = "Pin" })
                        NoteText("Pin", skin)
                    }
                    NoteAction("Delete", skin) { saver.discard(); store.delete(draft); close() }
                }
                if (error) NoteAction("Retry", skin) { store.save(draft) }
                NoteText(if (error) "Couldn’t save. Your text is still here." else "Auto-saves · start a checklist line with - ", skin, secondary = true)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    BasicTextField(draft.text, { change(draft.copy(text = it)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.atlasPreviewHeightDp.dp)
                            .padding(vertical = Metrics.hudCellGapDp.dp).semantics { contentDescription = "Note text" },
                        textStyle = noteStyle(skin), cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
                        decorationBox = { inner ->
                            if (draft.text.isEmpty()) NoteText("Write a Note…", skin, secondary = true)
                            inner()
                        })
                    val checklist = noteLines(draft.text).mapIndexedNotNull { index, line -> if (line.checked != null) index to line else null }
                    if (checklist.isNotEmpty()) {
                        NoteText("Checklist", skin, secondary = true)
                        checklist.forEach { (index, line) ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(line.checked == true, { change(draft.copy(text = toggleNoteLine(draft.text, index))) },
                                    Modifier.semantics { contentDescription = line.text.ifEmpty { "Checklist item" } })
                                BasicText(line.text, Modifier.weight(1f), style = noteStyle(skin).copy(
                                    textDecoration = if (line.checked == true) TextDecoration.LineThrough else null))
                            }
                        }
                    }
                }
            }
        }
    }
}
private fun noteStyle(skin: Skin, secondary: Boolean = false) = TextStyle(
    color = Color((if (secondary) skin.colors.textSecondary else skin.colors.textPrimary).toInt()),
    fontSize = skin.typography.menu.sizeSp.sp,
    fontFamily = if (skin.typography.fallbackFamily == "monospace") androidx.compose.ui.text.font.FontFamily.Monospace else null)
@Composable
private fun NoteText(text: String, skin: Skin, modifier: Modifier = Modifier, secondary: Boolean = false) {
    BasicText(text, modifier, style = noteStyle(skin, secondary))
}
@Composable
private fun NoteAction(text: String, skin: Skin, description: String = text, enabled: Boolean = true, action: () -> Unit) {
    Box(Modifier.heightIn(min = Metrics.menuRowHeightDp.dp).widthIn(min = Metrics.menuRowHeightDp.dp)
        .semantics { contentDescription = description }.clickable(enabled = enabled, onClick = action)
        .padding(Metrics.gridDp.dp), contentAlignment = Alignment.Center) {
        BasicText(text, style = noteStyle(skin).copy(color = Color(skin.colors.accent.toInt())))
    }
}
