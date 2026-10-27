package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** Plain text is authoritative. Checked lines use the portable `- [x] ` spelling. */
internal data class Note(val id: String, val text: String, val pinned: Boolean = false, val updatedAt: Long = 0)
internal data class NoteLine(val text: String, val checked: Boolean? = null)
internal fun noteLines(text: String): List<NoteLine> = text.split('\n').map { raw ->
    val line = raw.removeSuffix("\r")
    when {
        line.startsWith("- [x] ", ignoreCase = true) -> NoteLine(line.drop(6), true)
        line.startsWith("- [ ] ") -> NoteLine(line.drop(6), false)
        line.startsWith("- ") -> NoteLine(line.drop(2), false)
        else -> NoteLine(line)
    }
}
internal fun toggleNoteLine(text: String, index: Int): String = text.split('\n').mapIndexed { i, raw ->
    val line = noteLines(raw).single()
    if (i != index || line.checked == null) raw else if (line.checked) "- ${line.text}" else "- [x] ${line.text}"
}.joinToString("\n")
internal fun orderedNotes(notes: List<Note>): List<Note> = notes.sortedWith(
    compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt }.thenBy { it.id })
internal object NotesJson {
    const val Key = "notes_v1"
    fun encode(notes: List<Note>): String = JsonArray(notes.map { note -> buildJsonObject {
        put("id", note.id); put("text", note.text); put("pinned", note.pinned); put("updatedAt", note.updatedAt)
    } }).toString()
    fun decode(raw: String): List<Note> {
        if (raw.isBlank()) return emptyList()
        val array = Json.parseToJsonElement(raw).jsonArray
        return array.map { value ->
            val obj = value.jsonObject
            Note(obj.getValue("id").jsonPrimitive.content, obj.getValue("text").jsonPrimitive.content,
                obj["pinned"]?.jsonPrimitive?.booleanOrNull ?: false,
                obj["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0)
        }.also { notes -> require(notes.all { it.id.isNotBlank() } && notes.map { it.id }.distinct().size == notes.size) }
    }
}

/** Only the timer is cancelled. Once queued, writes finish in order on the process-owned scope. */
internal class NoteAutosave(scope: CoroutineScope, private val save: (Note) -> Unit) {
    private val scope = scope
    private var pending: Note? = null
    private var timer: Job? = null
    private var deleted = false
    fun change(note: Note) {
        if (deleted) return
        pending = note
        timer?.cancel()
        timer = scope.launch { delay(Metrics.noteSaveDebounceMs); flush() }
    }
    fun flush() {
        timer?.cancel(); timer = null
        pending?.let { if (!deleted) save(it) }; pending = null
    }
    fun discard() { deleted = true; timer?.cancel(); pending = null }
}
internal class NoteDeleteUndo(private val clock: () -> Long) {
    private var note: Note? = null
    private var expires = 0L
    fun offer(value: Note) { note = value; expires = clock() + Metrics.noteUndoMs }
    fun take(): Note? { val value = note; note = null; return value.takeIf { clock() < expires } }
}
