package com.brightblu.parc.core.data

import com.brightblu.parc.model.locked
import com.brightblu.parc.model.LockedStorage
import com.brightblu.parc.model.ActionKind
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Layout JSON version of [ParcLayout] (the contract's LayoutJson accepts exactly this). */
internal const val LayoutVersion = 1

/** Everything the layout tables hold for one snapshot. Immutable; the unit of an atomic write. */
internal data class LayoutRows(
    val assignments: List<SlotAssignmentEntity> = emptyList(),
    val folders: List<FolderEntity> = emptyList(),
    val folderApps: List<FolderAppEntity> = emptyList(),
    val hiddenAppIds: List<String> = emptyList(),
) {
    /** True when no layout has ever been written (hidden flags alone are not a layout). */
    fun isEmpty(): Boolean = assignments.isEmpty() && folders.isEmpty()
}

/**
 * Rows in, model out, and back. Pure: no Android, no SQL, no I/O. [toLayout] keeps every supplied
 * arc, fills only its occupied indices and drops rows for arcs the caller does not know. A row at or
 * beyond the arc capacity is kept: a rotating ring (T36, A2) may hold more items than it shows, so
 * only a negative index is dropped. Unknown slot kinds and missing payloads throw rather than being
 * read as an empty slot.
 */
internal object LayoutMapper {
    const val KindApp = "app"
    const val KindFolder = "folder"
    const val KindAction = "action"
    const val KindLink = "link"

    private val tagsJson = Json { encodeDefaults = false; ignoreUnknownKeys = false }

    fun toRows(layout: ParcLayout): LayoutRows {
        val locked = layout.folders.filter { it.locked }.flatMap { it.appIds }.map { it.value.substringBefore('/') }.toSet()
        fun stored(id: String): String = if (id.substringBefore('/') in locked) LockedStorage.encode(id) else id
        return LayoutRows(
        assignments = layout.arcs.flatMap { assignment ->
            assignment.slots.map { (index, slot) ->
                assignment.arc.id.toRow(index, slot, pinned = slot is Slot.App && slot.id in layout.pins).let { row ->
                    row.copy(appId = row.appId?.let(::stored), uri = row.uri?.let { if (locked.isNotEmpty() && row.kind == "shortcut") LockedStorage.encode(it) else it })
                }
            }
        },
        folders = layout.folders.map { folder ->
            FolderEntity(
                id = folder.id.value,
                name = folder.name,
                tags = tagsJson.encodeToString(folder.tags.toList().sorted()),
            )
        },
        folderApps = layout.folders.flatMap { folder ->
            folder.appIds.mapIndexed { position, id -> FolderAppEntity(folder.id.value, stored(id.value), position) }
        },
        hiddenAppIds = layout.hidden.map { stored(it.value) }.sorted(),
    )
    }

    fun toLayout(rows: LayoutRows, specs: List<ArcSpec>): ParcLayout {
        val byArc = rows.assignments.groupBy { it.arcId }
        val appsByFolder = rows.folderApps.groupBy { it.folderId }
        return ParcLayout(
            version = LayoutVersion,
            arcs = specs.map { spec ->
                ArcAssignment(
                    arc = spec,
                    // OPUS6 (audit F5): a row this build cannot read (written by a newer build, or
                    // damaged) is left out instead of failing the whole layout, which crashed Parc
                    // at every start.
                    slots = byArc[spec.id].orEmpty()
                        .filter { it.slotIndex >= 0 }
                        .mapNotNull { row -> row.toSlotOrNull()?.let { row.slotIndex to it } }
                        .toMap(),
                )
            },
            folders = rows.folders.map { entity ->
                Folder(
                    id = FolderId(entity.id),
                    name = entity.name,
                    // Unreadable tags fail closed: the folder is treated as locked, never as open.
                    tags = runCatching { tagsJson.decodeFromString<List<String>>(entity.tags).toSet() }
                        .getOrDefault(setOf(com.brightblu.parc.model.LockedFolderTag)),
                    appIds = appsByFolder[entity.id].orEmpty()
                        .sortedBy { it.position }
                        .map { AppId(LockedStorage.decode(it.appId)) },
                )
            },
            pins = rows.assignments
                .filter { it.pinned && it.kind == KindApp }
                .mapNotNull { it.appId?.let { id -> AppId(LockedStorage.decode(id)) } }
                .toSet(),
            hidden = rows.hiddenAppIds.map { AppId(LockedStorage.decode(it)) }.toSet(),
        )
    }

    /** OPUS6 (audit F5): the slot rows this build cannot read; [toLayout] leaves them out. */
    fun unreadableRows(rows: LayoutRows): List<SlotAssignmentEntity> =
        rows.assignments.filter { it.slotIndex >= 0 && it.toSlotOrNull() == null }

    private fun SlotAssignmentEntity.toSlotOrNull(): Slot? = try {
        toSlot()
    } catch (unreadable: IllegalArgumentException) {
        null
    } catch (unreadable: kotlinx.serialization.SerializationException) {
        null
    }

    private fun SlotAssignmentEntity.toSlot(): Slot = when (kind) {
        KindApp -> Slot.App(AppId(LockedStorage.decode(requireNotNull(appId) { "app slot row without appId" })))
        KindFolder -> Slot.Folder(FolderId(requireNotNull(folderId) { "folder slot row without folderId" }))
        KindAction -> Slot.Action(ActionKind.valueOf(requireNotNull(action) { "action slot row without action" }))
        "shortcut" -> tagsJson.decodeFromString<Slot.Shortcut>(LockedStorage.decode(requireNotNull(uri)))
        KindLink -> Slot.Link(requireNotNull(uri) { "link slot row without uri" }, label ?: LockedStorage.decode(requireNotNull(uri)))
        else -> throw IllegalArgumentException("Unknown slot kind: $kind")
    }

    private fun String.toRow(index: Int, slot: Slot, pinned: Boolean): SlotAssignmentEntity = when (slot) {
        is Slot.App -> SlotAssignmentEntity(this, index, KindApp, appId = slot.id.value, pinned = pinned)
        is Slot.Folder -> SlotAssignmentEntity(this, index, KindFolder, folderId = slot.id.value)
        is Slot.Action -> SlotAssignmentEntity(this, index, KindAction, action = slot.kind.name)
        is Slot.Shortcut -> SlotAssignmentEntity(this, index, "shortcut", uri = tagsJson.encodeToString(slot))
        is Slot.Link -> SlotAssignmentEntity(this, index, KindLink, uri = slot.uri, label = slot.label)
    }
}
