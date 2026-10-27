package com.brightblu.parc.core.data.apps

import android.content.Context
import android.content.pm.PackageManager
import com.brightblu.parc.model.AppEntry
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One app first installed inside the window. [installedAtMillis] is epoch milliseconds. */
data class NewApp(val app: AppEntry, val installedAtMillis: Long)

/**
 * Recently installed apps (T47). [recent] joins PackageManager's `firstInstallTime` with the
 * launchable snapshot so every result can be launched, and must be called off the main thread.
 * Newest first. An empty list means nothing was installed in the window; it is not an error.
 */
interface NewAppsProvider {

    suspend fun recent(limit: Int = DefaultLimit): List<NewApp>

    companion object {
        /** A sane cap for the Apps page; the window, not the page, is the real filter. */
        const val DefaultLimit = 20

        /** The card's "last 7 days". */
        const val WindowDays = 7
    }
}

/**
 * PackageManager boundary for install times. Returns null when the read fails, in which case the
 * provider falls back to the install time already carried by [AppEntry] (LauncherApps supplies it,
 * so the feature still works when package visibility hides a package).
 */
internal fun interface PackageInstallTimes {
    fun firstInstallTimes(): Map<String, Long>?
}

internal class AndroidPackageInstallTimes(
    private val packageManager: PackageManager,
) : PackageInstallTimes {

    @Suppress("DEPRECATION")
    override fun firstInstallTimes(): Map<String, Long>? = try {
        packageManager.getInstalledPackages(0)
            .associate { it.packageName to it.firstInstallTime }
    } catch (t: Throwable) {
        null
    }
}

/**
 * Production [NewAppsProvider]. `getInstalledPackages` is filtered by Android's package-visibility
 * rules; Parc's HOME role plus the LAUNCHER `<queries>` entry make launchable packages visible, and
 * the [AppEntry.installTime] fallback covers any package PackageManager hides.
 *
 * System apps are included by default because the card says "apps", and `firstInstallTime` of a
 * preinstalled package normally stays on the system image date; pass `includeSystem = false` to show
 * only user-installed apps. Suspended apps are excluded because T54's "tap to launch" cannot open
 * them.
 */
class AndroidNewAppsProvider internal constructor(
    private val installTimes: PackageInstallTimes,
    private val installed: () -> List<AppEntry>,
    private val ownPackage: String,
    private val clock: () -> Long,
    private val ioDispatcher: CoroutineDispatcher,
    private val includeSystem: Boolean,
) : NewAppsProvider {

    override suspend fun recent(limit: Int): List<NewApp> = withContext(ioDispatcher) {
        val times = try {
            installTimes.firstInstallTimes().orEmpty()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Throwable) {
            emptyMap()
        }
        val apps = try {
            installed()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Throwable) {
            emptyList()
        }
        NewApps.recent(
            apps = apps,
            installTimes = times,
            nowMillis = clock(),
            ownPackage = ownPackage,
            includeSystem = includeSystem,
            limit = limit,
        )
    }

    companion object {
        /** Builds the production provider. [installed] is normally `appIndex.apps.value`. */
        fun create(
            context: Context,
            installed: () -> List<AppEntry>,
            includeSystem: Boolean = true,
        ): AndroidNewAppsProvider = AndroidNewAppsProvider(
            installTimes = AndroidPackageInstallTimes(context.packageManager),
            installed = installed,
            ownPackage = context.packageName,
            clock = System::currentTimeMillis,
            ioDispatcher = Dispatchers.IO,
            includeSystem = includeSystem,
        )
    }
}

/**
 * Pure recent-install maths. An install exactly at the cutoff counts; one millisecond older does
 * not. A future timestamp (clock skew) counts as newest rather than being thrown away. Tie-break is
 * label then id so two apps installed in the same millisecond keep a stable order.
 */
internal object NewApps {

    const val WindowMillis = 7L * 24L * 60L * 60L * 1000L

    fun recent(
        apps: List<AppEntry>,
        installTimes: Map<String, Long>,
        nowMillis: Long,
        ownPackage: String,
        includeSystem: Boolean,
        limit: Int,
        windowMillis: Long = WindowMillis,
    ): List<NewApp> {
        if (limit <= 0) return emptyList()
        val cutoff = nowMillis - windowMillis
        return apps.asSequence()
            .filter { it.packageName != ownPackage }
            .filter { includeSystem || !it.isSystem }
            .filter { !it.suspended }
            .mapNotNull { app ->
                val installedAt = installTimes[app.packageName]?.takeIf { it > 0L }
                    ?: app.installTime.takeIf { it > 0L }
                    ?: return@mapNotNull null
                if (installedAt < cutoff) null else NewApp(app, installedAt)
            }
            .sortedWith(
                compareByDescending<NewApp> { it.installedAtMillis }
                    .thenBy { it.app.label.lowercase() }
                    .thenBy { it.app.id.value },
            )
            .take(limit)
            .toList()
    }
}
