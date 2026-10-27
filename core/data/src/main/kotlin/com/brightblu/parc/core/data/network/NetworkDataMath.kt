package com.brightblu.parc.core.data.network

import com.brightblu.parc.core.data.apps.AppPackages
import com.brightblu.parc.model.AppEntry

/**
 * Pure aggregation for T51. It takes the probe's neutral per-uid buckets, sums them per package
 * (so a package using several uids is not split), joins the package to a launchable [AppEntry] for
 * its label, and returns the top apps by total bytes.
 *
 * Rules that keep the number honest:
 * - negative byte counts are clamped to zero rather than subtracted;
 * - zero-byte packages are omitted (they used no data, so they are not a top app);
 * - packages without a launchable label, and Parc itself, are omitted; the page shows apps, not
 *   device-wide totals under a package name;
 * - ties sort by label (case-insensitive) and then by package, so the order is stable.
 */
internal object NetworkDataMath {

    fun totals(bytes: NetworkBytes?): DataTotals? = bytes?.let {
        DataTotals(
            rxBytes = it.rxBytes.coerceAtLeast(0L),
            txBytes = it.txBytes.coerceAtLeast(0L),
        )
    }

    fun topApps(
        wifi: List<UidBytes>?,
        mobile: List<UidBytes>?,
        installed: List<AppEntry>,
        ownPackage: String,
        limit: Int = NetworkStatsRepository.TopCount,
    ): List<AppDataUsage> {
        val byPackage = LinkedHashMap<String, LongArray>()
        addTransport(byPackage, wifi)
        addTransport(byPackage, mobile)

        val known = AppPackages.byPackage(installed, ownPackage)
        val out = ArrayList<AppDataUsage>(byPackage.size)
        for ((packageName, totals) in byPackage) {
            val rx = totals[0]
            val tx = totals[1]
            if (rx + tx <= 0L) continue
            val app = known[packageName] ?: continue
            out += AppDataUsage(
                appId = app.id,
                label = app.label,
                packageName = packageName,
                rxBytes = rx,
                txBytes = tx,
            )
        }
        return out.sortedWith(TOP_ORDER).take(limit.coerceAtLeast(0))
    }

    private fun addTransport(
        byPackage: MutableMap<String, LongArray>,
        buckets: List<UidBytes>?,
    ) {
        if (buckets == null) return
        for (bucket in buckets) {
            val packageName = bucket.packageName?.takeIf { it.isNotBlank() } ?: continue
            val rx = bucket.rxBytes.coerceAtLeast(0L)
            val tx = bucket.txBytes.coerceAtLeast(0L)
            if (rx == 0L && tx == 0L) continue
            val totals = byPackage.getOrPut(packageName) { LongArray(2) }
            totals[0] += rx
            totals[1] += tx
        }
    }

    private val TOP_ORDER: Comparator<AppDataUsage> =
        compareByDescending<AppDataUsage> { it.totalBytes }
            .thenBy { it.label.lowercase() }
            .thenBy { it.packageName }
}
