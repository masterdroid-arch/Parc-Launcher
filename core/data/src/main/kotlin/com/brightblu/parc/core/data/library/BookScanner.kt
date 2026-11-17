package com.brightblu.parc.core.data.library

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class FoundBook(
    val uri: String,
    val name: String,
    val folderLabel: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val format: BookFormat,
    /** The chosen root that produced this row; empty for a system-picker result. */
    val root: String = "",
)

sealed interface BookScanResult {
    data class Files(val files: List<FoundBook>, val unreadableFolders: Int = 0) : BookScanResult
    data object PickerRequired : BookScanResult
}

/** Explicit, cancellable enumeration. Neither this class nor find() writes to LibraryStore. */
class BookScanner(
    private val roots: () -> List<File>,
    private val hasAllFilesAccess: () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val rootLabel: (File) -> String = { it.name },
) {
    suspend fun find(types: Set<BookFormat>): BookScanResult {
        // Keep the old contract: no roots() call for an empty type set or denied access.
        if (types.isEmpty()) return BookScanResult.Files(emptyList())
        if (!hasAllFilesAccess()) return BookScanResult.PickerRequired
        return find(types, roots())
    }

    /** T224: the index refreshes one chosen folder at a time instead of walking the whole phone. */
    suspend fun find(types: Set<BookFormat>, selectedRoots: List<File>): BookScanResult = withContext(io) {
        if (types.isEmpty()) return@withContext BookScanResult.Files(emptyList())
        if (!hasAllFilesAccess()) return@withContext BookScanResult.PickerRequired
        val files = linkedMapOf<String, FoundBook>()
        val visited = hashSetOf<String>()
        var unreadable = 0
        val pending = ArrayDeque<Pair<File, File>>()
        selectedRoots.forEach {
            try {
                val root = it.canonicalFile
                // T225: a chosen root above shared storage must never walk into Android/data or Android/obb.
                if (!BookFolderRules.isAppPrivate(root.absolutePath)) pending.add(root to root)
            } catch (_: Exception) { unreadable++ }
        }
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            if (!hasAllFilesAccess()) return@withContext BookScanResult.PickerRequired
            val (folder, root) = pending.removeLast()
            val canonical = try { folder.canonicalPath } catch (_: Exception) { unreadable++; continue }
            if (!visited.add(canonical)) continue
            val children = try { folder.listFiles() } catch (_: SecurityException) { null }
            if (children == null) { unreadable++; continue }
            for (file in children) {
                currentCoroutineContext().ensureActive()
                try {
                    // Never follow links out of the volume (or into loops).
                    if (file.canonicalFile != file.absoluteFile) continue
                    if (file.isDirectory) {
                        if (!BookFolderRules.isAppPrivate(file.absolutePath)) pending.add(file to root)
                    } else if (file.isFile && BookFormat.accepts(file.name, types)) {
                        val format = BookFormat.fromName(file.name) ?: continue
                        val relative = file.parentFile?.relativeTo(root)?.invariantSeparatorsPath.orEmpty()
                        val label = rootLabel(root) + if (relative.isEmpty()) "" else "/$relative"
                        files[file.absolutePath] = FoundBook(file.absolutePath, file.name, label,
                            file.length(), file.lastModified(), format, root.absolutePath)
                    }
                } catch (_: java.io.IOException) { unreadable++ }
                catch (_: SecurityException) { unreadable++ }
            }
        }
        BookScanResult.Files(files.values.toList(), unreadable)
    }

    /** The cheap marker from T224: modified time plus the folder's direct child count. */
    suspend fun marker(folder: File): FolderMarker? = withContext(io) {
        try {
            if (!folder.isDirectory) return@withContext null
            val children = folder.listFiles() ?: return@withContext null
            FolderMarker(folder.lastModified(), children.size)
        } catch (_: Exception) {
            null
        }
    }
}
