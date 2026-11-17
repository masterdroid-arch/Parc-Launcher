package com.brightblu.parc

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * T267: the one place for Parc's donation link.
 *
 * [donate] stays empty until the owner supplies the URL. An empty value keeps the
 * "Support Parc" row out of About, so no dead link is ever shown.
 */
internal object SupportLinks {
    const val donate: String = ""

    /** A link is shown only when it has a destination. Pure so the empty case can be tested. */
    fun isVisible(url: String): Boolean = url.isNotBlank()

    /** Opens [url] in whatever handles it. Never throws if no app can open the link. */
    fun open(context: Context, url: String) {
        if (!isVisible(url)) return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
