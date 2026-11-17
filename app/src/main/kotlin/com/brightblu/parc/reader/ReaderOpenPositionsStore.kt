package com.brightblu.parc.reader

import android.content.Context
import com.brightblu.parc.core.data.library.BookPosition
import com.brightblu.parc.core.data.library.ReaderOpenPosition
import com.brightblu.parc.core.data.library.ReaderOpenPositions
import com.brightblu.parc.core.data.library.ReaderOpenPositionsJson

/**
 * T209: places for files that were opened but never added to the library. A tiny synchronous
 * preference file is used on purpose: the reader wants its start position before the first frame,
 * and the document holds a handful of short rows. It is not part of the backup.
 */
internal class ReaderOpenPositionsStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PrefsFile, Context.MODE_PRIVATE)

    fun position(hash: String): BookPosition? {
        val now = clock()
        return ReaderOpenPositions.find(read(now), hash, now)?.position()
    }

    fun save(hash: String, position: BookPosition) {
        val now = clock()
        write(ReaderOpenPositions.merge(read(now), ReaderOpenPosition.of(hash, position, now), now))
    }

    fun remove(hash: String) {
        write(read(clock()).filterNot { it.hash == hash })
    }

    private fun write(entries: List<ReaderOpenPosition>) {
        val text = runCatching { ReaderOpenPositionsJson.encode(entries) }.getOrNull() ?: return
        check(prefs.edit().putString(Key, text).commit()) { "Could not save reading position" }
    }

    private fun read(now: Long): List<ReaderOpenPosition> =
        ReaderOpenPositionsJson.decode(prefs.getString(Key, null), now)

    private companion object {
        const val PrefsFile = "parc_reader_open"
        const val Key = "positions_v1"
    }
}
