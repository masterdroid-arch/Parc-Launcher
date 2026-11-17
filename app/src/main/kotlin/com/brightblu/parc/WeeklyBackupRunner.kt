package com.brightblu.parc

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.ErrnoException
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.brightblu.parc.core.data.LayoutRepositoryImpl
import com.brightblu.parc.core.data.backup.FullBackup
import com.brightblu.parc.core.data.skins.CustomSkinStore
import com.brightblu.parc.feature.settings.backup.BackupFileName
import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.ring.RingLayout
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The chosen folder cannot be opened at all, so the system grant or the folder itself is gone. */
internal class WeeklyBackupFolderGone(cause: Throwable? = null) : Exception("Backup folder is gone", cause)

/**
 * T266: the chosen folder through Android's Storage Access Framework. Stores the tree URI the
 * folder picker granted; every write is a normal document write, so no storage permission is used
 * and nothing outside the chosen folder is touched.
 */
internal class AndroidBackupFolder(context: Context, private val treeUri: Uri) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun list(): List<WeeklyBackupFile> = withContext(Dispatchers.IO) {
        try {
            children()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WeeklyBackupFolderGone(e)
        }
    }

    /**
     * T270: the backup is written to `<name>.tmp` first and checked, so a failed write can never
     * corrupt the day's earlier backup. Only then is the old dated document replaced by a rename.
     * A provider that cannot rename falls back to writing the final name, with the checked temp
     * document kept until that finishes.
     */
    suspend fun write(name: String, text: String): WeeklyBackupFile = withContext(Dispatchers.IO) {
        val existing = try {
            children()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WeeklyBackupFolderGone(e)
        }
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val tempName = WeeklyBackupFiles.tempNameFor(name)
        existing.filter { it.name == tempName }.forEach { runCatching { delete(it) } }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val temp = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", tempName)
            ?: throw IOException("Backup document could not be created")
        var fallbackStarted = false
        try {
            val stream = resolver.openOutputStream(temp, "wt")
                ?: throw IOException("Backup document could not be opened for writing")
            stream.use { it.write(bytes) }
            val size = resolver.query(temp, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
            }
            if (size != null && size != bytes.size.toLong()) throw IOException("Backup document was not fully written")
            existing.firstOrNull { it.name == name }?.let { delete(it) }
            val renamed = runCatching { DocumentsContract.renameDocument(resolver, temp, name) }.getOrNull()
            if (renamed != null) return@withContext WeeklyBackupFile(name, renamed.toString())
            fallbackStarted = true
            // No rename support: write the final document now; the verified temp stays until it is done.
            val target = DocumentsContract.createDocument(resolver, parent, BackupFileName.MimeType, name)
                ?: throw IOException("Backup document could not be created")
            val out = resolver.openOutputStream(target, "wt")
                ?: throw IOException("Backup document could not be opened for writing")
            out.use { it.write(bytes) }
            runCatching { DocumentsContract.deleteDocument(resolver, temp) }
            WeeklyBackupFile(name, target.toString())
        } catch (e: Throwable) {
            // Remove only our own temp document; the old day's backup was never touched before the rename.
            if (!fallbackStarted) runCatching { DocumentsContract.deleteDocument(resolver, temp) }
            throw e
        }
    }

    suspend fun delete(file: WeeklyBackupFile) = withContext(Dispatchers.IO) {
        if (!DocumentsContract.deleteDocument(resolver, Uri.parse(file.uri))) {
            throw IOException("Old backup document could not be removed")
        }
    }

    private fun children(): List<WeeklyBackupFile> {
        val parentId = DocumentsContract.getTreeDocumentId(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val cursor = resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        ) ?: throw IOException("Backup folder could not be opened")
        val out = ArrayList<WeeklyBackupFile>()
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(0)?.takeIf { value -> value.isNotBlank() } ?: continue
                val id = it.getString(1)?.takeIf { value -> value.isNotBlank() } ?: continue
                out += WeeklyBackupFile(name, DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString())
            }
        }
        return out
    }

    companion object {
        /** The folder's own display name, for the Backup screen's folder row. */
        fun displayName(context: Context, treeUri: Uri): String? = runCatching {
            val resolver = context.applicationContext.contentResolver
            val document = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

/** T266: sort a folder or write failure into the two clear cases the card names, then the rest. */
internal object WeeklyBackupFailures {
    fun classify(error: Throwable): WeeklyBackupFailure {
        if (error is WeeklyBackupFolderGone) return WeeklyBackupFailure.FolderGone
        if (error is SecurityException) return WeeklyBackupFailure.FolderGone
        if (error is FileNotFoundException) return WeeklyBackupFailure.FolderGone
        var current: Throwable? = error
        while (current != null) {
            if (current is ErrnoException && current.errno == 28) return WeeklyBackupFailure.NoSpace
            val message = current.message.orEmpty()
            if (message.contains("ENOSPC", ignoreCase = true) ||
                message.contains("no space left", ignoreCase = true) ||
                message.contains("no space", ignoreCase = true)
            ) return WeeklyBackupFailure.NoSpace
            current = current.cause
        }
        return WeeklyBackupFailure.WriteFailed
    }
}

/**
 * T266: one weekly run. It builds exactly the manual backup (same `FullBackup`, layout and stores),
 * writes it under the manual dated name, then keeps the newest four. Every failure is stored before
 * returning so the Backup screen can say what happened.
 */
internal class WeeklyBackupRunner(
    private val context: Context,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    suspend fun run(): WeeklyBackupFailure? {
        if (DemoMode.enabled) return null
        val store = WeeklyBackupStore(context)
        val settings = store.read()
        if (!settings.enabled) return null
        val folderUri = settings.folderUri?.takeIf { it.isNotBlank() }
            ?: return fail(store, WeeklyBackupFailure.FolderGone)
        val folder = AndroidBackupFolder(context, Uri.parse(folderUri))

        // Prove the folder is still reachable before building the whole document.
        try {
            folder.list()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(store, WeeklyBackupFailures.classify(e))
        }

        val repository = try {
            // The stored layout is mapped through the same home arc ids the launcher uses. With no
            // specs the mapper would keep every slot row but produce no arcs, and the backup would
            // silently lose the whole home arrangement.
            LayoutRepositoryImpl.create(context) { weeklyBackupArcSpecs(context) }
                .also { it.load(emptyList()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(store, WeeklyBackupFailure.Unreadable)
        }
        if (!repository.loaded) return fail(store, WeeklyBackupFailure.Unreadable)

        val text = try {
            val customSkins = CustomSkinStore(File(context.filesDir, "skins"))
            FullBackup(repository, AppBackup.stores(context, customSkins)).export()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(store, WeeklyBackupFailure.Unreadable)
        }

        val name = WeeklyBackupFiles.nameFor(now(), zone())
        try {
            folder.write(name, text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(store, WeeklyBackupFailures.classify(e))
        }
        store.recordSuccess(now())
        try {
            val old = WeeklyBackupFiles.toDelete(folder.list())
            old.forEach { folder.delete(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(store, WeeklyBackupFailures.classify(e))
        }
        return null
    }

    private fun fail(store: WeeklyBackupStore, failure: WeeklyBackupFailure): WeeklyBackupFailure {
        store.recordFailure(failure, now())
        return failure
    }
}

/**
 * The home arc ids and capacities the stored layout is read through. The worker has no Activity, so
 * it uses the display's own dp size; only the arc ids matter to the backup, and a small fallback
 * screen keeps a malformed display metric from aborting the run.
 */
internal fun weeklyBackupArcSpecs(context: Context): List<ArcSpec> {
    val metrics = context.resources.displayMetrics
    val density = metrics.density.takeIf { it > 0f } ?: 1f
    return weeklyBackupArcSpecs(metrics.widthPixels / density, metrics.heightPixels / density)
}

/** Pure form for the test: the four home arc ids, with a small-screen fallback. */
internal fun weeklyBackupArcSpecs(widthDp: Float, heightDp: Float): List<ArcSpec> =
    runCatching { RingLayout(widthDp, heightDp) }.getOrElse { RingLayout(384f, 853f) }.arcs

/**
 * T266: the WorkManager worker. It is a normal background job with charging and idle constraints;
 * it keeps its own last result in the weekly store, so the screen can report a failed run later.
 */
class WeeklyBackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        WeeklyBackupRunner(applicationContext).run()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ParcLog.e("Backup", "weekly backup failed", e)
        Result.retry()
    }
}
