package com.brightblu.parc

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.brightblu.parc.feature.settings.ui.SettingsTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcPage
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.WritingRules

private fun spanFor(run: StyledRun, kind: LineKind, skin: Skin): SpanStyle = SpanStyle(
    color = if (run.mark) Color(skin.colors.textSecondary.toInt()) else Color.Unspecified,
    fontWeight = if (run.bold) FontWeight.Bold else null,
    fontStyle = if (run.italic) FontStyle.Italic else null,
    textDecoration = if (run.strike) TextDecoration.LineThrough else null,
    fontSize = if (kind == LineKind.Heading) (skin.typography.menu.sizeSp * Metrics.noteHeadingScale).sp else androidx.compose.ui.unit.TextUnit.Unspecified,
)

/** One line as rendered text with the mark characters left out (View mode and the hub preview). */
internal fun renderedLine(line: ParsedLine, skin: Skin): AnnotatedString = AnnotatedString.Builder().apply {
    for (run in line.runs) if (!run.mark) {
        pushStyle(spanFor(run, line.kind, skin)); append(line.raw.substring(run.start, run.end)); pop()
    }
}.toAnnotatedString()

/** Hub preview text: rendered, with a plain symbol standing in for the block mark. */
internal fun notePreviewLine(line: ParsedLine, skin: Skin): AnnotatedString = AnnotatedString.Builder().apply {
    append(when (line.kind) {
        LineKind.Checkbox -> if (line.checked) "☑ " else "☐ "
        LineKind.Bullet -> "• "
        LineKind.Numbered -> "${line.number}. "
        else -> ""
    })
    append(renderedLine(line, skin))
}.toAnnotatedString()

/** The whole note for Edit mode: same characters, marks dimmed, styles applied. Offsets are unchanged. */
internal fun editStyledText(text: String, skin: Skin): AnnotatedString = AnnotatedString.Builder(text).apply {
    for (line in NoteMarkup.parse(text)) for (run in line.runs) {
        if (run.end > run.start) addStyle(spanFor(run, line.kind, skin), line.offset + run.start, line.offset + run.end)
    }
}.toAnnotatedString()

