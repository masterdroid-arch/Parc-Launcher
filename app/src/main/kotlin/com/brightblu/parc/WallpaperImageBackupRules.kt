package com.brightblu.parc

import com.brightblu.parc.model.WallpaperImageRules
import java.util.Base64

internal object WallpaperImageBackupRules {
    fun encode(enabled: Boolean, bytes: ByteArray): String? =
        if (WallpaperImageRules.includeInBackup(enabled, bytes.size.toLong())) Base64.getEncoder().encodeToString(bytes) else null
    fun decode(text: String): ByteArray? = runCatching {
        require(text.length <= (com.brightblu.parc.model.Metrics.wallpaperMaxBytes + 2) / 3 * 4)
        Base64.getDecoder().decode(text).also { require(WallpaperImageRules.acceptsBytes(it.size.toLong())) }
    }.getOrNull()
}
