package com.brightblu.parc.core.data.network

import android.content.Context
import android.telephony.TelephonyManager
import com.brightblu.parc.core.data.apps.ScreenTimeWindows
import com.brightblu.parc.model.AppEntry
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production [NetworkStatsRepository]. Reads the day from local midnight to now, calls each
 * transport separately so one failure cannot erase the other, and joins per-uid buckets to the
 * installed launchable apps supplied by [installed] (normally `appIndex.apps.value`).
 *
 * The link readings do not need usage access, so they are collected even when [TodayDataUsage] is
 * [TodayDataUsage.Unavailable]. That lets the Network page show Wi-Fi signal next to a graceful
 * "Grant usage access" row instead of an empty page.
 *
 * The clock, zone and installed list are injected, so the window maths and the join are unit-tested
 * without a device. Nothing is cached: the caller decides when to refresh.
 */
class AndroidNetworkStatsRepository internal constructor(
    private val probe: NetworkDataProbe,
    private val installed: () -> List<AppEntry>,
    private val ownPackage: String,
    private val clock: () -> Long,
    private val zone: () -> ZoneId,
    private val ioDispatcher: CoroutineDispatcher,
) : NetworkStatsRepository {

    override suspend fun read(): NetworkUsageStats = withContext(ioDispatcher) {
        val link = readLink()
        val granted = guarded { probe.usageAccessGranted() } ?: false
        if (!granted) {
            return@withContext NetworkUsageStats(
                today = TodayDataUsage.Unavailable,
                link = link,
            )
        }

        val now = clock()
        val start = ScreenTimeWindows.startOfToday(now, zone())
        // querySummary rejects an empty range; at exactly local midnight a one-millisecond range
        // is the smallest honest window.
        val end = if (now > start) now else start + 1L

        val wifi = guarded { probe.deviceTotals(NetworkTransport.Wifi, start, end) }
            ?.let(NetworkDataMath::totals)
        val mobile = guarded { probe.deviceTotals(NetworkTransport.Mobile, start, end) }
            ?.let(NetworkDataMath::totals)
        val wifiUids = guarded { probe.perUidTotals(NetworkTransport.Wifi, start, end) }
        val mobileUids = guarded { probe.perUidTotals(NetworkTransport.Mobile, start, end) }
        val topApps = if (wifiUids == null && mobileUids == null) {
            null
        } else {
            NetworkDataMath.topApps(
                wifi = wifiUids,
                mobile = mobileUids,
                installed = installedSnapshot(),
                ownPackage = ownPackage,
                limit = NetworkStatsRepository.TopCount,
            )
        }

        NetworkUsageStats(
            today = TodayDataUsage.Available(
                wifi = wifi,
                mobile = mobile,
                topApps = topApps,
            ),
            link = link,
        )
    }

    private fun readLink(): NetworkLink {
        val wifi = guarded { probe.wifiLink() }
        val mobile = guarded { probe.mobileNetworkType() }
        return NetworkLink(
            wifiRssiDbm = wifi?.rssiDbm,
            wifiLinkSpeedMbps = wifi?.linkSpeedMbps,
            mobileNetworkType = MobileNetworkTypes.fromRaw(mobile),
        )
    }

    private fun installedSnapshot(): List<AppEntry> = try {
        installed()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (unexpected: Throwable) {
        emptyList()
    }

    private inline fun <T> guarded(read: () -> T?): T? = try {
        read()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (unexpected: Throwable) {
        null
    }

    companion object {
        /** Builds the production repository. [installed] is normally `appIndex.apps.value`. */
        fun create(
            context: Context,
            installed: () -> List<AppEntry>,
        ): AndroidNetworkStatsRepository = AndroidNetworkStatsRepository(
            probe = AndroidNetworkDataProbe(context.applicationContext),
            installed = installed,
            ownPackage = context.packageName,
            clock = System::currentTimeMillis,
            zone = { ZoneId.systemDefault() },
            ioDispatcher = Dispatchers.IO,
        )
    }
}

/**
 * Maps `TelephonyManager.NETWORK_TYPE_*` to the small set of generations the Network page shows.
 * Unknown values map to null instead of inventing a label. The constants are compile-time ints, so
 * the mapping is unit-testable.
 */
internal object MobileNetworkTypes {

    fun fromRaw(raw: Int?): MobileNetworkType? = when (raw) {
        TelephonyManager.NETWORK_TYPE_NR -> MobileNetworkType.Nr
        TelephonyManager.NETWORK_TYPE_LTE -> MobileNetworkType.Lte
        TelephonyManager.NETWORK_TYPE_HSPAP -> MobileNetworkType.Hspap
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA -> MobileNetworkType.Hspa
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> MobileNetworkType.Umts
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_GSM,
        TelephonyManager.NETWORK_TYPE_IDEN -> MobileNetworkType.Gsm
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_1xRTT -> MobileNetworkType.Cdma
        TelephonyManager.NETWORK_TYPE_IWLAN -> MobileNetworkType.IWlan
        else -> null
    }
}
