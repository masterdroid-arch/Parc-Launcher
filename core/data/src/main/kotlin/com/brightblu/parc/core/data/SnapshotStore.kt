package com.brightblu.parc.core.data

import com.brightblu.parc.model.LockedStorage
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Persisted app list. Ids and labels only; the other entry fields are reconstructed cheaply. */
@Serializable
internal data class AppSnapshot(
    val version: Int = SnapshotStore.SchemaVersion,
    val entries: List<SnapshotEntry> = emptyList(),
)

@Serializable
internal data class SnapshotEntry(val id: String, val label: String)

/**
 * Synchronous JSON snapshot of the last app list, written after each change so the first ring
 * frame can paint before the index (and icons) finish loading. Reads tolerate a missing or
 * corrupt file by returning an empty list; writes are atomic (temp file + rename). Not thread-safe
 * for concurrent writes; callers serialize writes on a single dispatcher.
 */
internal class SnapshotStore(private val file: File) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun read(): List<AppEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val snapshot = json.decodeFromJsonElement(AppSnapshot.serializer(), LockedStorage.transform(json.parseToJsonElement(file.readText()), false))
            if (snapshot.version != SchemaVersion) return emptyList()
            snapshot.entries.map { it.toEntry() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun write(entries: List<AppEntry>) {
        val snapshot = AppSnapshot(entries = entries.map { SnapshotEntry(it.id.value, it.label) })
        val parent = file.parentFile ?: File(".")
        val temp = File(parent, "${file.name}.tmp")
        temp.writeText(LockedStorage.transform(json.encodeToJsonElement(AppSnapshot.serializer(), snapshot), true).toString())
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    private fun SnapshotEntry.toEntry(): AppEntry = AppEntry(
        id = AppId(id),
        label = label,
        packageName = id.substringBefore('/'),
        isSystem = false,
        installTime = 0L,
    )

    internal companion object {
        const val SchemaVersion = 1
    }
}
