package com.brightblu.parc

import android.content.Context
import android.graphics.BitmapFactory
import com.brightblu.parc.core.data.backup.*
import com.brightblu.parc.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64

/** Separate file entry: omitted entirely unless the user enabled inclusion. */
internal class WallpaperImageBackupAccess(private val context: Context) : StoreAccess {
    override suspend fun read(spec: BackupStoreSpec): Map<String, Any> = withContext(Dispatchers.IO) {
        val look = ParcLookStore(context).look.first()
        val file = WallpaperImageStore(context).file
        if (!WallpaperImageRules.includeInBackup(look.includeWallpaperInBackups, file.length())) emptyMap()
        else runCatching { WallpaperImageBackupRules.encode(true, file.readBytes())?.let { mapOf("jpeg" to it) } ?: emptyMap() }.getOrDefault(emptyMap())
    }
    override suspend fun write(spec: BackupStoreSpec, values: Map<String, Any>) = withContext(Dispatchers.IO) {
        val text = values["jpeg"] as? String ?: return@withContext
        if (!BackupValues.matches(spec.key("jpeg") ?: return@withContext, text)) return@withContext
        runCatching {
            val bytes = WallpaperImageBackupRules.decode(text) ?: error("Invalid image data")
            require(WallpaperImageRules.acceptsBytes(bytes.size.toLong()))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 && maxOf(bounds.outWidth, bounds.outHeight) <= Metrics.wallpaperMaxSide)
            // Decode as well as checking the header, so truncated/corrupt streams never replace a good file.
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid wallpaper")
            decoded.recycle()
            val file = WallpaperImageStore(context).file
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "restore.jpg")
            temp.writeBytes(bytes)
            require(temp.renameTo(file))
            ParcLookStore(context).update { it.copy(wallpaperImageRevision = System.currentTimeMillis()) }
        }
        Unit
    }
}
