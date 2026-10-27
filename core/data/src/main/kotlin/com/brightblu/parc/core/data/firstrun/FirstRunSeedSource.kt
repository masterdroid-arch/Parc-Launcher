package com.brightblu.parc.core.data.firstrun

import com.brightblu.parc.core.data.FirstRunSeed
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.ParcLayout

/**
 * Supplies the first-run layout at cold start (T62).
 *
 * The stored picker choice wins when it still names installed apps; gaps are filled from the intent
 * suggestions. [kindOf] comes from Android's [android.content.pm.ApplicationInfo.category]; every
 * collaborator is a function, so the whole path is testable without a device.
 */
internal class FirstRunSeedSource(
    private val kindOf: (String) -> AppKind,
    private val suggestion: (List<InstalledApp>) -> List<AppId>,
    private val storedMainApps: suspend () -> List<AppId>,
) {
    suspend fun build(installed: List<AppEntry>, specs: List<ArcSpec>): ParcLayout {
        val apps = installed.map { entry ->
            InstalledApp(
                id = entry.id,
                label = entry.label,
                packageName = entry.packageName,
                kind = kindOf(entry.packageName),
            )
        }
        val known = apps.mapTo(mutableSetOf()) { it.id }
        val stored = storedMainApps().filter { it in known }
        val chosen = (stored + suggestion(apps))
            .distinct()
            .filter { it in known }
        return FirstRunSeed.build(apps, chosen, specs)
    }
}
