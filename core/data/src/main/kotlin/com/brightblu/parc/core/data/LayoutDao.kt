package com.brightblu.parc.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Layout tables only; the launch log has no queries yet (T13). Every method is suspend, so callers
 * decide the dispatcher. `read`-ers are ordered so a layout round-trips deterministically.
 */
@Dao
internal interface LayoutDao {
    @Query("SELECT * FROM slot_assignment ORDER BY arcId, slotIndex")
    suspend fun assignments(): List<SlotAssignmentEntity>

    @Query("DELETE FROM slot_assignment")
    suspend fun clearAssignments()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssignments(rows: List<SlotAssignmentEntity>)

    @Query("SELECT * FROM folder ORDER BY name, id")
    suspend fun folders(): List<FolderEntity>

    @Query("DELETE FROM folder")
    suspend fun clearFolders()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolders(rows: List<FolderEntity>)

    @Query("SELECT * FROM folder_app ORDER BY folderId, position")
    suspend fun folderApps(): List<FolderAppEntity>

    @Query("DELETE FROM folder_app")
    suspend fun clearFolderApps()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolderApps(rows: List<FolderAppEntity>)

    @Query("SELECT * FROM app_meta")
    suspend fun appMeta(): List<AppMetaEntity>

    @Query("UPDATE app_meta SET hidden = 0 WHERE hidden = 1")
    suspend fun clearHiddenFlags()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAppMetaIfAbsent(rows: List<AppMetaEntity>)

    @Query("UPDATE app_meta SET hidden = 1 WHERE appId IN (:ids)")
    suspend fun markHidden(ids: List<String>)
}
