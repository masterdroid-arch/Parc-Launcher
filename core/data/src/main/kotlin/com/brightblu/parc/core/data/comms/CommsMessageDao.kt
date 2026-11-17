package com.brightblu.parc.core.data.comms

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The only SQL over `comms_messages` (T214). Writes are plain calls for the listener's
 * `parc-notifications` worker; reads are a Room [Flow]. Deletes are suspend and are called from
 * the repository's IO context, never the main thread.
 *
 * `insert` ignores a duplicate identity so a repost can never create a second row. The store then
 * refreshes the live key through [markLive]; it is the only caller of these methods.
 */
@Dao
interface CommsMessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(message: CommsMessageEntity): Long

    @Query("SELECT * FROM comms_messages WHERE identity = :identity LIMIT 1")
    fun findByIdentity(identity: String): CommsMessageEntity?

    @Query(
        "UPDATE comms_messages SET notification_key = :notificationKey, " +
            "conversation_title = :conversationTitle, live = 1, removed_at_ms = NULL " +
            "WHERE identity = :identity",
    )
    fun markLive(identity: String, notificationKey: String, conversationTitle: String): Int

    @Query(
        "UPDATE comms_messages SET live = 0, removed_at_ms = :removedAtMs, notification_key = '' " +
            "WHERE notification_key = :notificationKey AND live = 1",
    )
    fun markRemoved(notificationKey: String, removedAtMs: Long): Int

    @Query("UPDATE comms_messages SET live = 0, removed_at_ms = :removedAtMs, notification_key = '' WHERE live = 1")
    fun markAllNotLive(removedAtMs: Long): Int

    @Query("DELETE FROM comms_messages")
    suspend fun clear()

    @Query("DELETE FROM comms_messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM comms_messages WHERE app = :app AND conversation_key = :conversationKey")
    suspend fun deleteConversation(app: String, conversationKey: String)

    @Query("SELECT * FROM comms_messages ORDER BY time_ms DESC, id DESC")
    fun observeAll(): Flow<List<CommsMessageEntity>>
}
