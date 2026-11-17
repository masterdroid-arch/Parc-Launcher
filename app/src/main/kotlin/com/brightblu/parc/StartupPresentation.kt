package com.brightblu.parc

import com.brightblu.parc.model.AppEntry

/**
 * T262: the first frame uses the startup snapshot's app list when it has one. A missing or older app
 * index snapshot must not leave the arcs empty while the launcher is still reading the live index.
 */
internal object StartupPresentation {
    fun apps(restored: List<AppEntry>, indexed: List<AppEntry>): List<AppEntry> =
        if (restored.isNotEmpty()) restored else indexed
}
