package com.brightblu.parc.core.data

import com.brightblu.parc.core.data.firstrun.InstalledApp
import com.brightblu.parc.core.data.firstrun.SeedFolderRules
import com.brightblu.parc.model.ActionKind
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import com.brightblu.parc.model.SlotPosition
import java.util.Locale

/** The home arcs the first-run seed places slots on. The layout engine emits these ids. */
internal val SeedArcIds: List<String> = listOf("A1", "A2", "A3", "A4")

/** Folder that takes apps installed after the seed. Stored unplaced; it occupies no arc while empty. */
internal val NewFolderId = FolderId("new")

/** Folder name for [NewFolderId]. */
internal const val NewFolderName = "New"

/** The innermost arc owns the four apps chosen in the picker. */
internal const val MainAppsArcId: String = "A1"

/** How many apps the picker may place on the innermost arc. */
const val MainAppsSlotCount: Int = 4

/**
 * First-run layout generation. It stays 2 (T34/T36) because T62 changes what a *fresh* database
 * seeds, not the meaning of an existing stored layout. Bumping it would migrate every install and
 * replace the user's own layout, which the card forbids.
 */
internal object SeedGeneration {
    const val Current = 2
}

/**
 * Builds the generic first-run layout from the installed apps (T62, PROJECT.md §6.3).
 *
 * - [MainAppsArcId] slots 0..3 hold [mainApps] in picker order, pinned, thumb-first.
 * - A2 is left empty: the learned usage layer fills it once Parc has launch data.
 * - A4[0] is the Settings gear.
 * - Every non-empty auto folder goes on A3 slot 0..capacity-1, then A4 slot 1..capacity-1. Empty
 *   folders are omitted; folder contents are alphabetical by label.
 * - `New` exists unplaced for apps installed later.
 *
 * Missing apps are skipped without moving any other slot, so the function is safe for a stale
 * installed list. Uncategorised apps are simply not filed.
 */
internal object FirstRunSeed {

    fun build(
        installed: List<InstalledApp>,
        mainApps: List<AppId>,
        specs: List<ArcSpec>,
    ): ParcLayout {
        val byId = installed.associateBy { it.id }
        val chosen = mainApps.filter { it in byId }.distinct().take(MainAppsSlotCount)
        val chosenSet = chosen.toSet()
        val labels = installed.associate { it.id to it.label.lowercase(Locale.ROOT) }

        val folders = buildFolders(installed, chosenSet, labels)
        val folderPositions = folderPositions(specs)
        val slots = HashMap<String, MutableMap<Int, Slot>>(SeedArcIds.size)
        val pins = LinkedHashSet<AppId>()

        chosen.forEachIndexed { index, id ->
            slots.getOrPut(MainAppsArcId) { mutableMapOf() }[index] = Slot.App(id)
            pins += id
        }
        if (specs.any { it.id == "A4" }) {
            slots.getOrPut("A4") { mutableMapOf() }[0] = Slot.Action(ActionKind.Settings)
        }
        folders.forEachIndexed { index, folder ->
            val position = folderPositions.getOrNull(index) ?: return@forEachIndexed
            slots.getOrPut(position.arcId) { mutableMapOf() }[position.index] = Slot.Folder(folder.id)
        }

        return ParcLayout(
            version = LayoutVersion,
            arcs = specs.map { spec -> ArcAssignment(spec, slots[spec.id].orEmpty().toMap()) },
            folders = folders + Folder(id = NewFolderId, name = NewFolderName),
            pins = pins,
            hidden = emptySet(),
        )
    }

    /** Non-empty folders in rule order, contents alphabetical by label then id. */
    private fun buildFolders(
        installed: List<InstalledApp>,
        excluded: Set<AppId>,
        labels: Map<AppId, String>,
    ): List<Folder> {
        val members = LinkedHashMap<String, MutableList<AppId>>()
        installed.forEach { app ->
            if (app.id in excluded) return@forEach
            val rule = SeedFolderRules.folderFor(app) ?: return@forEach
            members.getOrPut(rule.id) { mutableListOf() } += app.id
        }
        return SeedFolderRules.rules.mapNotNull { rule ->
            val apps = members[rule.id] ?: return@mapNotNull null
            if (apps.isEmpty()) return@mapNotNull null
            Folder(
                id = FolderId(rule.id),
                name = rule.name,
                appIds = apps.distinct().sortedWith(
                    compareBy({ labels[it].orEmpty() }, { it.value }),
                ),
            )
        }
    }

    /** Folder slots in reach order: all of A3, then A4 from slot 1 (slot 0 is the gear). */
    private fun folderPositions(specs: List<ArcSpec>): List<SlotPosition> {
        val byId = specs.associateBy { it.id }
        val positions = ArrayList<SlotPosition>()
        byId["A3"]?.let { arc ->
            for (index in 0 until arc.slotCapacity) positions += SlotPosition("A3", index)
        }
        byId["A4"]?.let { arc ->
            for (index in 1 until arc.slotCapacity) positions += SlotPosition("A4", index)
        }
        return positions
    }
}
