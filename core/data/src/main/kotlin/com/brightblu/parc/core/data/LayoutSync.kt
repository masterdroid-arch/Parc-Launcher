package com.brightblu.parc.core.data

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot

/**
 * Reconciles a layout with what is installed (PROJECT.md §6.3, §7). Pure and deterministic:
 *
 * - a slot whose app is gone stays empty and keeps its index, so no other slot ever moves;
 * - pins and folder lists lose apps that are gone;
 * - installed user apps that appear in no pin, folder or hidden set are prepended to the New folder,
 *   newest install first, so a day-one install shows up without a relayout;
 * - hidden apps and system apps never enter New.
 */
internal object LayoutSync {

    fun reconcile(
        layout: ParcLayout,
        installed: List<AppEntry>,
        newFolderId: FolderId,
        newFolderName: String,
        newSince: Long? = null,
    ): ParcLayout {
        val installedIds = installed.mapTo(mutableSetOf()) { it.id }
        val known = mutableSetOf<AppId>()
        known += layout.pins
        known += layout.hidden
        layout.folders.forEach { known += it.appIds }

        val unseen = installed.asSequence()
            .filter { !it.isSystem && it.id !in known }
            // T62: apps present at the first seed are the baseline. An uncategorised baseline app
            // stays unfiled; only an install made after Parc was set up enters New.
            .filter { newSince == null || it.installTime >= newSince }
            .sortedWith(compareByDescending<AppEntry> { it.installTime }.thenBy { it.id.value })
            .map { it.id }
            .distinct()
            .toList()

        val folders = layout.folders
            .map { folder -> folder.copy(appIds = folder.appIds.filter { it in installedIds }) }
            .let { folders -> if (unseen.isEmpty()) folders else folders.withNewApps(newFolderId, newFolderName, unseen) }

        return layout.copy(
            arcs = layout.arcs.map { assignment ->
                assignment.copy(slots = assignment.slots.filterNot { (_, slot) ->
                    slot is Slot.App && slot.id !in installedIds
                })
            },
            folders = folders,
            pins = layout.pins.filterTo(mutableSetOf()) { it in installedIds },
        )
    }

    private fun List<Folder>.withNewApps(
        newFolderId: FolderId,
        newFolderName: String,
        unseen: List<AppId>,
    ): List<Folder> {
        val existing = firstOrNull { it.id == newFolderId }
        if (existing == null) {
            return this + Folder(id = newFolderId, name = newFolderName, appIds = unseen)
        }
        return map { folder ->
            if (folder.id == newFolderId) {
                folder.copy(appIds = (unseen + folder.appIds).distinct())
            } else {
                folder
            }
        }
    }
}
