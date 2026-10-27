package com.brightblu.parc.core.data.network

import android.Manifest
import android.annotation.SuppressLint
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import com.brightblu.parc.core.data.stats.StatsParsers
import com.brightblu.parc.core.data.usage.UsageAccess

/**
 * Production [NetworkDataProbe] over public APIs.
 *
 * `NetworkStatsManager.querySummaryForDevice` gives the whole-device totals and `querySummary`
 * gives per-uid buckets; both require the GET_USAGE_STATS app op and are called with a null
 * subscriber id, which returns all subscribers. The raw `NetworkStats` cursor is always closed.
 *
 * Wi-Fi RSSI/link speed need the normal ACCESS_WIFI_STATE permission already declared by T32.
 * Mobile network type needs READ_PHONE_STATE (a runtime permission Parc does not declare and this
 * card may not add), so [mobileNetworkType] checks the grant and returns null, which the page shows
 * as unavailable rather than guessing.
 *
 * Every path returns null on refusal or failure; nothing here throws.
 */
internal class AndroidNetworkDataProbe(context: Context) : NetworkDataProbe {

    private val appContext = context.applicationContext
    private val packageManager: PackageManager = appContext.packageManager
    private val statsManager: NetworkStatsManager? =
        appContext.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager

    override fun usageAccessGranted(): Boolean = UsageAccess.granted(appContext)

    override fun deviceTotals(
        transport: NetworkTransport,
        startMillis: Long,
        endMillis: Long,
    ): NetworkBytes? {
        if (endMillis <= startMillis || !usageAccessGranted()) return null
        val manager = statsManager ?: return null
        return try {
            val bucket = manager.querySummaryForDevice(
                networkType(transport),
                null,
                startMillis,
                endMillis,
            )
            NetworkBytes(rxBytes = bucket.rxBytes, txBytes = bucket.txBytes)
        } catch (unexpected: Throwable) {
            null
        }
    }

    override fun perUidTotals(
        transport: NetworkTransport,
        startMillis: Long,
        endMillis: Long,
    ): List<UidBytes>? {
        if (endMillis <= startMillis || !usageAccessGranted()) return null
        val manager = statsManager ?: return null
        val stats = try {
            manager.querySummary(networkType(transport), null, startMillis, endMillis)
        } catch (unexpected: Throwable) {
            return null
        } ?: return null

        return try {
            val bucket = android.app.usage.NetworkStats.Bucket()
            val out = ArrayList<UidBytes>()
            while (stats.hasNextBucket()) {
                if (!stats.getNextBucket(bucket)) break
                out += UidBytes(
                    uid = bucket.uid,
                    packageName = packageForUid(bucket.uid),
                    rxBytes = bucket.rxBytes,
                    txBytes = bucket.txBytes,
                )
            }
            out
        } catch (unexpected: Throwable) {
            null
        } finally {
            try {
                stats.close()
            } catch (unexpected: Throwable) {
                // Closing a cursor the platform already dropped is not an error worth reporting.
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun wifiLink(): WifiLinkSample? {
        val granted = appContext.checkSelfPermission(Manifest.permission.ACCESS_WIFI_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val manager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val info = try {
            manager.connectionInfo
        } catch (unexpected: Throwable) {
            return null
        } ?: return null
        return WifiLinkSample(
            rssiDbm = StatsParsers.wifiRssiDbm(info.rssi),
            linkSpeedMbps = StatsParsers.wifiLinkSpeedMbps(
                txMbps = info.txLinkSpeedMbps,
                rxMbps = info.rxLinkSpeedMbps,
            ),
        )
    }

    // The grant is checked on the line below; lint cannot see a checkSelfPermission guard that is
    // split from the call by a line comment. READ_BASIC_PHONE_STATE (API 33) is not declared either,
    // so on Android 13 the read stays unavailable exactly as on 34.
    @SuppressLint("MissingPermission")
    override fun mobileNetworkType(): Int? {
        // READ_PHONE_STATE is not declared: the device's RAT therefore stays unavailable
        // (T51 report).
        if (appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val manager = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return null
        return try {
            manager.dataNetworkType
        } catch (unexpected: Throwable) {
            null
        }
    }

    /**
     * `getNameForUid` returns "package" or "package:process" for a known uid. Package visibility can
     * hide a package, in which case it returns null and the aggregation drops the row.
     */
    private fun packageForUid(uid: Int): String? = try {
        packageManager.getNameForUid(uid)?.substringBefore(':')?.takeIf { it.isNotBlank() }
    } catch (unexpected: Throwable) {
        null
    }

    private fun networkType(transport: NetworkTransport): Int = when (transport) {
        NetworkTransport.Wifi -> android.net.ConnectivityManager.TYPE_WIFI
        NetworkTransport.Mobile -> android.net.ConnectivityManager.TYPE_MOBILE
    }
}
