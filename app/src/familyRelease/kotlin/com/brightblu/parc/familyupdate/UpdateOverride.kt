package com.brightblu.parc.familyupdate

import android.content.Context
import java.net.URL

/** T396: the release build has no override at all. The address and the hosts are fixed. */
internal object UpdateOverride {
    fun releaseUrl(context: Context): String? = null
    fun allows(url: URL): Boolean = false
}
