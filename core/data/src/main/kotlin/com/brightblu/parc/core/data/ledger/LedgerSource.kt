package com.brightblu.parc.core.data.ledger

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.brightblu.parc.core.data.apps.AndroidForegroundUsageQuery
import com.brightblu.parc.core.data.apps.ForegroundUsageQuery
import com.brightblu.parc.core.data.apps.ScreenTimeMath
import com.brightblu.parc.core.data.network.AndroidNetworkDataProbe
import com.brightblu.parc.core.data.network.NetworkDataProbe
import com.brightblu.parc.core.data.network.NetworkTransport
import com.brightblu.parc.core.data.network.UidBytes
import com.brightblu.parc.core.data.power.ScreenEvent
import com.brightblu.parc.core.data.usage.UsageAccess

/** Network answer for one time window: totals per transport (null when refused) and per package. */
internal data class WindowNetwork(
    val wifiBytes: Long?,
    val mobileBytes: Long?,
    val perPackage: Map<String, Long>,
)

/**
 * The Android boundary of the ledger. Every call returns null when the platform refuses (no usage
 * access, no service, an exception); nothing here throws. A null is "not answered", never a zero.
 */
internal interface LedgerSource {
    fun accessGranted(): Boolean

    /** Wi-Fi and mobile bytes and per-package bytes in [startMillis, endMillis). */
    fun network(startMillis: Long, endMillis: Long): WindowNetwork?

    /** Screen interactive and non-interactive events in [fromMillis, toMillis], oldest first. */
    fun screenEvents(fromMillis: Long, toMillis: Long): List<ScreenEvent>?

    /** Per-package foreground time in the window, as `UsageStatsManager` reports daily buckets. */
    fun foreground(startMillis: Long, endMillis: Long): Map<String, Long>?
}

/**
 * Production [LedgerSource] over the same probes the Network page and the Screen time tile already
 * use, plus one `queryEvents` read for screen events. Needs only the usage-access app op the pages
 * already ask for; no new permission.
 */
internal class AndroidLedgerSource(
    context: Context,
    private val network: NetworkDataProbe = AndroidNetworkDataProbe(context),
    private val foreground: ForegroundUsageQuery = AndroidForegroundUsageQuery(context.applicationContext),
) : LedgerSource {
    private val context = context.applicationContext

    override fun accessGranted(): Boolean = UsageAccess.granted(context)

    override fun network(startMillis: Long, endMillis: Long): WindowNetwork? {
        if (endMillis <= startMillis) return null
        val wifi = network.deviceTotals(NetworkTransport.Wifi, startMillis, endMillis)
        val mobile = network.deviceTotals(NetworkTransport.Mobile, startMillis, endMillis)
        if (wifi == null && mobile == null) return null
        val wifiUids = network.perUidTotals(NetworkTransport.Wifi, startMillis, endMillis)
        val mobileUids = network.perUidTotals(NetworkTransport.Mobile, startMillis, endMillis)
        return WindowNetwork(
            wifiBytes = wifi?.let { it.rxBytes.coerceAtLeast(0L) + it.txBytes.coerceAtLeast(0L) },
            mobileBytes = mobile?.let { it.rxBytes.coerceAtLeast(0L) + it.txBytes.coerceAtLeast(0L) },
            perPackage = perPackage(wifiUids, mobileUids),
        )
    }

    override fun screenEvents(fromMillis: Long, toMillis: Long): List<ScreenEvent>? {
        if (toMillis <= fromMillis || !accessGranted()) return null
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        return try {
            val usage = manager.queryEvents(fromMillis.coerceAtLeast(0L), toMillis) ?: return null
            val events = ArrayList<ScreenEvent>()
            val event = UsageEvents.Event()
            while (usage.hasNextEvent()) {
                if (!usage.getNextEvent(event)) break
                when (event.eventType) {
                    UsageEvents.Event.SCREEN_INTERACTIVE -> events += ScreenEvent(true, event.timeStamp)
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> events += ScreenEvent(false, event.timeStamp)
                }
            }
            events
        } catch (unexpected: Throwable) {
            null
        }
    }

    override fun foreground(startMillis: Long, endMillis: Long): Map<String, Long>? {
        val buckets = foreground.buckets(startMillis, endMillis) ?: return null
        return ScreenTimeMath.perPackage(buckets, startMillis, endMillis)
    }

    internal companion object {
        /** Sums both transports per package; unattributed uids and zero rows are dropped. */
        fun perPackage(wifi: List<UidBytes>?, mobile: List<UidBytes>?): Map<String, Long> {
            val out = HashMap<String, Long>()
            for (list in listOfNotNull(wifi, mobile)) {
                for (bucket in list) {
                    val pkg = bucket.packageName?.takeIf { it.isNotBlank() } ?: continue
                    val bytes = bucket.rxBytes.coerceAtLeast(0L) + bucket.txBytes.coerceAtLeast(0L)
                    if (bytes > 0L) out[pkg] = (out[pkg] ?: 0L) + bytes
                }
            }
            return out
        }
    }
}
