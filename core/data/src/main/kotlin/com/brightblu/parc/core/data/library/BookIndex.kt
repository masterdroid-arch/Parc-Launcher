package com.brightblu.parc.core.data.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

/**
 * T224: the phone-only Find books index. It remembers what an explicit scan found so opening Find
 * books does not walk shared storage again. It is deliberately not part of the backup.
 */
@Serializable
data class IndexedBook(
    val location: String,
    val name: String,
    val folder: String,
    val format: BookFormat,
    val sizeBytes: Long,
    val modifiedAt: Long,
    /** The chosen root this row came from. Empty for a file picked through the system picker. */
    val root: String = "",
) {
    init {
        require(location.isNotBlank() && name.isNotBlank())
        require(sizeBytes >= 0 && modifiedAt >= 0)
    }

    fun found(): FoundBook = FoundBook(location, name, folder, sizeBytes, modifiedAt, format, root)

    companion object {
        fun from(found: FoundBook): IndexedBook = IndexedBook(found.uri, found.name, found.folderLabel,
            found.format, found.sizeBytes, found.modifiedAt, found.root)
    }
}

@Serializable
data class IndexedFolder(
    val path: String,
    val label: String,
    val lastScanAt: Long,
    val modifiedAt: Long,
    val fileCount: Int,
) {
    init {
        require(path.isNotBlank() && label.isNotBlank())
        require(lastScanAt >= 0 && modifiedAt >= 0 && fileCount >= 0)
    }
}

@Serializable
data class BookIndex(
    val files: List<IndexedBook> = emptyList(),
    val folders: List<IndexedFolder> = emptyList(),
) {
    fun foundBooks(): List<FoundBook> = files.map(IndexedBook::found)

    fun folder(path: String): IndexedFolder? = folders.firstOrNull { it.path == path }
}

/** The cheap marker: the chosen folder's modified time and its direct child count. */
data class FolderMarker(val modifiedAt: Long, val fileCount: Int)

data class FolderObservation(
    val path: String,
    val label: String,
    val exists: Boolean,
    val marker: FolderMarker?,
    val tree: Boolean = false,
) {
    fun scanFolder(): ScanFolder = ScanFolder(path, label, tree)
}

/** One chosen folder for a refresh. T225 supplies the user's ticks in place of the volume roots. */
data class ScanFolder(val path: String, val label: String, val tree: Boolean = false)

object BookIndexJson {
    /** About 20,000 rows; far above a large real library of 653 files and small enough for one preference value. */
    const val MaxChars = 4_000_000
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(index: BookIndex): String = json.encodeToString(
        JsonObject(
            mapOf(
                "files" to JsonArray(index.files.map { json.encodeToJsonElement(IndexedBook.serializer(), it) }),
                "folders" to JsonArray(index.folders.map { json.encodeToJsonElement(IndexedFolder.serializer(), it) }),
            ),
        ),
    ).also { require(it.length <= MaxChars) { "The book index is too large" } }

    /** Empty, old and corrupt documents read as an empty index; one malformed row cannot lose the rest. */
    fun decode(text: String?): BookIndex {
        if (text.isNullOrBlank() || text.length > MaxChars) return BookIndex()
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return BookIndex()
        val fileRows = when (root) {
            is JsonArray -> root
            is JsonObject -> runCatching { root["files"]?.jsonArray }.getOrNull()
            else -> null
        }
        val folderRows = (root as? JsonObject)?.let { runCatching { it["folders"]?.jsonArray }.getOrNull() }
        val files = fileRows.orEmpty().mapNotNull {
            runCatching { json.decodeFromJsonElement(IndexedBook.serializer(), it) }.getOrNull()
        }.distinctBy { it.location }
        val folders = folderRows.orEmpty().mapNotNull {
            runCatching { json.decodeFromJsonElement(IndexedFolder.serializer(), it) }.getOrNull()
        }.distinctBy { it.path }
        return BookIndex(files, folders)
    }
}

/** Pure refresh rules, kept free of Android so the incremental cases can be unit tested. */
object BookIndexRules {
    fun markerChanged(folder: IndexedFolder?, marker: FolderMarker?): Boolean =
        folder == null || marker == null || folder.modifiedAt != marker.modifiedAt ||
            folder.fileCount != marker.fileCount

    /** Only folders whose marker changed are walked; a forced refresh walks every chosen folder. */
    fun targets(index: BookIndex, observations: List<FolderObservation>, force: Boolean): List<FolderObservation> =
        observations.filter { observed ->
            observed.exists && (force || markerChanged(index.folder(observed.path), observed.marker))
        }

    /** Replace one root's rows and marker. Rows from every other root, and picked files, stay. */
    fun merge(index: BookIndex, observed: FolderObservation, found: List<FoundBook>, now: Long): BookIndex {
        val old = index.folder(observed.path)
        val marker = observed.marker
        val folder = IndexedFolder(
            observed.path,
            observed.label,
            now,
            marker?.modifiedAt ?: old?.modifiedAt ?: 0,
            marker?.fileCount ?: old?.fileCount ?: 0,
        )
        val kept = index.files.filterNot { it.root == observed.path }
        val added = found.asSequence()
            .filter { it.format in BookFormat.entries }
            .map { IndexedBook.from(it.copy(root = observed.path)) }
            .distinctBy { it.location }
            .toList()
        return BookIndex(
            (kept + added).distinctBy { it.location },
            index.folders.filterNot { it.path == observed.path } + folder,
        )
    }

    /** A chosen folder that is gone takes its rows with it. Missing roots are never rescanned. */
    fun removeFolder(index: BookIndex, path: String): BookIndex = BookIndex(
        index.files.filterNot { it.root == path },
        index.folders.filterNot { it.path == path },
    )

    /** T225: Find books shows only rows backed by a folder the user chose now; picked files stay. */
    fun retainFolders(index: BookIndex, locations: Set<String>): BookIndex = index.copy(
        files = index.files.filter { it.root.isBlank() || it.root in locations },
        folders = index.folders.filter { it.path in locations },
    )

    fun applyObservations(index: BookIndex, observations: List<FolderObservation>): BookIndex =
        observations.filterNot { it.exists }.fold(index) { current, gone -> removeFolder(current, gone.path) }

    /** T224: a picked file whose grant or file is gone leaves the index; nothing else is touched. */
    fun removeFiles(index: BookIndex, locations: Set<String>): BookIndex =
        if (locations.isEmpty()) index else index.copy(files = index.files.filterNot { it.location in locations })

    /** A full explicit scan (permission flow or Scan again) replaces every chosen root at once. */
    fun mergeFull(index: BookIndex, observations: List<FolderObservation>, found: List<FoundBook>, now: Long): BookIndex {
        var result = applyObservations(index, observations)
        for (observed in observations.filter { it.exists }) {
            val rows = found.filter { it.root == observed.path }
            result = merge(result, observed, rows, now)
        }
        return result
    }

    /** Files chosen through the system picker have no folder marker, so they are added as they are. */
    fun addPicked(index: BookIndex, found: List<FoundBook>): BookIndex {
        val added = found.asSequence()
            .map { it.copy(root = "") }
            .map(IndexedBook::from)
            .distinctBy { it.location }
            .toList()
        if (added.isEmpty()) return index
        val locations = added.mapTo(hashSetOf()) { it.location }
        return index.copy(files = (index.files.filterNot { it.location in locations } + added)
            .distinctBy { it.location })
    }
}
