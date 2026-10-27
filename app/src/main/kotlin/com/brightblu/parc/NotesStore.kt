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

/** One process-owned writer. No note contents enter logs, search or notification services. */
internal class NotesStore(
    private val data: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    val scope: CoroutineScope,
    clock: () -> Long,
) {
    private val key = stringPreferencesKey(NotesJson.Key)
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val problem = MutableStateFlow(false)
    val error = problem.asStateFlow()
    val notes = data.data.map { NotesJson.decode(it[key].orEmpty()) }
    private val undo = NoteDeleteUndo(clock)
    val undoAvailable = MutableStateFlow(false)
    private var undoTimer: Job? = null
    init {
        scope.launch {
            for (command in commands) {
                try { command(); problem.value = false }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { problem.value = true }
            }
        }
    }
    fun save(note: Note) { commands.trySend {
        data.edit { prefs ->
            val notes = NotesJson.decode(prefs[key].orEmpty())
            val encoded = NotesJson.encode(notes.filterNot { it.id == note.id } + note)
            require(encoded.length <= com.brightblu.parc.core.data.backup.BackupSpecs.notes.keys.single().maxLength)
            prefs[key] = encoded
        }
    } }
    fun delete(note: Note) { commands.trySend {
        data.edit { prefs -> prefs[key] = NotesJson.encode(NotesJson.decode(prefs[key].orEmpty()).filterNot { it.id == note.id }) }
        undo.offer(note); undoAvailable.value = true
        undoTimer?.cancel()
        undoTimer = scope.launch { delay(com.brightblu.parc.model.Metrics.noteUndoMs); undoAvailable.value = false }
    } }
    fun undoDelete() { commands.trySend {
        undo.take()?.let { restored ->
            data.edit { prefs -> prefs[key] = NotesJson.encode(NotesJson.decode(prefs[key].orEmpty()).filterNot { it.id == restored.id } + restored) }
        }
        undoAvailable.value = false; undoTimer?.cancel()
    } }
    companion object {
        @Volatile private var instance: NotesStore? = null
        fun get(context: Context): NotesStore = instance ?: synchronized(this) {
            instance ?: NotesStore(context.applicationContext.notesDataStore,
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), SystemClock::elapsedRealtime).also { instance = it }
        }
    }
}
