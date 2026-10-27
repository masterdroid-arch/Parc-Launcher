package com.brightblu.parc.core.data.network

import com.brightblu.parc.model.AppId

/** Bytes counted today for one transport. Values are non-negative byte counts. */
data class DataTotals(val rxBytes: Long, val txBytes: Long) {
    val totalBytes: Long get() = rxBytes + txBytes
}

/**
 * One launchable app's today traffic. [appId] is the deterministic component-level id chosen by
 * the same one-entry-per-package rule as T47; a package with several activities is attributed to
 * its smallest id. Immutable and non-null.
 */
data class AppDataUsage(
    val appId: AppId,
    val label: String,
    val packageName: String,
    val rxBytes: Long,
    val txBytes: Long,
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

/**
 * Today's device data usage. [Unavailable] means the GET_USAGE_STATS app op is not granted or the
 * platform refused the query; that is not the same as a real zero and must show the "Grant usage
 * access" affordance rather than "0 B".
 *
 * In [Available], each field can still be null when its own query failed: a device without a
 * cellular radio can report Wi-Fi bytes while the mobile query is unavailable, and vice versa.
 * [topApps] is null when both per-uid queries failed; an empty list is a real "nothing used".
 * The list is sorted by total bytes, descending, capped at [NetworkStatsRepository.TopCount].
 */
sealed interface TodayDataUsage {

    data object Unavailable : TodayDataUsage

    data class Available(
        val wifi: DataTotals?,
        val mobile: DataTotals?,
        val topApps: List<AppDataUsage>?,
    ) : TodayDataUsage
}

/** Mobile radio generation. A missing or unknown type is null, not a fabricated "4G". */
enum class MobileNetworkType { Nr, Lte, Hspap, Hspa, Umts, Gsm, Cdma, IWlan }

/**
 * Live connection facts for the Network page. Wi-Fi RSSI and link speed need only the normal
 * ACCESS_WIFI_STATE permission; mobile type needs READ_PHONE_STATE and is therefore null on this
 * build, which declares no telephone permission (T51 report).
 */
data class NetworkLink(
    val wifiRssiDbm: Int? = null,
    val wifiLinkSpeedMbps: Int? = null,
    val mobileNetworkType: MobileNetworkType? = null,
)

/**
 * One immutable Network-page snapshot. [today] carries the usage-access-gated figures; [link]
 * stays available without that grant so the page can still show Wi-Fi signal and speed.
 */
data class NetworkUsageStats(
    val today: TodayDataUsage,
    val link: NetworkLink = NetworkLink(),
)

/**
 * Read-only today's-data source for T51. [read] performs the `NetworkStatsManager` queries and
 * must be called off the main thread; it does no caching, so every page open sees a fresh answer.
 * The Network page decides when to refresh.
 */
interface NetworkStatsRepository {

    suspend fun read(): NetworkUsageStats

    companion object {
        /** The card's "top 5 apps by data". */
        const val TopCount = 5
    }
}