@Composable
internal fun NoteEditor(initial: Note, skin: Skin, store: NotesStore, changed: (Note) -> Unit, close: () -> Unit,
    saveOnOpen: Boolean = false) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    val clock = remember { SystemClock::elapsedRealtime }
    // T237: a quick jot from the hub "+" gets the same saved confirmation (and Undo) as the search Note mode.
    // T250: a note received through the share target is already new, so it gets the same confirmation on open.
    val wasNew = remember(initial.id) { saveOnOpen || !NoteSaveFeedback.hasContent(initial) }
    var feedbackShown by remember(initial.id) { mutableStateOf(false) }
    var savedNotice by remember(initial.id) { mutableStateOf<NoteSaveNotice?>(null) }
    val saver = remember(initial.id, store) {
        NoteAutosave(store.scope) { note ->
            store.saveReporting(note) { ok ->
                if (ok && wasNew && !feedbackShown) NoteSaveFeedback.forNewNote(note, clock())?.let {
                    feedbackShown = true; savedNotice = it
                }
            }
        }
    }
    LaunchedEffect(savedNotice) {
        val notice = savedNotice ?: return@LaunchedEffect
        delay((notice.expiresAt - clock()).coerceAtLeast(0))
        if (savedNotice === notice) savedNotice = null
    }
    val error by store.error.collectAsState()
    val overLimit by store.overLimit.collectAsState()
    // A new, empty note opens ready to type; an existing note opens in View mode.
    var editing by rememberSaveable(initial.id) { mutableStateOf(saveOnOpen || initial.text.isEmpty()) }
    var caret by rememberSaveable(initial.id) { mutableStateOf(initial.text.length) }
    fun change(note: Note) {
        draft = note.copy(updatedAt = System.currentTimeMillis()); changed(draft); saver.change(draft)
    }
    // T250: shared text arrives with the editor, so it is written once even if the user closes without typing.
    LaunchedEffect(initial.id) { if (saveOnOpen) change(draft) }
    fun finish() { saver.flush(); close() }
    // T183: the reminder is read from the store (the autosave never carries it), and changed only through the bell.
    val context = androidx.compose.ui.platform.LocalContext.current
    val stored by remember(store) { store.notes.catch { emit(emptyList()) } }.collectAsState(initial = emptyList())
    val remindAt = stored.firstOrNull { it.id == draft.id }?.remindAt ?: 0L
    var sheet by remember { mutableStateOf(NoteSheet.None) }
    var notice by remember { mutableStateOf<String?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, saver) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) saver.flush() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); saver.flush() }
    }
    Dialog(onDismissRequest = ::finish, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        SettingsTheme(skin) {
            ParcPage(Modifier.background(Color(skin.colors.bg.toInt())).imePadding(), scroll = false) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    NoteText(if (editing) "Editing" else "Note", skin, Modifier.weight(1f))
                    if (editing) NoteAction("View", skin, "View mode") { saver.flush(); editing = false }
                    else NoteAction("Edit", skin, "Edit mode") { caret = draft.text.length; editing = true }
                    NoteBellButton(skin) { sheet = NoteSheet.Choose }
                    NoteAction("Close", skin, action = ::finish)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(draft.pinned, { change(draft.copy(pinned = it)) }, Modifier.semantics { contentDescription = "Pin" })
                        NoteText("Pin", skin)
                    }
                    NoteAction("Delete", skin) { saver.discard(); store.delete(draft); close() }
                }
                if (remindAt > 0) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    NoteBell(Color(skin.colors.textSecondary.toInt()), Modifier.padding(end = Metrics.lineGapDp.dp))
                    NoteText(NoteReminderRules.label(remindAt, System.currentTimeMillis(), java.time.ZoneId.systemDefault(),
                        android.text.format.DateFormat.is24HourFormat(context), TimerScheduler.canScheduleExact(context)),
                        skin, Modifier.weight(1f), secondary = true)
                    NoteAction("Change", skin, "Change reminder") { sheet = NoteSheet.Remind }
                    NoteAction("Clear", skin, "Clear reminder") { saver.flush(); store.setReminder(draft.id, 0) }
                }
                notice?.let { NoteText(it, skin, secondary = true) }
                if (error) NoteAction("Retry", skin) { store.save(draft) }
                NoteText(when {
                    overLimit -> "This note was not saved: all notes together are limited to ${"%,d".format(Metrics.notesMaxChars)} characters. Your text is still here. Shorten it or delete another note."
                    error -> "Couldn’t save. Your text is still here."
                    else -> "Auto-saves"
                }, skin, secondary = true)
                NoteReminderSheets(sheet, { sheet = it }, skin, draft, remindAt, store, { saver.flush() }) { notice = it }
                NoteTitleField(draft.title, skin) { change(draft.copy(title = it)) }
                if (editing) {
                    NoteEditBody(draft.text, caret, skin, Modifier.weight(1f).fillMaxWidth()) { change(draft.copy(text = it)) }
                } else {
                    NoteViewBody(draft.text, skin, Modifier.weight(1f).fillMaxWidth(),
                        toggle = { index -> change(draft.copy(text = toggleNoteLine(draft.text, index))); saver.flush() },
                        edit = { offset -> caret = offset; editing = true })
                }
                // Last row in the column: with the keyboard open it stays directly above it for its full time.
                savedNotice?.takeIf { NoteSaveFeedback.visible(it, clock()) }?.let { saved ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        NoteText("Saved to Notes", skin, Modifier.weight(1f))
                        NoteAction("Undo", skin, "Undo saving this note") {
                            savedNotice = null; saver.discard(); store.remove(saved.note.id); close()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteTitleField(value: String, skin: Skin, onValue: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = { onValue(NoteTitles.normalize(it)) },
        modifier = Modifier.fillMaxWidth().padding(vertical = Metrics.itemGapDp.dp)
            .semantics { contentDescription = "Title" },
        textStyle = noteStyle(skin).copy(fontSize = skin.typography.menu.sizeSp.sp, fontWeight = FontWeight.SemiBold),
        singleLine = true,
        cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth()) {
                if (value.isEmpty()) NoteText("Title (optional)", skin, secondary = true)
                inner()
            }
        },
    )
}

@Composable
private fun NoteViewBody(text: String, skin: Skin, modifier: Modifier, toggle: (Int) -> Unit, edit: (Int) -> Unit) {
    val lines = remember(text) { NoteMarkup.parse(text) }
    val none = remember { MutableInteractionSource() }
    Column(modifier.verticalScroll(rememberScrollState()).clickable(none, null) { edit(text.length) }) {
        lines.forEachIndexed { index, line ->
            Row(Modifier.fillMaxWidth().clickable(none, null) { edit(line.end) }, verticalAlignment = Alignment.CenterVertically) {
                when (line.kind) {
                    LineKind.Checkbox -> Checkbox(line.checked, { toggle(index) },
                        Modifier.semantics { contentDescription = "Tick: " + NoteMarkup.stripped(line).ifEmpty { "item" } })
                    LineKind.Bullet -> BasicText("•", Modifier.padding(horizontal = Metrics.lineGapDp.dp), style = noteStyle(skin))
                    LineKind.Numbered -> BasicText("${line.number}.", Modifier.padding(horizontal = Metrics.lineGapDp.dp), style = noteStyle(skin))
                    else -> {}
                }
                val rendered = renderedLine(line, skin)
                BasicText(if (rendered.isEmpty()) AnnotatedString(" ") else rendered, Modifier.weight(1f).padding(vertical = Metrics.lineGapDp.dp),
                    style = noteStyle(skin).let { base ->
                        if (line.kind == LineKind.Plain || line.kind == LineKind.Heading) base
                        else base.copy(textDecoration = if (line.kind == LineKind.Checkbox && line.checked) TextDecoration.LineThrough else null)
                    })
            }
        }
    }
}

