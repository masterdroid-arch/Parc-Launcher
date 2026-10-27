package com.brightblu.parc.core.data.firstrun

import com.brightblu.parc.model.AppId
import java.util.Locale

/**
 * Pure rules for the picker's pre-selection (T62).
 *
 * The Android layer resolves four intent targets in a fixed order: dialer, messaging app, browser,
 * camera. [suggest] turns those package names into installed [AppId]s, deduplicates them and fills a
 * gap with a package-name hint when intent resolution returned nothing. It never invents an app: a
 * slot stays empty when no installed app matches.
 */
object MainAppSuggestions {

    /** Package fragments tried, in slot order, when intent resolution yields no usable package. */
    private val hints: List<List<String>> = listOf(
        listOf("dialer", "phone", "contacts"),
        listOf("messaging", "mms", "sms"),
        listOf("browser", "chrome", "firefox"),
        listOf("camera"),
    )

    /**
     * At most four ids. [resolvedPackages] has the four slots' package names (null when the platform
     * resolved nothing); each is matched exactly against an installed package.
     */
    fun suggest(
        installed: List<InstalledApp>,
        resolvedPackages: List<String?>,
    ): List<AppId> {
        val byPackage = installed.associateBy { it.packageName.lowercase(Locale.ROOT) }
        val chosen = LinkedHashSet<AppId>()
        val ordered = installed.sortedWith(compareBy({ it.label.lowercase(Locale.ROOT) }, { it.id.value }))

        hints.indices.forEach { slot ->
            if (chosen.size >= HINT_SLOTS) return@forEach
            val resolved = resolvedPackages.getOrNull(slot)
                ?.lowercase(Locale.ROOT)
                ?.let(byPackage::get)
            val candidate = resolved ?: ordered.firstOrNull { app ->
                app.id !in chosen && hints[slot].any { hint -> app.packageName.contains(hint, ignoreCase = true) }
            }
            if (candidate != null && candidate.id !in chosen) chosen += candidate.id
        }
        return chosen.toList()
    }

    private const val HINT_SLOTS = 4
}
