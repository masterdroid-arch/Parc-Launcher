package com.brightblu.parc.core.data.storage

import android.content.Context
import com.brightblu.parc.core.data.apps.AppPackages
import com.brightblu.parc.model.AppEntry
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production [AppStorageRepository]. The usage-access app op is checked once; without it the answer
 * is [StorageResult.Unavailable] and no package is queried. With it, each launchable package is
 * queried separately, so one uninstalled or refused package cannot erase the others.
 *
 * The launcher's own package is included when the app index contains it: unlike screen time, its
 * storage is a real, user-visible app size, and hiding it would understate the list. The app index
 * is package-deduplicated by T47's [AppPackages], so a package with several activities appears once.
 *
 * No cache: the Apps page reads when it opens, and an uninstalled app must not linger.
 */
class AndroidAppStorageRepository internal constructor(
    private val probe: StorageStatsProbe,
    private val installed: () -> List<AppEntry>,
    private val ioDispatcher: CoroutineDispatcher,
) : AppStorageRepository {

    override suspend fun read(): StorageResult = withContext(ioDispatcher) {
        val granted = try {
            probe.usageAccessGranted()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Throwable) {
            false
        }
        if (!granted) return@withContext StorageResult.Unavailable

        val apps = try {
            installed()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Throwable) {
            emptyList()
        }
        val byPackage = AppPackages.byPackage(apps)

        val samples = ArrayList<PackageStorageSample>(byPackage.size)
        var failures = 0
        for (packageName in byPackage.keys) {
            val sample = try {
                probe.statsFor(packageName)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unexpected: Throwable) {
                null
            }
            if (sample == null) failures++ else samples += sample
        }

        val entries = StorageMath.entries(samples, byPackage)
        // Nothing came back although there were packages to ask about: treat it as a refused
        // platform answer rather than as "every app is zero bytes".
        if (entries.isEmpty() && byPackage.isNotEmpty() && failures == byPackage.size) {
            StorageResult.Unavailable
        } else {
            StorageResult.Available(entries)
        }
    }

    companion object {
        /** Builds the production repository. [installed] is normally `appIndex.apps.value`. */
        fun create(
            context: Context,
            installed: () -> List<AppEntry>,
        ): AndroidAppStorageRepository = AndroidAppStorageRepository(
            probe = AndroidStorageStatsProbe(context.applicationContext),
            installed = installed,
            ioDispatcher = Dispatchers.IO,
        )
    }
}
