package com.brightblu.parc.core.data.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * T225: walks a folder chosen through the system picker (OpenDocumentTree). The tree URI and its read
 * grant are persisted by the caller; this class only reads children the provider exposes. It never
 * follows a document URI that is not inside the chosen tree and never enters Android/data or obb.
 */
class BookTreeScanner(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val resolver = context.applicationContext.contentResolver

    private data class Node(
        val id: String,
        val name: String,
        val directory: Boolean,
        val size: Long,
        val modified: Long,
    )

    /** A root that no longer resolves, or whose grant was revoked, is reported as missing. */
    suspend fun observe(folder: ScanFolder): FolderObservation = withContext(io) {
        val uri = runCatching { Uri.parse(folder.path) }.getOrNull()
        if (uri == null) return@withContext FolderObservation(folder.path, folder.label, false, null, true)
        val rootId = treeRootId(uri)
        if (rootId == null || BookFolderRules.isAppPrivate(rootId)) {
            return@withContext FolderObservation(folder.path, folder.label, false, null, true)
        }
        try {
            val root = queryDocument(uri, rootId)
            if (root == null) {
                FolderObservation(folder.path, folder.label, false, null, true)
            } else {
                val children = runCatching { childCount(uri, rootId) }.getOrNull()
                FolderObservation(folder.path, folder.label, true,
                    children?.let { FolderMarker(root.modified, it) }, true)
            }
        } catch (_: Exception) {
            FolderObservation(folder.path, folder.label, false, null, true)
        }
    }

    suspend fun find(folder: ScanFolder, types: Set<BookFormat>): BookScanResult = withContext(io) {
        if (types.isEmpty()) return@withContext BookScanResult.Files(emptyList())
        val uri = runCatching { Uri.parse(folder.path) }.getOrNull()
            ?: return@withContext BookScanResult.Files(emptyList())
        val rootId = treeRootId(uri)
        if (rootId == null || BookFolderRules.isAppPrivate(rootId)) {
            return@withContext BookScanResult.Files(emptyList())
        }
        val found = linkedMapOf<String, FoundBook>()
        val visited = hashSetOf<String>()
        var unreadable = 0
        val pending = ArrayDeque<Pair<String, String>>()
        pending.add(rootId to "")
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (documentId, relative) = pending.removeLast()
            if (!visited.add(documentId)) continue
            val children = try { queryChildren(uri, documentId) } catch (_: Exception) { null }
            if (children == null) {
                unreadable++
                continue
            }
            for (node in children) {
                currentCoroutineContext().ensureActive()
                val childRelative = if (relative.isEmpty()) node.name else "$relative/${node.name}"
                if (node.directory) {
                    if (!isPrivate(rootId, childRelative)) pending.add(node.id to childRelative)
                } else if (BookFormat.accepts(node.name, types)) {
                    val format = BookFormat.fromName(node.name) ?: continue
                    val childUri = runCatching {
                        DocumentsContract.buildDocumentUriUsingTree(uri, node.id)
                    }.getOrNull() ?: continue
                    val label = folder.label + if (relative.isEmpty()) "" else "/$relative"
                    found[childUri.toString()] = FoundBook(
                        childUri.toString(), node.name, label, node.size, node.modified, format, folder.path,
                    )
                }
            }
        }
        BookScanResult.Files(found.values.toList(), unreadable)
    }

    /** The root document's display name, with a plain fallback when the provider gives none. */
    fun label(uri: Uri): String {
        val id = treeRootId(uri) ?: return uri.lastPathSegment ?: "Folder"
        val document = runCatching { queryDocument(uri, id) }.getOrNull()
        return document?.name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "Folder"
    }

    /** A tree rooted at "Android" must still never enter its data/obb children. */
    private fun isPrivate(rootId: String, relative: String): Boolean =
        BookFolderRules.isAppPrivate(relative) || BookFolderRules.isAppPrivate("$rootId/$relative")

    private fun treeRootId(uri: Uri): String? = try {
        if (uri.scheme != "content") null else DocumentsContract.getTreeDocumentId(uri)
    } catch (_: Exception) {
        null
    }

    private fun childUri(uri: Uri, documentId: String): Uri? = try {
        DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
    } catch (_: Exception) {
        null
    }

    private fun queryDocument(tree: Uri, documentId: String): Node? {
        val uri = try { DocumentsContract.buildDocumentUriUsingTree(tree, documentId) } catch (_: Exception) { return null }
        return resolver.query(uri, Projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.node()
        }
    }

    private fun queryChildren(tree: Uri, documentId: String): List<Node>? {
        val uri = childUri(tree, documentId) ?: return null
        return resolver.query(uri, Projection, null, null, null)?.use { cursor ->
            val rows = ArrayList<Node>(cursor.count)
            while (cursor.moveToNext()) rows += cursor.node()
            rows
        }
    }

    private fun childCount(tree: Uri, documentId: String): Int {
        val uri = childUri(tree, documentId) ?: return 0
        return resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.count } ?: 0
    }

    private fun android.database.Cursor.node(): Node {
        fun string(column: String): String? =
            getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)
        fun number(column: String): Long =
            getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getLong(it).coerceAtLeast(0) } ?: 0
        val mime = string(DocumentsContract.Document.COLUMN_MIME_TYPE)
        return Node(
            id = string(DocumentsContract.Document.COLUMN_DOCUMENT_ID).orEmpty(),
            name = string(DocumentsContract.Document.COLUMN_DISPLAY_NAME).orEmpty(),
            directory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
            size = number(DocumentsContract.Document.COLUMN_SIZE),
            modified = number(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
        )
    }

    private companion object {
        val Projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
