package com.brightblu.parc

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The one place for Parc's donation link (T267, set by T309).
 *
 * [donate] is the Ko-fi page. An empty value keeps the "Support Parc" row out of
 * About, so no dead link is ever shown. [rowTitle] and [rowNote] are the plain, honest
 * wording of the row (T394); the note matches the Support section of the README.
 */
internal object SupportLinks {
    const val donate: String = "https://ko-fi.com/parcapp"

    /** The About row's title. Opening it is a normal view intent; Parc makes no network call itself. */
    const val rowTitle: String = "Support Parc on Ko-fi"

    /** The one line under the row: optional, nothing locked behind it. */
    const val rowNote: String = "Optional. Nothing is locked behind it."

    /** A link is shown only when it has a destination. Pure so the empty case can be tested. */
    fun isVisible(url: String): Boolean = url.isNotBlank()

    /** Opens [url] in whatever handles it. Never throws if no app can open the link. */
    fun open(context: Context, url: String) {
        if (!isVisible(url)) return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
