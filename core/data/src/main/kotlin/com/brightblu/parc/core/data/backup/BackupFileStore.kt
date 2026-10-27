package com.brightblu.parc.core.data.backup

import android.content.Context
import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads and writes one backup document named by a Storage Access Framework URI string (T17, F-15).
 *
 * Thread-safe: implementations do file I/O and must be called off the main thread. Both methods
 * throw on failure; the caller turns that into a typed result. No permission is involved, because
 * SAF grants access per document the user picked.
 */
interface BackupFileStore {
    /** The whole document as UTF-8 text. Throws when the URI cannot be opened or read. */
    suspend fun read(uri: String): String

    /** Replaces the document with [text]. Throws when the URI cannot be opened or written. */
    suspend fun write(uri: String, text: String)
}

/**
 * Production [BackupFileStore] over [Context.getContentResolver].
 *
 * The `wt` mode truncates the document first, so replacing a larger backup with a smaller one never
 * leaves trailing bytes. I/O runs on [dispatcher]. Unverified at runtime: no test or device run in
 * this task exercises a real SAF provider, only the interface is faked.
 */
class AndroidBackupFileStore(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BackupFileStore {

    private val resolver = context.applicationContext.contentResolver

    override suspend fun read(uri: String): String = withContext(dispatcher) {
        val stream = resolver.openInputStream(Uri.parse(uri))
            ?: throw IOException("Backup document cannot be opened for reading")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    override suspend fun write(uri: String, text: String) = withContext(dispatcher) {
        val stream = resolver.openOutputStream(Uri.parse(uri), WriteTruncate)
            ?: throw IOException("Backup document cannot be opened for writing")
        stream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
    }

    private companion object {
        const val WriteTruncate = "wt"
    }
}
