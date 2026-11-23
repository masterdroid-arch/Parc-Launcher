package com.brightblu.parc.familyupdate

import android.content.Context
import java.io.File
import java.net.URL

/**
 * T396, DEBUG BUILDS ONLY (this file is in `src/familyDebug`; the release build compiles `src/familyRelease`, which
 * has no override): lets an emulator check use a local test server. The first line of `files/update-override.txt`
 * in the app's private folder is the address to ask instead of GitHub; its host is then also allowed for the
 * downloads the fake release names. Nothing happens when that file does not exist.
 */
internal object UpdateOverride {
    private const val FileName = "update-override.txt"

    @Volatile private var host: String? = null

    fun releaseUrl(context: Context): String? {
        val text = runCatching { File(context.filesDir, FileName).takeIf { it.isFile }?.readText()?.lineSequence()?.firstOrNull()?.trim() }
            .getOrNull()?.takeIf { it.isNotEmpty() }
        host = text?.let { runCatching { URL(it).host }.getOrNull() }
        return text
    }

    fun allows(url: URL): Boolean = host != null && url.host == host
}
