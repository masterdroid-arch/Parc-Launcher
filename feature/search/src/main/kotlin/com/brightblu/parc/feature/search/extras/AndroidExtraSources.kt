package com.brightblu.parc.feature.search.extras

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import com.brightblu.parc.core.data.agenda.AgendaEvent
import com.brightblu.parc.core.data.agenda.AgendaRead
import com.brightblu.parc.core.data.agenda.AndroidAgendaSource

/** The media permissions the Files source uses. Each one only unlocks its own type. */
object FilePermissions {
    val all: Array<String> = arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO,
    )

    fun granted(context: Context, permission: String): Boolean = try {
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    fun anyGranted(context: Context): Boolean = all.any { granted(context, it) }
}

/** T102: a Settings page exists on this phone when some activity answers its action. */
class AndroidSettingsAvailability(context: Context) : SettingsAvailability {
    private val packageManager = context.applicationContext.packageManager
    private val cache = HashMap<String, Boolean>()

    override fun has(action: String): Boolean = synchronized(cache) {
        cache.getOrPut(action) {
            try {
                packageManager.resolveActivity(Intent(action), 0) != null
            } catch (_: Exception) {
                false
            }
        }
    }
}

/**
 * Production [FileSource] over `MediaStore` (T102). Reads file names, ids, types and add dates only;
 * never file contents, and nothing is stored. Only types whose media permission is granted are
 * queried; downloads that are media of a granted type appear through that type. Every failure
 * (revoked permission, missing provider, vendor quirk) returns an empty list.
 */
class AndroidFilesSource(context: Context) : FileSource {
    private val appContext = context.applicationContext

    override fun hasPermission(): Boolean = com.brightblu.parc.core.data.DistributionFeatures.files(appContext) && FilePermissions.anyGranted(appContext)

    override fun search(query: String, limit: Int): List<FileRecord> {
        if (limit <= 0 || query.isBlank() || !hasPermission()) return emptyList()
        val types = grantedTypes()
        if (types.isEmpty()) return emptyList()
        return try {
            queryProvider(query, limit, types)
        } catch (_: Exception) {
            // SecurityException after a revoke, IllegalArgumentException from a vendor provider, and the rest.
            emptyList()
        }
    }

    private fun grantedTypes(): List<Int> {
        val out = ArrayList<Int>(3)
        if (FilePermissions.granted(appContext, Manifest.permission.READ_MEDIA_IMAGES)) out += MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        if (FilePermissions.granted(appContext, Manifest.permission.READ_MEDIA_VIDEO)) out += MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        if (FilePermissions.granted(appContext, Manifest.permission.READ_MEDIA_AUDIO)) out += MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO
        return out
    }

    private fun queryProvider(query: String, limit: Int, types: List<Int>): List<FileRecord> {
        val escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        // Words may be separated by anything in a file name, so a multi-word query matches on its first word
        // here and the pure ranker checks the rest.
        val firstWord = escaped.split(' ').firstOrNull { it.isNotEmpty() } ?: return emptyList()
        val selection = "${FileColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\' AND ${FileColumns.MEDIA_TYPE} IN (${types.joinToString(",")})"
        val out = ArrayList<FileRecord>()
        val projection = arrayOf(FileColumns._ID, FileColumns.DISPLAY_NAME, FileColumns.MIME_TYPE, FileColumns.MEDIA_TYPE, FileColumns.DATE_ADDED, FileColumns.RELATIVE_PATH)
        appContext.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            projection,
            selection,
            arrayOf("%$firstWord%"),
            "${FileColumns.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                try {
                    val id = c.getLong(0)
                    val name = c.getString(1)?.trim().orEmpty()
                    if (id <= 0L || name.isEmpty()) continue
                    val mediaType = if (c.isNull(3)) 0 else c.getInt(3)
                    val inDownloads = c.getString(5)?.startsWith("Download", ignoreCase = true) == true
                    val kind = when {
                        mediaType == FileColumns.MEDIA_TYPE_IMAGE -> FileKind.Image
                        mediaType == FileColumns.MEDIA_TYPE_VIDEO -> FileKind.Video
                        mediaType == FileColumns.MEDIA_TYPE_AUDIO -> FileKind.Audio
                        else -> FileKind.Other
                    }
                    val base = when (kind) {
                        FileKind.Image -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                        FileKind.Video -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                        FileKind.Audio -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                        else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    }
                    out += FileRecord(
                        id = id,
                        name = name,
                        uri = ContentUris.withAppendedId(base, id).toString(),
                        mime = c.getString(2),
                        kind = if (inDownloads && kind != FileKind.Other) FileKind.Download else kind,
                        addedSeconds = if (c.isNull(4)) 0 else c.getLong(4),
                    )
                } catch (_: Exception) {
                    continue
                }
            }
        }
        return out
    }
}

/** Production [EventSource]: the T100 calendar reader, reused. */
class AndroidEventSource(context: Context) : EventSource {
    private val appContext = context.applicationContext
    private val agenda = AndroidAgendaSource(appContext)

    override fun hasPermission(): Boolean = AndroidAgendaSource.granted(appContext)

    override fun events(fromMs: Long, toMs: Long): List<AgendaEvent> = try {
        (agenda.events(fromMs, toMs) as? AgendaRead.Data)?.events.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }
}

/** Intents the sheet fires for extra results. Pure builders so tests can check them; no starting here. */
object ExtraIntents {

    fun settings(action: String): Intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun file(file: FileRecord): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(file.uri), file.mime)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun event(event: AgendaEvent): Intent? {
        val uri = AndroidAgendaSource.eventUri(event.eventId) ?: return null
        return Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
