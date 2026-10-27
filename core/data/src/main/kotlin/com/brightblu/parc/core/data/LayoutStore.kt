package com.brightblu.parc.core.data

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Rows-only boundary for the layout tables. Callers above it work with the frozen model only. */
internal interface LayoutStore {
    /** All layout rows; [LayoutRows.isEmpty] means no layout has ever been written. */
    suspend fun read(): LayoutRows

    /** Replaces the layout tables in one transaction. The launch log is untouched. */
    suspend fun write(rows: LayoutRows)
}

/** Runs [block] atomically. The production implementation is one Room transaction. */
internal interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

/** Room-backed store. Reads are ordered and writes clear-then-insert the four layout tables. */
internal class RoomLayoutStore(
    private val dao: LayoutDao,
    private val transactions: TransactionRunner,
    private val dispatcher: CoroutineDispatcher,
) : LayoutStore {
    override suspend fun read(): LayoutRows = withContext(dispatcher) {
        LayoutRows(
            assignments = dao.assignments(),
            folders = dao.folders(),
            folderApps = dao.folderApps(),
            hiddenAppIds = dao.appMeta().filter { it.hidden }.map { it.appId },
        )
    }

    override suspend fun write(rows: LayoutRows) {
        withContext(dispatcher) {
            transactions.run {
                dao.clearAssignments()
                dao.clearFolders()
                dao.clearFolderApps()
                dao.clearHiddenFlags()
                if (rows.assignments.isNotEmpty()) dao.insertAssignments(rows.assignments)
                if (rows.folders.isNotEmpty()) dao.insertFolders(rows.folders)
                if (rows.folderApps.isNotEmpty()) dao.insertFolderApps(rows.folderApps)
                // Hidden rows only ever flip the flag, so a stored label override survives.
                if (rows.hiddenAppIds.isNotEmpty()) {
                    dao.insertAppMetaIfAbsent(rows.hiddenAppIds.map { AppMetaEntity(appId = it) })
                    dao.markHidden(rows.hiddenAppIds)
                }
            }
        }
    }
}

/** Room transaction boundary. */
internal class RoomTransactionRunner(private val database: RoomDatabase) : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = database.withTransaction { block() }
}
