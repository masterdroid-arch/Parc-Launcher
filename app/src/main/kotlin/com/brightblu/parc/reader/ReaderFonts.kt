package com.brightblu.parc.reader

import android.content.Context
import android.util.Base64
import com.brightblu.parc.core.model.R

/** T255: the one font that ships inside the app and can be drawn by the reader's page. */
internal object ReaderFonts {
    @Volatile private var cached: String? = null

    fun figtreeBase64(context: Context): String = cached ?: runCatching {
        context.resources.openRawResource(R.font.figtree_variable).use { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }
    }.getOrDefault("").also { cached = it }
}
