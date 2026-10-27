package com.brightblu.parc.feature.settings.folders

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcLayout

/**
 * The folder part of the layout as the editor sees it (T11). Immutable snapshot, no Android types.
 * [pins] and [hidden] are read-only context: the editor refuses drops onto pinned slots and keeps
 * hidden apps out of the picker (hidden apps are T17's feature).
 */
data class FolderDocument(
    val folders: List<Folder> = emptyList(),
    val pins: Set<AppId> = emptySet(),
    val hidden: Set<AppId> = emptySet(),
) {
    init {
        require(folders.map { it.id }.distinct().size == folders.size) { "Duplicate folder id" }
    }

    fun folder(id: FolderId?): Folder? = id?.let { wanted -> folders.firstOrNull { it.id == wanted } }

    /** Copy with [folder] replacing the same id, or appended when it is new. */
    fun withFolder(folder: Folder): FolderDocument =
        if (folders.any { it.id == folder.id }) copy(folders = folders.map { if (it.id == folder.id) folder else it })
        else copy(folders = folders + folder)

    companion object {
        /** Editor view of a layout snapshot. */
        fun from(layout: ParcLayout): FolderDocument =
            FolderDocument(layout.folders, layout.pins, layout.hidden)
    }
}

/** Name rules for the folder editor. Names land on one caption under a 44 dp disc (T24). */
object FolderNames {
    /** Longest folder name, in characters; the ring draws one caption line (Metrics, T05/T24). */
    const val MaxLength = Metrics.folderLabelMaxChars

    /** Rejected names, or null when [raw] can be saved. */
    fun issue(raw: String, others: List<String> = emptyList()): String? {
        val name = raw.trim()
        return when {
            name.isEmpty() -> "Name is empty"
            name.length > MaxLength -> "At most $MaxLength characters"
            others.any { it.equals(name, ignoreCase = true) } -> "That name is already used"
            else -> null
        }
    }

    fun normalise(raw: String): String = raw.trim()
}

/** Stable folder keys for names the user types. Deterministic, so tests can pin the result. */
object FolderIds {
    /** Lowercase ASCII slug, at most [slugLength] characters, suffixed until it is unique. */
    fun fromName(name: String, taken: Set<FolderId>, slugLength: Int = 16): FolderId {
        require(slugLength >= 1)
        val base = name.trim().lowercase().map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }
            .joinToString("").trim('-').take(slugLength).ifEmpty { "folder" }
        if (FolderId(base) !in taken) return FolderId(base)
        var suffix = 2
        while (FolderId("$base-$suffix") in taken) suffix++
        return FolderId("$base-$suffix")
    }
}
