package com.brightblu.parc.core.data.storage

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Process
import android.os.storage.StorageManager
import com.brightblu.parc.core.data.usage.UsageAccess

/** One package's three StorageStats counters, neutral so aggregation is testable without Android. */
internal data class PackageStorageSample(
    val packageName: String,
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
)

/**
 * The Android boundary for T52 storage. Implementations must not throw: a missing service, a
 * package that disappeared between the app-index read and the query, or a refused query is a null
 * sample, and the repository skips that package. [usageAccessGranted] is the single app-op read.
 *
 * Thread-safety: may be called from any thread; implementations must be re-entrant.
 */
internal interface StorageStatsProbe {

    fun usageAccessGranted(): Boolean

    /** One `StorageStatsManager.queryStatsForPackage`, or null when it could not be answered. */
    fun statsFor(packageName: String): PackageStorageSample?
}

/**
 * Production probe. `queryStatsForPackage` documents that PACKAGE_USAGE_STATS is required for any
 * package other than Parc itself; the repository checks the same app op once before the loop, so an
 * ungranted device never sees a half-populated list.
 *
 * The volume is the primary internal one (`StorageManager.UUID_DEFAULT`) and the user is the
 * process user: Parc is a single-user launcher. The call itself is unverified on Android 16 until
 * the Apps page runs on a device (T54).
 */
internal class AndroidStorageStatsProbe(context: Context) : StorageStatsProbe {

    private val appContext = context.applicationContext

    private val statsManager: StorageStatsManager? =
        appContext.getSystemService(StorageStatsManager::class.java)

    override fun usageAccessGranted(): Boolean = UsageAccess.granted(appContext)

    override fun statsFor(packageName: String): PackageStorageSample? {
        val manager = statsManager ?: return null
        return try {
            val stats = manager.queryStatsForPackage(
                StorageManager.UUID_DEFAULT,
                packageName,
                Process.myUserHandle(),
            )
            PackageStorageSample(
                packageName = packageName,
                appBytes = stats.appBytes,
                dataBytes = stats.dataBytes,
                cacheBytes = stats.cacheBytes,
            )
        } catch (unexpected: Throwable) {
            // NameNotFoundException, SecurityException, a dead service or a vendor refusal: one
            // package missing is not a reason to hide the rest of the list.
            null
        }
    }
}
