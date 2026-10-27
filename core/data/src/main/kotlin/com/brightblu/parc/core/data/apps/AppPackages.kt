package com.brightblu.parc.core.data.apps

import com.brightblu.parc.model.AppEntry

/**
 * Package-level join used by every T47 source. `UsageStatsManager` and `UsageEvents` identify a
 * package, while Parc identifies an activity (`AppId`), so one launchable entry per package is the
 * finest mapping that can be made without guessing. When a package has several launchable
 * activities, the smallest id wins, which is deterministic across restarts (the same rule T13 uses
 * for optional enrichment).
 *
 * The launcher's own package is excluded: time spent looking at Home is not app screen time, and it
 * would otherwise dominate the list.
 */
internal object AppPackages {

    /** Deterministic package to launchable-entry map, without [excludedPackage]. */
    fun byPackage(apps: List<AppEntry>, excludedPackage: String? = null): Map<String, AppEntry> {
        val out = LinkedHashMap<String, AppEntry>()
        for (app in apps) {
            if (app.packageName == excludedPackage) continue
            val existing = out[app.packageName]
            if (existing == null || app.id.value < existing.id.value) out[app.packageName] = app
        }
        return out
    }
}
