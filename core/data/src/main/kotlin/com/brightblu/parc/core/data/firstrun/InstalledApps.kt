package com.brightblu.parc.core.data.firstrun

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.brightblu.parc.core.data.AndroidLauncherSource
import com.brightblu.parc.core.data.LauncherSource
import com.brightblu.parc.core.data.appIdOf
import com.brightblu.parc.model.AppId

/**
 * Coarse app kind used by the first-run folder builder (T62).
 *
 * It mirrors [ApplicationInfo.category] one-to-one. Intent categories such as
 * `Intent.CATEGORY_APP_BROWSER` are not used here: they describe intent filters, not the installed
 * application, and most apps never set them.
 */
enum class AppKind {
    Game,
    Social,
    Audio,
    Video,
    Image,
    News,
    Maps,
    Productivity,
    Accessibility,
    Uncategorised,
}

/** One launchable app the picker may show. Immutable; the id is the stable [AppId]. */
data class InstalledApp(
    val id: AppId,
    val label: String,
    val packageName: String,
    val kind: AppKind = AppKind.Uncategorised,
)

/** Reads the installed launchable apps. Android implementations touch LauncherApps; tests fake it. */
interface InstalledAppsSource {
    fun read(): List<InstalledApp>

    /** The kind of one package, used when the caller already has the app list. */
    fun kindOf(packageName: String): AppKind
}

/**
 * Production [InstalledAppsSource]: the same LauncherApps snapshot the launcher indexes, plus the
 * category from [PackageManager].
 *
 * The launcher's own package is excluded because a launcher never lists itself. Category reads are
 * best effort: a package the manager cannot resolve (or cannot see) reads as [AppKind.Uncategorised]
 * rather than failing the picker. Package visibility for a package already returned by LauncherApps
 * is assumed, not verified on a device.
 */
class AndroidInstalledApps private constructor(
    private val source: LauncherSource,
    private val packageManager: PackageManager,
    private val ownPackage: String,
) : InstalledAppsSource {

    override fun read(): List<InstalledApp> = source.activities()
        .asSequence()
        .filter { it.packageName != ownPackage }
        .map { activity ->
            InstalledApp(
                id = appIdOf(activity.component, activity.userSerial),
                label = activity.label,
                packageName = activity.packageName,
                kind = kindOf(activity.packageName),
            )
        }
        .sortedWith(compareBy({ it.label.lowercase(java.util.Locale.ROOT) }, { it.id.value }))
        .toList()

    override fun kindOf(packageName: String): AppKind = try {
        packageManager.getApplicationInfo(packageName, 0).category.toAppKind()
    } catch (_: PackageManager.NameNotFoundException) {
        AppKind.Uncategorised
    } catch (_: RuntimeException) {
        AppKind.Uncategorised
    }

    companion object {
        /** Builds the reader for an app context. */
        fun create(context: Context): AndroidInstalledApps = AndroidInstalledApps(
            source = AndroidLauncherSource(context),
            packageManager = context.packageManager,
            ownPackage = context.packageName,
        )
    }
}

/** Maps the platform's category constant to the seed's coarse kind. */
internal fun Int.toAppKind(): AppKind = when (this) {
    ApplicationInfo.CATEGORY_GAME -> AppKind.Game
    ApplicationInfo.CATEGORY_SOCIAL -> AppKind.Social
    ApplicationInfo.CATEGORY_AUDIO -> AppKind.Audio
    ApplicationInfo.CATEGORY_VIDEO -> AppKind.Video
    ApplicationInfo.CATEGORY_IMAGE -> AppKind.Image
    ApplicationInfo.CATEGORY_NEWS -> AppKind.News
    ApplicationInfo.CATEGORY_MAPS -> AppKind.Maps
    ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppKind.Productivity
    ApplicationInfo.CATEGORY_ACCESSIBILITY -> AppKind.Accessibility
    else -> AppKind.Uncategorised
}
