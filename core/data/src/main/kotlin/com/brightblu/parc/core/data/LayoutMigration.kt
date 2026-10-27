package com.brightblu.parc.core.data

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Which seed generation the stored layout was built from (T34).
 *
 * The layout tables carry no generation of their own (the contract's `ParcLayout.version` is the
 * frozen JSON schema and stays 1), so the marker lives in DataStore. `null` means "no marker was
 * ever written", which is what a build older than T34 leaves behind: such a layout is migrated.
 */
internal interface LayoutGenerationStore {
    /** Applied generation, or null when there is no marker. */
    suspend fun applied(): Int?

    /** Records [generation] as applied. */
    suspend fun setApplied(generation: Int)
}

/**
 * Preserves the layout a seed migration replaces (T34). The point is that the old slots can be
 * brought back by hand from `filesDir/backups/` if the new layout is wrong.
 */
internal interface LayoutBackupStore {
    /**
     * Writes [document] for the layout of [generation] and returns the file name used. Throws when
     * it cannot write, which the caller treats as "do not migrate yet".
     */
    suspend fun write(generation: Int, document: String): String
}

/** DataStore-backed [LayoutGenerationStore]. */
internal class PrefsLayoutGenerationStore(private val prefs: ParcPrefs) : LayoutGenerationStore {
    override suspend fun applied(): Int? = prefs.layoutGeneration.first()

    override suspend fun setApplied(generation: Int) = prefs.setLayoutGeneration(generation)
}

/**
 * File-backed [LayoutBackupStore] over `filesDir/backups` (T34). One small JSON file per migration,
 * named by the generation being replaced and the local date, so a second migration later in the
 * month cannot overwrite the first. Writes run on [dispatcher]; the file is complete before the
 * caller reseeds, because a half-written backup is worse than none.
 */
internal class FileLayoutBackupStore(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zoneOffsetMillis: (Long) -> Int = { TimeZone.getDefault().getOffset(it) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LayoutBackupStore {

    override suspend fun write(generation: Int, document: String): String = withContext(dispatcher) {
        val now = clock()
        val name = layoutBackupFileName(generation, now, zoneOffsetMillis(now))
        directory.mkdirs()
        val file = File(directory, name)
        val temp = File(directory, "$name.tmp")
        temp.writeText(document)
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "layout backup could not be moved into place" }
        }
        name
    }

    companion object {
        /** Directory under `filesDir` that holds migration backups. */
        const val DirectoryName = "backups"

        /** Builds the production store for an app context. */
        fun create(context: Context): FileLayoutBackupStore =
            FileLayoutBackupStore(File(context.applicationContext.filesDir, DirectoryName))
    }
}

/**
 * Name of the document written before generation [generation] is replaced: `layout-v1-2026-10-16.json`
 * at +00:00. Pure; [zoneOffsetMillis] is the offset in effect at [epochMillis].
 */
internal fun layoutBackupFileName(generation: Int, epochMillis: Long, zoneOffsetMillis: Int): String {
    val date = Instant.ofEpochMilli(epochMillis)
        .atOffset(ZoneOffset.ofTotalSeconds(zoneOffsetMillis / MillisPerSecond))
        .toLocalDate()
    return "layout-v$generation-$date.json"
}

private const val MillisPerSecond = 1000
