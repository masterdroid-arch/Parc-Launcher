package com.brightblu.parc.core.data.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Android file access is kept out of the model, parser and scan algorithm. */
class BookFiles(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    /** The mounted volume roots, used only to offer and browse folders. Nothing is scanned here. */
    fun roots(): List<File> = app.getSystemService(StorageManager::class.java).storageVolumes
        .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
        .mapNotNull { it.directory }

    fun rootLabel(root: File): String = if (root.path.contains("/emulated/")) "Shared storage" else root.name

    /** The primary shared-storage root, where Download, Documents and Books are suggested. */
    fun storageRoot(): File? = roots().firstOrNull { it.path.contains("/emulated/") } ?: roots().firstOrNull()

    /** T225: the sensible start, unticked and only when the folder exists and is safe to scan. */
    fun suggestedFolders(): List<BookFolder> {
        val root = storageRoot() ?: return emptyList()
        return BookFolderRules.suggestedNames.mapNotNull { name ->
            val folder = File(root, name)
            val canonical = runCatching { folder.canonicalFile }.getOrNull() ?: return@mapNotNull null
            if (!canonical.isDirectory || !BookFolderRules.isSuggestable(canonical.absolutePath)) return@mapNotNull null
            BookFolder(canonical.absolutePath, folderLabel(root, canonical))
        }
    }

    /** Browser children. Android/data and obb never appear; WhatsApp stays browsable. */
    suspend fun browseFolders(parent: File): List<File> = withContext(Dispatchers.IO) {
        try {
            parent.listFiles().orEmpty()
                .filter { it.isDirectory && !BookFolderRules.isAppPrivate(it.absolutePath) }
                .sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** "Shared storage/Download/Books", with the volume's own name for an SD card. */
    fun folderLabel(root: File, folder: File): String {
        val relative = runCatching { folder.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath }
            .getOrNull().orEmpty()
        return rootLabel(root) + if (relative.isEmpty() || relative == ".") "" else "/$relative"
    }

    fun treeFolderLabel(uri: Uri): String = BookTreeScanner(app).label(uri)

    /** The image of the volume roots for the old T224 entry point; Find books now passes chosen folders. */
    fun scanFolders(): List<ScanFolder> = roots().map { ScanFolder(it.absolutePath, rootLabel(it)) }

    fun scanner(allFilesAllowedByBuild: Boolean): BookScanner = BookScanner(
        roots = ::roots,
        hasAllFilesAccess = { allFilesAllowedByBuild && Environment.isExternalStorageManager() },
        rootLabel = ::chosenRootLabel,
    )

    /** A chosen child folder reads as "Shared storage/Download", not just "Shared storage". */
    fun chosenRootLabel(root: File): String {
        val volume = roots().firstOrNull { candidate ->
            root.path == candidate.path || root.path.startsWith(candidate.path.trimEnd('/') + "/")
        } ?: return root.name
        return folderLabel(volume, root)
    }

    suspend fun canRead(location: String): Boolean = withContext(Dispatchers.IO) {
        try { open(location).use { }; true }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    /** Call only with the OpenMultipleDocuments result. Failed grants are reported to the caller. */
    suspend fun picked(uris: List<Uri>, types: Set<BookFormat>): PickedBooks = withContext(Dispatchers.IO) {
        val files = mutableListOf<FoundBook>()
        var rejected = 0
        for (uri in uris.distinct()) {
            currentCoroutineContext().ensureActive()
            val candidate = try {
                if (uri.scheme != "content") throw FileNotFoundException()
                // Openable providers need not support DocumentsContract's modified-date column.
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    fun string(name: String): String? = cursor.getColumnIndex(name).takeIf { it >= 0 }
                        ?.let { if (cursor.isNull(it)) null else cursor.getString(it) }
                    fun number(name: String): Long = cursor.getColumnIndex(name).takeIf { it >= 0 }
                        ?.let { if (cursor.isNull(it)) 0 else cursor.getLong(it).coerceAtLeast(0) } ?: 0
                    val name = string(OpenableColumns.DISPLAY_NAME) ?: return@use null
                    val format = BookFormat.fromName(name)?.takeIf { it in types } ?: return@use null
                    resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val folder = runCatching { DocumentsContract.getDocumentId(uri).substringBeforeLast('/', "") }
                        .getOrNull()?.ifBlank { null } ?: uri.authority.orEmpty()
                    FoundBook(uri.toString(), name, folder, number(OpenableColumns.SIZE),
                        number(DocumentsContract.Document.COLUMN_LAST_MODIFIED), format)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            if (candidate == null) rejected++ else files += candidate
        }
        PickedBooks(files, rejected)
    }

    fun open(location: String): InputStream {
        val uri = Uri.parse(location)
        return when (uri.scheme) {
            "content" -> resolver.openInputStream(uri) ?: throw FileNotFoundException()
            "file" -> File(requireNotNull(uri.path)).inputStream()
            null -> File(location).inputStream()
            else -> throw FileNotFoundException("Unsupported book location")
        }
    }

    fun descriptor(location: String): ParcelFileDescriptor {
        val uri = Uri.parse(location)
        return when (uri.scheme) {
            "content" -> resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException()
            "file" -> ParcelFileDescriptor.open(File(requireNotNull(uri.path)), ParcelFileDescriptor.MODE_READ_ONLY)
            null -> ParcelFileDescriptor.open(File(location), ParcelFileDescriptor.MODE_READ_ONLY)
            else -> throw FileNotFoundException("Unsupported book location")
        }
    }
}

data class PickedBooks(val files: List<FoundBook>, val rejectedCount: Int)