@Composable
private fun NoteEditBody(initialText: String, caret: Int, skin: Skin, modifier: Modifier, onText: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(initialText, TextRange(caret.coerceIn(0, initialText.length)))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    val styled = remember(field.text, skin) { editStyledText(field.text, skin) }
    val transformation = remember(styled) {
        VisualTransformation { original ->
            TransformedText(if (original.text == styled.text) styled else original, OffsetMapping.Identity)
        }
    }
    fun set(value: TextFieldValue) {
        val textChanged = value.text != field.text
        field = value
        if (textChanged) onText(value.text)
    }
    fun set(edit: NoteEdit) = set(TextFieldValue(edit.text, TextRange(edit.selStart, edit.selEnd)))
    Column(modifier) {
        // T386: the field takes the remaining height and scrolls by itself, with 8 dp kept under the
        // caret so the last line never touches the 52 dp formatting strip below it.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            BasicTextField(field, { new ->
                val enter = if (new.selection.collapsed) NoteEditing.onEnter(field.text, new.text, new.selection.start) else null
                set(if (enter != null) TextFieldValue(enter.text, TextRange(enter.selStart, enter.selEnd)) else new)
            },
                modifier = Modifier.fillMaxSize()
                    .padding(top = Metrics.itemGapDp.dp, bottom = Metrics.noteCaretGapDp.dp)
                    .focusRequester(focus)
                    .semantics { contentDescription = "Note text" },
                textStyle = noteStyle(skin), cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
                visualTransformation = transformation,
                decorationBox = { inner ->
                    if (field.text.isEmpty()) NoteText("Write a Note…", skin, secondary = true)
                    inner()
                })
        }
        // T386: one 52 dp strip. The seven 48 dp tools (416 dp with gaps and insets) are wider than
        // the phone, so the strip scrolls sideways and the last tool stays reachable.
        Row(Modifier.height(Metrics.noteToolStripDp.dp)
            .horizontalScroll(rememberScrollState())
            .width(WritingRules.noteStripContentWidthDp().dp)
            .padding(horizontal = Metrics.noteToolStripInsetDp.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.noteToolGapDp.dp)) {
            val s = field.selection.min; val e = field.selection.max
            ToolButton("☐", "Checkbox list", skin) { set(NoteEditing.toggleLine(field.text, s, e, LineKind.Checkbox)) }
            ToolButton("•", "Bullet list", skin) { set(NoteEditing.toggleLine(field.text, s, e, LineKind.Bullet)) }
            ToolButton("1.", "Numbered list", skin) { set(NoteEditing.toggleLine(field.text, s, e, LineKind.Numbered)) }
            ToolButton("H", "Heading", skin) { set(NoteEditing.toggleLine(field.text, s, e, LineKind.Heading)) }
            ToolButton("B", "Bold", skin, SpanStyle(fontWeight = FontWeight.Bold)) { set(NoteEditing.wrap(field.text, s, e, "**")) }
            ToolButton("I", "Italic", skin, SpanStyle(fontStyle = FontStyle.Italic)) { set(NoteEditing.wrap(field.text, s, e, "*")) }
            ToolButton("S", "Strikethrough", skin, SpanStyle(textDecoration = TextDecoration.LineThrough)) { set(NoteEditing.wrap(field.text, s, e, "~~")) }
        }
    }
}

@Composable
private fun ToolButton(label: String, description: String, skin: Skin, style: SpanStyle = SpanStyle(), action: () -> Unit) {
    Box(Modifier.size(Metrics.noteToolTargetDp.dp)
        .semantics { contentDescription = description }.clickable(onClick = action),
        contentAlignment = Alignment.Center) {
        BasicText(AnnotatedString(label, style), style = noteStyle(skin).copy(color = Color(skin.colors.accent.toInt())))
    }
}
