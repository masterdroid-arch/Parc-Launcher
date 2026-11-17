package com.brightblu.parc.core.data.library

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal val Context.parcBookIndexStore by preferencesDataStore(
    name = "parc_book_index",
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler("parc_book_index"),
)

/**
 * T224: a persistent, phone-only Find books index. It is not registered in BackupSpecs, holds no
 * cover or reading position, and never changes LibraryStore.
 */
class BookIndexStore(
    private val store: DataStore<Preferences>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(context.applicationContext.parcBookIndexStore)

    val index: Flow<BookIndex> = store.data.map { BookIndexJson.decode(it[Key]) }.flowOn(io)

    suspend fun snapshot(): BookIndex = withContext(io) {
        BookIndexJson.decode(store.data.first()[Key])
    }

    /**
     * Walk only the chosen folders whose marker changed, unless [force] is set. Missing folders lose
     * their rows; every other root and every picked file is kept. No scan result ever touches the library.
     */
    suspend fun refresh(
        scanner: BookScanner,
        folders: List<ScanFolder>,
        types: Set<BookFormat>,
        force: Boolean,
    ): BookIndex = refresh(scanner, null, folders, types, force)

    /** T225: file roots use the plain directory walk; content-tree roots use the system folder grant. */
    suspend fun refresh(
        scanner: BookScanner,
        treeScanner: BookTreeScanner?,
        folders: List<ScanFolder>,
        types: Set<BookFormat>,
        force: Boolean,
    ): BookIndex = withContext(io) {
        val current = snapshot()
        val observations = folders.map { folder -> observe(folder, scanner, treeScanner) }
        var refreshed = BookIndexRules.applyObservations(current, observations)
        for (observed in BookIndexRules.targets(refreshed, observations, force)) {
            if (observed.tree) {
                val tree = treeScanner ?: continue
                val result = tree.find(observed.scanFolder(), types)
                refreshed = BookIndexRules.merge(refreshed, observed, filesOf(result), clock())
            } else {
                when (val result = scanner.find(types, listOf(File(observed.path)))) {
                    is BookScanResult.Files -> refreshed = BookIndexRules.merge(refreshed, observed, result.files, clock())
                    BookScanResult.PickerRequired -> return@withContext current
                }
            }
        }
        if (refreshed != current) write(refreshed)
        refreshed
    }

    private suspend fun observe(
        folder: ScanFolder,
        scanner: BookScanner,
        treeScanner: BookTreeScanner?,
    ): FolderObservation {
        if (folder.tree) {
            return treeScanner?.observe(folder)
                ?: FolderObservation(folder.path, folder.label, false, null, true)
        }
        val file = File(folder.path)
        return FolderObservation(folder.path, folder.label, file.exists(), scanner.marker(file))
    }

    private fun filesOf(result: BookScanResult): List<FoundBook> = when (result) {
        is BookScanResult.Files -> result.files
        BookScanResult.PickerRequired -> emptyList()
    }

    /** A full explicit scan, not an incremental refresh; only the markers are read again here. */
    suspend fun mergeFull(
        scanner: BookScanner,
        folders: List<ScanFolder>,
        found: List<FoundBook>,
    ): BookIndex = mergeFull(scanner, null, folders, found)

    suspend fun mergeFull(
        scanner: BookScanner,
        treeScanner: BookTreeScanner?,
        folders: List<ScanFolder>,
        found: List<FoundBook>,
    ): BookIndex = withContext(io) {
        val current = snapshot()
        val observations = folders.map { folder -> observe(folder, scanner, treeScanner) }
        val refreshed = BookIndexRules.mergeFull(current, observations, found, clock())
        if (refreshed != current) write(refreshed)
        refreshed
    }

    /**
     * Picker rows have no folder marker, so their small number is checked directly. A row whose grant
     * or file is gone is dropped; a row backed by a chosen root is left to the marker refresh.
     */
    suspend fun prunePicked(canRead: suspend (String) -> Boolean): BookIndex = withContext(io) {
        val current = snapshot()
        val picked = current.files.filter { it.root.isBlank() }
        if (picked.isEmpty()) return@withContext current
        val missing = picked.filterNot { file ->
            try { canRead(file.location) } catch (_: Exception) { false }
        }.mapTo(hashSetOf()) { it.location }
        val refreshed = BookIndexRules.removeFiles(current, missing)
        if (refreshed != current) write(refreshed)
        refreshed
    }

    suspend fun addPicked(found: List<FoundBook>): BookIndex = withContext(io) {
        val current = snapshot()
        val refreshed = BookIndexRules.addPicked(current, found)
        if (refreshed != current) write(refreshed)
        refreshed
    }

    /** T252: the files Parc has already looked at for auto-add. Null before the first pass. Phone-only. */
    suspend fun autoSeen(): Set<String>? = withContext(io) {
        val raw = store.data.first()[SeenKey] ?: return@withContext null
        raw.split('\n').filter { it.isNotBlank() }.toSet()
    }

    suspend fun saveAutoSeen(seen: Set<String>) = withContext(io) {
        store.edit { it[SeenKey] = seen.joinToString("\n") }
    }

    suspend fun save(index: BookIndex) = withContext(io) { write(index) }

    private suspend fun write(index: BookIndex) {
        val text = BookIndexJson.encode(index)
        store.edit { it[Key] = text }
    }

    companion object {
        const val KeyName = "index_v1"
        private val Key = stringPreferencesKey(KeyName)
        private val SeenKey = stringPreferencesKey("auto_seen_v1")
    }
}
