package com.brightblu.parc.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Stable flattened ComponentName + user serial (e.g. pkg/pkg.Activity@0). Immutable, non-null. */
@Serializable
@JvmInline
value class AppId(val value: String)

/** Stable folder key. Immutable, non-null. */
@Serializable
@JvmInline
value class FolderId(val value: String)

/** App metadata; installTime is epoch milliseconds. Immutable; all fields non-null. */
@Serializable
data class AppEntry(
    val id: AppId,
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
    val installTime: Long,
    val suspended: Boolean = false,
)

/** Slot payload. Immutable, non-null; an empty position is represented outside this type. */
@Serializable
sealed interface Slot {
    /** App component reference. Immutable, non-null. */
    @Serializable data class App(val id: AppId) : Slot
    /** Folder reference. Immutable, non-null. */
    @Serializable data class Folder(val id: FolderId) : Slot
    /** Built-in action. Immutable, non-null. */
    @Serializable data class Action(val kind: ActionKind) : Slot
    /** Platform pinned shortcut, scoped to package and user; folder destination is optional. */
    @Serializable data class Shortcut(val packageName: String, val shortcutId: String,
        val userSerial: Long, val label: String, val folderId: FolderId? = null) : Slot
    /** External shortcut/deep link URI and label. Immutable, non-null. */
    @Serializable data class Link(val uri: String, val label: String) : Slot
}

/** Built-in action identifiers. Immutable, non-null. */
@Serializable
enum class ActionKind { Settings, More, AbcList }

/**
 * Owning camera layer; folder identity is carried by ArcSpec.folderId. Immutable, non-null.
 * [AllApps] (T29) replaces the retired [Recents] layer: the hub tap now toggles Home and
 * All-apps. [Recents] is kept as a frozen value so old snapshots and the debug review screen
 * keep compiling; no navigation path builds a Recents scene anymore.
 */
@Serializable
enum class Layer { Home, Folder, Recents, AllApps }

/**
 * Arc geometry in dp/degrees (180 left, 270 up). Mirroring gives a negative sweep.
 * Immutable snapshot: callers must not mutate slotAngles. Empty angles mean evenly spaced
 * legacy geometry. folderId is null outside folders; origins default to zero for old JSON.
 */
@Serializable
data class ArcSpec(
    val id: String,
    val index: Int,
    val radiusDp: Float,
    val startAngle: Float,
    val endAngle: Float,
    val slotCapacity: Int,
    val layer: Layer,
    val folderId: FolderId? = null,
    val originXDp: Float = 0f,
    val originYDp: Float = 0f,
    val slotAngles: List<Float> = emptyList(),
) {
    fun slotAngle(index: Int): Float {
        require(index in 0 until slotCapacity)
        return if (slotAngles.isNotEmpty()) slotAngles[index]
        else if (slotCapacity == 1) (startAngle + endAngle) / 2f
        else startAngle + index * (endAngle - startAngle) / (slotCapacity - 1)
    }
}

/** Sparse slot indices preserve holes. Read-only snapshot; callers must not mutate the backing map. */
@Serializable
data class ArcAssignment(val arc: ArcSpec, val slots: Map<Int, Slot>)

/** Folder snapshot. Non-null; supplied collections must never be mutated after publication. */
@Serializable
data class Folder(
    val id: FolderId,
    val name: String,
    val tags: Set<String> = emptySet(),
    val appIds: List<AppId> = emptyList(),
)

/**
 * T98: the lock flag of a folder. It is stored as a reserved entry in [Folder.tags], so the existing
 * layout tables, the versioned layout JSON, the startup snapshot and the backup file carry it with no
 * schema change; a layout written before T98 simply has no such tag and decodes as unlocked.
 */
const val LockedFolderTag = "parc:locked"

/** True when the folder is locked (its apps are hidden until the user authenticates). */
val Folder.locked: Boolean get() = LockedFolderTag in tags

/** Copy with the lock flag set or cleared; every other tag is kept. */
fun Folder.withLocked(locked: Boolean): Folder =
    if (locked == this.locked) this else copy(tags = if (locked) tags + LockedFolderTag else tags - LockedFolderTag)

/** Copy of the layout with one folder locked or unlocked; unknown ids change nothing. */
fun ParcLayout.withFolderLocked(id: FolderId, locked: Boolean): ParcLayout =
    if (folders.none { it.id == id }) this
    else copy(folders = folders.map { if (it.id == id) it.withLocked(locked) else it })

/** Versioned layout snapshot. Non-null; supplied collections must be treated as immutable. */
@Serializable
data class ParcLayout(
    val version: Int = 1,
    val arcs: List<ArcAssignment> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val pins: Set<AppId> = emptySet(),
    val hidden: Set<AppId> = emptySet(),
)

/** Position in a sparse arc. Immutable, non-null. */
@Serializable
data class SlotPosition(val arcId: String, val index: Int)

/** Strict versioned JSON codec; thread-safe, non-null. Unsupported versions/keys throw. */
object LayoutJson {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    fun encode(layout: ParcLayout): String {
        val element = json.encodeToJsonElement(ParcLayout.serializer(), layout)
        return (if (layout.folders.any { it.locked }) LockedStorage.transform(element, true) else element).toString()
    }
    fun decode(source: String): ParcLayout =
        json.decodeFromJsonElement(ParcLayout.serializer(), LockedStorage.transform(json.parseToJsonElement(source), false)).also {
            require(it.version == 1) { "Unsupported layout version: ${it.version}" }
        }
}
