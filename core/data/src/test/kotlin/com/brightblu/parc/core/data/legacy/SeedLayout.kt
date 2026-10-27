// T89: recovered unchanged from 6241093, in test scope only.
package com.brightblu.parc.core.data.legacy

import com.brightblu.parc.core.data.LayoutVersion
import com.brightblu.parc.core.data.LayoutRepositoryImpl

import com.brightblu.parc.model.ActionKind
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The home arcs the seed places slots on. The layout engine must emit these ids (see reports/T05.md). */
internal val SeedArcIds: List<String> = listOf("A1", "A2", "A3", "A4")

/** Folder that takes apps installed after the seed. Stored unplaced; it occupies no arc while empty. */
internal val NewFolderId = FolderId("new")

/** Folder name for [NewFolderId]. */
internal const val NewFolderName = "New"

/** First-run layout document, authored as `core/data/src/main/assets/seed_layout.json`. */
@Serializable
internal data class SeedLayout(
    val version: Int = SeedLayoutJson.Version,
    val placements: List<SeedPlacement> = emptyList(),
    val folders: List<SeedFolder> = emptyList(),
)

/**
 * One seed slot. Exactly one payload field is set; [toSlot] rejects anything else.
 *
 * [pinned] is the layout's locked flag for an app slot. A learned usage slot (T34, A2[0..3]) is
 * placed unpinned so [com.brightblu.parc.core.data.usage.UsageLayerManager] may replace it later;
 * everything else in the seed is pinned.
 */
@Serializable
internal data class SeedPlacement(
    val arcId: String,
    val index: Int,
    val app: String? = null,
    val folder: String? = null,
    val action: ActionKind? = null,
    val linkUri: String? = null,
    val linkLabel: String? = null,
    val pinned: Boolean = true,
) {
    /** Slot payload for this placement. Throws when the payload is missing or ambiguous. */
    fun toSlot(): Slot {
        require(listOfNotNull(app, folder, action, linkUri).size == 1) {
            "seed placement $arcId[$index] needs exactly one payload"
        }
        return when {
            app != null -> Slot.App(AppId(app))
            folder != null -> Slot.Folder(FolderId(folder))
            action != null -> Slot.Action(action)
            else -> Slot.Link(requireNotNull(linkUri), linkLabel ?: linkUri)
        }
    }
}

/** Folder definition: its arc placement, if any, is a [SeedPlacement] naming this folder id. */
@Serializable
internal data class SeedFolder(
    val id: String,
    val name: String,
    val tags: List<String> = emptyList(),
    val apps: List<String> = emptyList(),
)

/** Strict seed codec: unknown keys or a different version throw. */
internal object SeedLayoutJson {
    /**
     * Seed document generation. v2 (T34) is the anchors/usage-layer/seven-folder layout; a stored
     * layout older than this is backed up and reseeded once (see [LayoutRepositoryImpl]).
     */
    const val Version = 2
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun decode(source: String): SeedLayout =
        json.decodeFromString(SeedLayout.serializer(), source).also {
            require(it.version == Version) { "Unsupported seed layout version: ${it.version}" }
        }
}

/**
 * Builds the first-run layout (PROJECT.md §6.3, §7). Apps that are not in [installed] are dropped
 * silently: their slot stays empty and no other slot moves. Placements naming an unknown arc, an
 * undeclared folder or a negative index are dropped for the same reason.
 *
 * An index at or beyond the arc capacity is kept (T36): a rotating ring (A2) may hold more items
 * than it shows, so only a negative index is invalid.
 * Pure; the caller supplies the arc specs of the current screen.
 */
internal fun SeedLayout.toParcLayout(specs: List<ArcSpec>, installed: Set<AppId>): ParcLayout {
    require(folders.map { it.id }.distinct().size == folders.size) { "duplicate folder id in seed" }
    val specsById = specs.associateBy { it.id }
    val declaredFolders = folders.mapTo(mutableSetOf()) { FolderId(it.id) }
    val slots = mutableMapOf<String, MutableMap<Int, Slot>>()
    val pins = mutableSetOf<AppId>()

    fun place(arcId: String, index: Int, slot: Slot) {
        if (arcId !in specsById) return
        if (index < 0) return
        slots.getOrPut(arcId) { mutableMapOf() }[index] = slot
    }

    placements.forEach { placement ->
        val slot = placement.toSlot().resolve(installed, declaredFolders) ?: return@forEach
        place(placement.arcId, placement.index, slot)
        if (slot is Slot.App && placement.pinned) pins += slot.id
    }

    return ParcLayout(
        version = LayoutVersion,
        arcs = specs.map { ArcAssignment(it, slots[it.id].orEmpty().toMap()) },
        folders = folders.map { folder ->
            Folder(
                id = FolderId(folder.id),
                name = folder.name,
                tags = folder.tags.toSet(),
                appIds = folder.apps.map(::AppId).filter { it in installed }.distinct(),
            )
        },
        pins = pins,
        hidden = emptySet(),
    )
}

/** Null when the payload refers to something that is not installed/declared. Actions are kept. */
private fun Slot.resolve(installed: Set<AppId>, folders: Set<FolderId>): Slot? = when (this) {
    is Slot.App -> if (id in installed) this else null
    is Slot.Folder -> if (id in folders) this else null
    is Slot.Action, is Slot.Link, is Slot.Shortcut -> this
}
