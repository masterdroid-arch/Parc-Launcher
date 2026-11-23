package com.brightblu.parc

import com.brightblu.parc.model.textFamily
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
import com.brightblu.parc.model.ParcPage
import com.brightblu.parc.model.ParcCardContent
import com.brightblu.parc.model.Skin
import java.util.UUID

@Composable
internal fun NotesHubPage(skin: Skin) {
    val store = rememberNotesStore()
    val context = LocalContext.current
    var loadError by remember { mutableStateOf(false) }
    val notes by remember(store) { store.notes.catchNotes { loadError = true } }.collectAsState(initial = null)
    val undo by store.undoAvailable.collectAsState()
    val writeError by store.error.collectAsState()
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorText by rememberSaveable { mutableStateOf("") }
    var editorPin by rememberSaveable { mutableStateOf(false) }
    var editorTime by rememberSaveable { mutableStateOf(0L) }
    var editorTitle by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    // T183: a tapped reminder (or finished note timer) opens its note here once the list has loaded.
    val request by NoteOpenRequests.pending.collectAsState()
    LaunchedEffect(request, notes) {
        val id = request ?: return@LaunchedEffect
        val list = notes ?: return@LaunchedEffect
        list.firstOrNull { it.id == id }?.let {
            editorId = it.id; editorText = it.text; editorPin = it.pinned; editorTime = it.updatedAt; editorTitle = it.title
        }
        NoteOpenRequests.consume(id)
    }
    fun open(note: Note) {
        editorId = note.id; editorText = note.text; editorPin = note.pinned; editorTime = note.updatedAt; editorTitle = note.title
    }
    // T237: a new note is newest-first; bring the list back to the top when one arrives so it is seen at once.
    val listScroll = rememberHubScrollState()
    var previousCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(notes) {
        val list = notes ?: return@LaunchedEffect
        val previous = previousCount
        if (previous != null && list.size > previous) listScroll.scrollTo(0)
        previousCount = list.size
    }
    Column(Modifier.fillMaxSize().hubPageScroll(listScroll)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Quick jottings", Modifier.weight(1f), style = hubTitleStyle(skin))
            NoteAction("+", skin, "Add Note", enabled = notes != null && !loadError) {
                if (query.isNotEmpty()) query = ""
                open(Note(UUID.randomUUID().toString(), "", updatedAt = System.currentTimeMillis()))
            }
        }
        NoteSearchField(query, skin, enabled = notes != null && !loadError) { query = it }
        if (undo) Row(verticalAlignment = Alignment.CenterVertically) {
            NoteText("Note deleted", skin, Modifier.weight(1f))
            NoteAction("Undo", skin, action = store::undoDelete)
        }
        if (loadError || writeError) NoteText("Couldn’t save or read notes. Reopen the Note to retry.", skin)
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
            val shown = remember(notes, query) { notes?.let { NoteSearch.results(it, query) }.orEmpty() }
            if (notes == null && !loadError) NoteText("Loading notes…", skin)
            else if (notes?.isEmpty() == true) NoteText(hubTemplates.getValue("notes").empty, skin)
            else if (notes?.isNotEmpty() == true && shown.isEmpty()) NoteText(NoteSearch.emptyMessage(notes.orEmpty(), query).orEmpty(), skin)
            // A search shows every match; the hub-size cap is only for the plain list.
            shown.take(if (query.isNotBlank()) Int.MAX_VALUE else if (LocalHubSize.current == HubSize.Compact) 1 else if (LocalHubSize.current == HubSize.Standard) 3 else Int.MAX_VALUE).forEach { note ->
                Column(Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp)
                    .background(Color(skin.colors.surface.toInt()), RoundedCornerShape(skin.shape.surfaceCornerDp.dp))
                    .clickable { open(note) }) {
                    ParcCardContent {
                        if (note.pinned) NoteText("Pinned", skin, secondary = true)
                        if (note.remindAt > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                            NoteBell(Color(skin.colors.textSecondary.toInt()), Modifier.padding(end = Metrics.lineGapDp.dp))
                            NoteText(NoteReminderRules.whenText(note.remindAt, System.currentTimeMillis(), java.time.ZoneId.systemDefault(),
                                android.text.format.DateFormat.is24HourFormat(context)), skin, secondary = true)
                        }
                        val explicitTitle = NoteTitles.normalize(note.title)
                        if (explicitTitle.isNotEmpty()) BasicText(
                            explicitTitle, style = noteStyle(skin).copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val lines = NoteMarkup.parse(note.text).take(
                            if (explicitTitle.isEmpty()) Metrics.notePreviewLines else (Metrics.notePreviewLines - 1).coerceAtLeast(0))
                        if (note.text.isBlank() && explicitTitle.isEmpty()) NoteText("Note", skin)
                        else lines.forEachIndexed { index, line ->
                            val heading = explicitTitle.isEmpty() && index == 0
                            BasicText(notePreviewLine(line, skin),
                                style = if (heading) noteStyle(skin).copy(fontWeight = FontWeight.SemiBold) else noteStyle(skin),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    editorId?.let { id ->
        NoteEditor(Note(id, editorText, editorPin, editorTime, title = editorTitle), skin, store,
            changed = { editorText = it.text; editorPin = it.pinned; editorTime = it.updatedAt; editorTitle = it.title },
            close = { editorId = null })
    }
}

/** 1.5.1: the slim search field the Commonplace page also uses; one 36 dp line, not a card. */
@Composable
private fun NoteSearchField(query: String, skin: Skin, enabled: Boolean, onQuery: (String) -> Unit) {
    HubSearchField(query, "Search notes", "Search notes", skin, Modifier.padding(bottom = Metrics.itemGapDp.dp), enabled, onQuery)
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

internal fun noteStyle(skin: Skin, secondary: Boolean = false) = TextStyle(
    color = Color((if (secondary) skin.colors.textSecondary else skin.colors.textPrimary).toInt()),
    fontSize = skin.typography.menu.sizeSp.sp,
    fontFamily = skin.textFamily)
@Composable
internal fun NoteText(text: String, skin: Skin, modifier: Modifier = Modifier, secondary: Boolean = false) {
    BasicText(text, modifier, style = noteStyle(skin, secondary))
}
@Composable
internal fun NoteAction(text: String, skin: Skin, description: String = text, enabled: Boolean = true, action: () -> Unit) {
    Box(Modifier.heightIn(min = Metrics.menuRowHeightDp.dp).widthIn(min = Metrics.menuRowHeightDp.dp)
        .semantics { contentDescription = description }.clickable(enabled = enabled, onClick = action)
        .padding(Metrics.lineGapDp.dp), contentAlignment = Alignment.Center) {
        BasicText(text, style = noteStyle(skin).copy(color = Color(skin.colors.accent.toInt())))
    }
}
