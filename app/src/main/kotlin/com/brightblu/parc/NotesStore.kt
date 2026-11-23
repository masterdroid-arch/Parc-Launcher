package com.brightblu.parc

import android.content.Context
import android.os.SystemClock
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

internal val Context.notesDataStore by preferencesDataStore(name = "notes",
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler("notes"))

/** One process-owned writer. No note contents enter logs or search; only a reminder notification shows the first line. */
internal class NotesStore(
    private val data: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    val scope: CoroutineScope,
    private val alarms: NoteReminderAlarms = NoNoteReminderAlarms,
    clock: () -> Long,
) {
    private val key = stringPreferencesKey(NotesJson.Key)
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val problem = MutableStateFlow(false)
    val error = problem.asStateFlow()
    private val tooLarge = MutableStateFlow(false)
    /** True when the last save failed only because all notes together are over the size limit. */
    val overLimit = tooLarge.asStateFlow()
    private fun read(prefs: androidx.datastore.preferences.core.Preferences) =
        NotesJson.decode(prefs[key].orEmpty()).map { it.migrated() }
    val notes = data.data.map { read(it) }
    private val undo = NoteDeleteUndo(clock)
    val undoAvailable = MutableStateFlow(false)
    private var undoTimer: Job? = null
    init {
        // 1.2.0: write the one-time rewrite of old `- ` lines to disk. Reads already show migrated notes.
        commands.trySend { data.edit { prefs -> prefs[key]?.let { raw -> NotesJson.migrate(raw).let { if (it != raw) prefs[key] = it } } } }
        scope.launch {
            for (command in commands) {
                try { command(); problem.value = false; tooLarge.value = false }
                catch (e: CancellationException) { throw e }
                catch (_: NotesTooLargeException) { tooLarge.value = true; problem.value = true }
                catch (_: Exception) { problem.value = true }
            }
        }
    }
    fun save(note: Note) { commands.trySend { saveNow(note) } }
    private suspend fun saveNow(note: Note, reminder: Long? = null) {
        data.edit { prefs ->
            val notes = read(prefs)
            // The reminder is changed only by setReminder, never by a text autosave carrying a stale value.
            val kept = note.copy(remindAt = reminder ?: notes.firstOrNull { it.id == note.id }?.remindAt ?: 0)
            val encoded = NotesJson.encode(notes.filterNot { it.id == note.id } + kept)
            if (encoded.length > com.brightblu.parc.core.data.backup.BackupSpecs.notes.keys.single().maxLength) throw NotesTooLargeException()
            prefs[key] = encoded
        }
    }
    /** Create the note and its reminder in the same edit before arming the alarm. */
    fun saveWithReminder(note: Note, at: Long, onResult: (Boolean) -> Unit) { commands.trySend {
        try {
            saveNow(note, at)
            alarms.schedule(note.id, at)
            onResult(true)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { onResult(false); throw e }
    } }

    /** T195: like [save], and tells the caller (on the store's scope) whether the write went through. */
    fun saveReporting(note: Note, onResult: (Boolean) -> Unit) { commands.trySend {
        try { saveNow(note); onResult(true) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { onResult(false); throw e }
    } }
    /** T195: removes a note at once with no undo of its own (the search card keeps its own Undo). Cancels its alarm. */
    fun remove(noteId: String) { commands.trySend {
        data.edit { prefs -> prefs[key] = NotesJson.encode(read(prefs).filterNot { it.id == noteId }) }
        alarms.cancel(noteId)
    } }
    /**
     * T195: appends [addition] as a new line to the most recently edited note (see [NoteSearchRules.lastEdited]).
     * Reports the note as it was and as it is now; null/null/false when there is no note to add to, null/null/true when the write failed.
     */
    fun appendToLastEdited(addition: String, now: Long, onResult: (before: Note?, after: Note?, failed: Boolean) -> Unit) { commands.trySend {
        var before: Note? = null
        var after: Note? = null
        try {
            data.edit { prefs ->
                val notes = read(prefs)
                val target = NoteSearchRules.lastEdited(notes)
                if (target != null) {
                    val changed = target.copy(text = NoteSearchRules.appended(target.text, addition), updatedAt = now)
                    val encoded = NotesJson.encode(notes.map { if (it.id == target.id) changed else it })
                    if (encoded.length > com.brightblu.parc.core.data.backup.BackupSpecs.notes.keys.single().maxLength) throw NotesTooLargeException()
                    prefs[key] = encoded
                    before = target; after = changed
                }
            }
            onResult(before, after, false)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { onResult(null, null, true); throw e }
    } }
    /** Sets or clears (0) the reminder of a stored note and arms or cancels its alarm. */
    fun setReminder(noteId: String, at: Long) { commands.trySend {
        var changed = false
        data.edit { prefs ->
            val notes = read(prefs)
            if (notes.any { it.id == noteId }) {
                prefs[key] = NotesJson.encode(notes.map { if (it.id == noteId) it.copy(remindAt = at.coerceAtLeast(0)) else it })
                changed = true
            }
        }
        if (changed) { if (at > 0) alarms.schedule(noteId, at) else alarms.cancel(noteId) }
    } }
    /** The alarm went off: returns the note and clears its reminder, or null when nothing is due for it. */
    suspend fun fireReminder(noteId: String, now: Long): Note? {
        var due: Note? = null
        data.edit { prefs ->
            val notes = read(prefs)
            val note = notes.firstOrNull { it.id == noteId }
            if (note != null && NoteReminderRules.isDue(note.remindAt, now)) {
                due = note
                prefs[key] = NotesJson.encode(notes.map { if (it.id == noteId) it.copy(remindAt = 0) else it })
            }
        }
        return due
    }
    /** Arms every stored future reminder again and returns the notes whose reminder time has already passed. */
    suspend fun rearmReminders(now: Long): List<Note> {
        val all = data.data.first().let { read(it) }.filter { it.remindAt > 0 }
        all.filter { it.remindAt > now }.forEach { alarms.schedule(it.id, it.remindAt) }
        return all.filter { it.remindAt <= now }
    }
    fun delete(note: Note) { commands.trySend {
        var stored = note
        data.edit { prefs ->
            val notes = read(prefs)
            stored = note.copy(remindAt = notes.firstOrNull { it.id == note.id }?.remindAt ?: 0)
            prefs[key] = NotesJson.encode(notes.filterNot { it.id == note.id })
        }
        alarms.cancel(note.id)
        undo.offer(stored); undoAvailable.value = true
        undoTimer?.cancel()
        undoTimer = scope.launch { delay(com.brightblu.parc.model.Metrics.noteUndoMs); undoAvailable.value = false }
    } }
    fun undoDelete() { commands.trySend {
        undo.take()?.let { restored ->
            data.edit { prefs -> prefs[key] = NotesJson.encode(read(prefs).filterNot { it.id == restored.id } + restored) }
            if (restored.remindAt > 0) alarms.schedule(restored.id, restored.remindAt)
        }
        undoAvailable.value = false; undoTimer?.cancel()
    } }
    companion object {
        @Volatile private var instance: NotesStore? = null
        fun get(context: Context): NotesStore = instance ?: synchronized(this) {
            instance ?: NotesStore(context.applicationContext.notesDataStore,
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), AndroidNoteAlarms(context.applicationContext),
                SystemClock::elapsedRealtime).also { instance = it }
        }
    }
}
