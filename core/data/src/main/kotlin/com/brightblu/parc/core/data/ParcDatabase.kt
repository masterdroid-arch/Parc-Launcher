package com.brightblu.parc.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.brightblu.parc.core.data.usage.UsageDao

/** Schema version of [ParcDatabase]. Version 1 is the whole schema; there is no migration yet. */
internal const val ParcDatabaseVersion = 1

/**
 * The layout and usage database (PROJECT.md §6.3). Version 1, schema exported to
 * `core/data/schemas/` and asserted by `LayoutSchemaTest` before any later version is added.
 * Room's generated code touches Android SQLite; everything above [LayoutDao] is pure.
 */
@Database(
    entities = [
        SlotAssignmentEntity::class,
        FolderEntity::class,
        FolderAppEntity::class,
        AppMetaEntity::class,
        LaunchLogEntity::class,
    ],
    version = ParcDatabaseVersion,
    exportSchema = true,
)
internal abstract class ParcDatabase : RoomDatabase() {
    abstract fun layoutDao(): LayoutDao

    /** Launch-log queries (T13). Same v1 schema: the table already existed, only queries are new. */
    abstract fun usageDao(): UsageDao

    companion object {
        const val DbName = "parc.db"

        @Volatile
        private var instance: ParcDatabase? = null

        /**
         * Opens (or creates) the database. No destructive fallback: a failed open must never wipe a
         * layout. One instance per process, because the layout repository and the usage repository
         * both need it and two Room instances on one file would be two connection pools on the same
         * SQLite database.
         */
        fun create(context: Context): ParcDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ParcDatabase::class.java,
                    DbName,
                ).build().also { instance = it }
            }
    }
}
