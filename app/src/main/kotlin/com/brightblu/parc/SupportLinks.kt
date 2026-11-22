package com.brightblu.parc

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The one place for Parc's donation link (T267, set by T309).
 *
 * [donate] is the Ko-fi page. An empty value keeps the "Support Parc" row out of
 * About, so no dead link is ever shown. [donateNote] is the plain, honest wording
 * shown beside the row.
 */
internal object SupportLinks {
    const val donate: String = "https://ko-fi.com/parcapp"

    /** Shown in About next to the Support row: free, optional tip, nothing locked behind it. */
    const val donateNote: String =
        "Parc is free and always will be. If it helps you, you can leave a tip on Ko-fi. Nothing is locked behind it."

    /** A link is shown only when it has a destination. Pure so the empty case can be tested. */
    fun isVisible(url: String): Boolean = url.isNotBlank()

    /** Opens [url] in whatever handles it. Never throws if no app can open the link. */
    fun open(context: Context, url: String) {
        if (!isVisible(url)) return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
