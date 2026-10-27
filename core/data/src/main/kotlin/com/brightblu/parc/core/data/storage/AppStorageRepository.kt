package com.brightblu.parc.core.data.storage

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId

/**
 * How much space one launchable app occupies (T52). All three byte counts are the platform's own
 * `StorageStats` figures: the installed code ([appBytes]), user data ([dataBytes]) and reclaimable
 * cache ([cacheBytes]). [totalBytes] is their sum, which is what "biggest apps by storage" ranks.
 *
 * StorageStats is package-level, so [appId] is the deterministic first launchable entry of that
 * package, the same one-entry-per-package rule T47 uses. Immutable and non-null.
 */
data class AppStorage(
    val appId: AppId,
    val label: String,
    val packageName: String,
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
) {
    val totalBytes: Long get() = appBytes + dataBytes + cacheBytes
}

/**
 * The result of one per-app storage read. [Unavailable] means the GET_USAGE_STATS app op is not
 * granted, or every package query failed after it was granted: the platform refused to answer, and
 * no figure is invented. [Available] may hold no entries, which is a real "nothing to show" answer
 * (for example an app index that has not loaded yet).
 *
 * Entries are immutable and sorted by [AppStorage.totalBytes], descending. [biggest] filters out
 * zero-byte rows, so the top list never pads itself with empty apps.
 */
sealed interface StorageResult {

    data object Unavailable : StorageResult

    data class Available(val entries: List<AppStorage>) : StorageResult {

        /** Apps with any storage at all, largest first, capped at [limit]. */
        fun biggest(limit: Int = AppStorageRepository.TopCount): List<AppStorage> =
            entries.asSequence()
                .filter { it.totalBytes > 0L }
                .take(limit.coerceAtLeast(0))
                .toList()
    }
}

/**
 * Per-app storage (T52). [read] performs one `queryStatsForPackage` per launchable package and must
 * be called off the main thread; the result is an immutable snapshot safe to publish. Nothing is
 * cached: the Apps page reads when it opens, so an uninstalled app cannot linger.
 *
 * The permission is the existing GET_USAGE_STATS app op (PACKAGE_USAGE_STATS is already declared for
 * the optional usage enrichment), so this card adds no permission.
 */
interface AppStorageRepository {

    suspend fun read(): StorageResult

    companion object {
        /** The card's "top 5". */
        const val TopCount = 5
    }
}

/**
 * Pure assembly of platform samples. Samples are already package-keyed, so the only work is the
 * launchable join, clamping (a negative counter is platform nonsense, never a credit) and the
 * deterministic order. Ties fall back to label, package and id so the list is stable across reads.
 */
internal object StorageMath {

    val Order: Comparator<AppStorage> =
        compareByDescending<AppStorage> { it.totalBytes }
            .thenBy { it.label.lowercase() }
            .thenBy { it.packageName }
            .thenBy { it.appId.value }

    fun entries(
        samples: List<PackageStorageSample>,
        appsByPackage: Map<String, AppEntry>,
    ): List<AppStorage> {
        val out = ArrayList<AppStorage>(samples.size)
        for (sample in samples) {
            val app = appsByPackage[sample.packageName] ?: continue
            out += AppStorage(
                appId = app.id,
                label = app.label,
                packageName = sample.packageName,
                appBytes = sample.appBytes.coerceAtLeast(0L),
                dataBytes = sample.dataBytes.coerceAtLeast(0L),
                cacheBytes = sample.cacheBytes.coerceAtLeast(0L),
            )
        }
        out.sortWith(Order)
        return out.toList()
    }
}
