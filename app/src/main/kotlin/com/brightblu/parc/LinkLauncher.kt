package com.brightblu.parc

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.brightblu.parc.model.Slot

/**
 * Opens a generic [Slot.Link] with `ACTION_VIEW`. No app, package or URI scheme is special-cased:
 * an imported layout carrying a link slot behaves like any other deep link, and the platform picks
 * the handler. Returns false when nothing can open the URI.
 */
internal class LinkLauncher(private val context: Context) {
    fun open(link: Slot.Link): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.uri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) { false }
      catch (_: SecurityException) { false }
      catch (_: IllegalArgumentException) { false }
}
