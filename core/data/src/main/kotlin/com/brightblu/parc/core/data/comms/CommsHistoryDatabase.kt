package com.brightblu.parc.core.data.comms

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The dedicated message-history database (T214). It is deliberately separate from `parc.db`: a
 * failure here can only lose history, never block Home, and the file can be cleared without
 * touching the layout. It is not named in [com.brightblu.parc.core.data.backup.BackupSpecs],
 * `FullBackup`, `LayoutJson`, `BackupStores` or diagnostics.
 *
 * Version 1 is exported to `core/data/schemas/` and asserted by `CommsHistoryDatabaseTest`.
 */
@Database(
    entities = [CommsMessageEntity::class],
    version = CommsHistoryDatabaseVersion,
    exportSchema = true,
)
internal abstract class CommsHistoryDatabase : RoomDatabase() {

    abstract fun messageDao(): CommsMessageDao

    companion object {
        /** Opens or creates the history file. No destructive fallback. */
        fun create(context: Context): CommsHistoryDatabase = Room.databaseBuilder(
            context.applicationContext,
            CommsHistoryDatabase::class.java,
            CommsHistoryStore.DatabaseName,
        ).build()
    }
}

/** Schema version of [CommsHistoryDatabase]. */
internal const val CommsHistoryDatabaseVersion = 1
