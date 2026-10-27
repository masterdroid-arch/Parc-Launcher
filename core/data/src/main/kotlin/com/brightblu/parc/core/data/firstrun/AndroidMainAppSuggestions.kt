package com.brightblu.parc.core.data.firstrun

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.brightblu.parc.model.AppId
import java.util.Locale

/**
 * Resolves the four picker suggestions on Android (T62): dialer, messaging app, browser, camera.
 *
 * A package is only accepted when it is one of the installed launchables the picker will show. If
 * resolution throws or returns nothing, the pure [MainAppSuggestions] hints decide. Intent
 * resolution for these actions is assumed to work under the launcher's `<queries>` manifest entry;
 * that assumption is not verified on a device in this task.
 */
class AndroidMainAppSuggestions(context: Context) {

    private val packageManager = context.packageManager

    fun suggest(installed: List<InstalledApp>): List<AppId> {
        val installedPackages = installed.mapTo(mutableSetOf()) { it.packageName.lowercase(Locale.ROOT) }
        val resolved = listOf(
            listOf(Intent(Intent.ACTION_DIAL)),
            listOf(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)),
            listOf(
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                    .addCategory(Intent.CATEGORY_BROWSABLE),
            ),
            listOf(Intent(MediaStore.ACTION_IMAGE_CAPTURE)),
        ).map { candidates -> resolve(candidates, installedPackages) }
        return MainAppSuggestions.suggest(installed, resolved)
    }

    private fun resolve(candidates: List<Intent>, installedPackages: Set<String>): String? {
        candidates.forEach { intent ->
            try {
                val match = packageManager.queryIntentActivities(intent, 0)
                    .asSequence()
                    .mapNotNull { it.activityInfo?.packageName }
                    .firstOrNull { it.lowercase(Locale.ROOT) in installedPackages }
                if (match != null) return match
            } catch (_: RuntimeException) {
                // Fall through to the next candidate; the pure hints still have a turn.
            }
        }
        return null
    }
}
