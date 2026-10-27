package com.brightblu.parc.core.data

import android.content.Context
import android.content.pm.PackageManager

/** Immutable application manifest switches, supplied by the app flavour. No preference can enable a removed feature. */
object DistributionFeatures {
    private fun enabled(context: Context, name: String): Boolean =
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            .metaData?.getBoolean("com.brightblu.parc.$name", true) ?: true

    fun files(context: Context): Boolean = enabled(context, "fileSearchEnabled")
    fun contacts(context: Context): Boolean = enabled(context, "contactSearchEnabled")
    fun personal(context: Context): Boolean = enabled(context, "personalBuild")
}
