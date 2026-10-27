package com.brightblu.parc.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One occupied home-arc position (PROJECT.md §6.3 `slot_assignment`). An empty position has no row,
 * which is how sparse slot maps survive a restart. `kind` names the payload column that is set.
 * `pinned` carries [com.brightblu.parc.model.ParcLayout.pins], which the frozen model keeps apart
 * from the slot payloads.
 */
@Entity(tableName = "slot_assignment", primaryKeys = ["arcId", "slotIndex"])
internal data class SlotAssignmentEntity(
    val arcId: String,
    val slotIndex: Int,
    val kind: String,
    val appId: String? = null,
    val folderId: String? = null,
    val action: String? = null,
    val uri: String? = null,
    val label: String? = null,
    val pinned: Boolean = false,
)

/** Folder metadata. `tags` is a JSON array string; §6.3 declares no tag table. */
@Entity(tableName = "folder")
internal data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val tags: String,
)

/** Ordered folder membership; `position` is the stored (stable) order inside the folder. */
@Entity(tableName = "folder_app", primaryKeys = ["folderId", "appId"])
internal data class FolderAppEntity(
    val folderId: String,
    val appId: String,
    val position: Int,
)

/**
 * Per-app metadata. Only `hidden` is read and written today; `labelOverride` exists in v1 so later
 * tasks (F-17) need no migration. Rows outlive a hidden flag change, so overrides are never lost.
 */
@Entity(tableName = "app_meta")
internal data class AppMetaEntity(
    @PrimaryKey val appId: String,
    val hidden: Boolean = false,
    val labelOverride: String? = null,
)

/** Parc's own launch log (§6.3). Written and read by T13; created here so v1 needs no migration. */
@Entity(tableName = "launch_log", indices = [Index("ts")])
internal data class LaunchLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val appId: String,
    val ts: Long,
)
