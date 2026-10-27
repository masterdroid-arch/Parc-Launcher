package com.brightblu.parc.core.data.network

import android.telephony.TelephonyManager
import com.brightblu.parc.core.data.apps.epoch
import com.brightblu.parc.core.data.apps.nairobi
import com.brightblu.parc.core.data.apps.testEntry
import com.brightblu.parc.model.AppEntry
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T51 network repository: grant handling, day window, per-transport nulls and the label join. */
class NetworkDataRepositoryTest {

    private class FakeProbe : NetworkDataProbe {
        var granted = true
        var throwOnGrant = false
        var wifi: WifiLinkSample? = WifiLinkSample(rssiDbm = -58, linkSpeedMbps = 433)
        var mobileType: Int? = TelephonyManager.NETWORK_TYPE_LTE
        var throwOnLink = false

        val device = mutableMapOf<NetworkTransport, NetworkBytes?>(
            NetworkTransport.Wifi to NetworkBytes(1_000L, 2_000L),
            NetworkTransport.Mobile to NetworkBytes(3_000L, 4_000L),
        )
        val perUid = mutableMapOf<NetworkTransport, List<UidBytes>?>(
            NetworkTransport.Wifi to emptyList(),
            NetworkTransport.Mobile to emptyList(),
        )
        val windows = ArrayList<Pair<NetworkTransport, Pair<Long, Long>>>()

        override fun usageAccessGranted(): Boolean {
            if (throwOnGrant) throw IllegalStateException("appops")
            return granted
        }

        override fun deviceTotals(
            transport: NetworkTransport,
            startMillis: Long,
            endMillis: Long,
        ): NetworkBytes? {
            windows += transport to (startMillis to endMillis)
            return device[transport]
        }

        override fun perUidTotals(
            transport: NetworkTransport,
            startMillis: Long,
            endMillis: Long,
        ): List<UidBytes>? {
            windows += transport to (startMillis to endMillis)
            return perUid[transport]
        }

        override fun wifiLink(): WifiLinkSample? {
            if (throwOnLink) throw IllegalStateException("wifi")
            return wifi
        }

        override fun mobileNetworkType(): Int? = mobileType
    }

    private val now = epoch(2026, 10, 17, 9, 0, nairobi)

    private fun repository(
        probe: NetworkDataProbe,
        installed: () -> List<AppEntry> = { emptyList() },
        nowMillis: Long = now,
        zone: ZoneId = nairobi,
    ): AndroidNetworkStatsRepository = AndroidNetworkStatsRepository(
        probe = probe,
        installed = installed,
        ownPackage = "com.brightblu.parc",
        clock = { nowMillis },
        zone = { zone },
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `a missing usage grant is Unavailable but link data still arrives`() = runTest {
        val probe = FakeProbe().apply { granted = false }

        val result = repository(probe).read()

        assertEquals(TodayDataUsage.Unavailable, result.today)
        assertEquals(-58, result.link.wifiRssiDbm)
        assertEquals(433, result.link.wifiLinkSpeedMbps)
        assertEquals(MobileNetworkType.Lte, result.link.mobileNetworkType)
        assertTrue(probe.windows.isEmpty())
    }

    @Test
    fun `an appop read failure is Unavailable rather than a crash`() = runTest {
        val probe = FakeProbe().apply { throwOnGrant = true }

        assertEquals(TodayDataUsage.Unavailable, repository(probe).read().today)
    }

    @Test
    fun `wifi and mobile totals are read from local midnight to now`() = runTest {
        val probe = FakeProbe()

        val result = repository(probe).read() as NetworkUsageStats
        val today = result.today as TodayDataUsage.Available

        assertEquals(DataTotals(1_000L, 2_000L), today.wifi)
        assertEquals(DataTotals(3_000L, 4_000L), today.mobile)
        val start = epoch(2026, 10, 17, 0, 0, nairobi)
        assertEquals(4, probe.windows.size)
        assertTrue(probe.windows.all { it.second == (start to now) })
    }

    @Test
    fun `per-uid buckets become labelled top apps`() = runTest {
        val probe = FakeProbe()
        val instagram = testEntry("instagram", label = "PhotoApp")
        val messenger = testEntry("messenger", label = "Messenger")
        probe.perUid[NetworkTransport.Wifi] = listOf(
            UidBytes(1, instagram.packageName, 5_000L, 1_000L),
            UidBytes(2, messenger.packageName, 2_000L, 500L),
            UidBytes(3, null, 9_000L, 9_000L),
        )

        val today = repository(probe, installed = { listOf(instagram, messenger) }).read().today
            as TodayDataUsage.Available

        assertEquals(listOf("PhotoApp", "Messenger"), today.topApps!!.map { it.label })
        assertEquals(6_000L, today.topApps!!.first().totalBytes)
    }

    @Test
    fun `a failed transport stays null while the other succeeds`() = runTest {
        val probe = FakeProbe()
        probe.device[NetworkTransport.Mobile] = null

        val today = repository(probe).read().today as TodayDataUsage.Available

        assertEquals(DataTotals(1_000L, 2_000L), today.wifi)
        assertNull(today.mobile)
        assertEquals(emptyList<AppDataUsage>(), today.topApps)
    }

    @Test
    fun `both per-uid failures leave the app list unavailable, not empty`() = runTest {
        val probe = FakeProbe()
        probe.perUid[NetworkTransport.Wifi] = null
        probe.perUid[NetworkTransport.Mobile] = null

        val today = repository(probe).read().today as TodayDataUsage.Available

        assertNull(today.topApps)
    }

    @Test
    fun `an installed-list failure yields no labelled apps but keeps the totals`() = runTest {
        val probe = FakeProbe()
        probe.perUid[NetworkTransport.Wifi] = listOf(
            UidBytes(1, "com.test.a", 500L, 0L),
        )

        val today = repository(probe, installed = { error("app index not ready") }).read().today
            as TodayDataUsage.Available

        assertEquals(emptyList<AppDataUsage>(), today.topApps)
        assertEquals(DataTotals(1_000L, 2_000L), today.wifi)
    }

    @Test
    fun `link failures are isolated from usage data`() = runTest {
        val probe = FakeProbe().apply { throwOnLink = true }

        val result = repository(probe).read()

        assertNull(result.link.wifiRssiDbm)
        assertNull(result.link.wifiLinkSpeedMbps)
        assertEquals(MobileNetworkType.Lte, result.link.mobileNetworkType)
        assertTrue(result.today is TodayDataUsage.Available)
    }

    @Test
    fun `a read exactly at midnight queries a one millisecond window`() = runTest {
        val probe = FakeProbe()
        val midnight = epoch(2026, 10, 17, 0, 0, nairobi)

        repository(probe, nowMillis = midnight).read()

        assertTrue(probe.windows.all { it.second == (midnight to midnight + 1L) })
    }

    @Test
    fun `mobile network type maps every generation and rejects unknown`() {
        assertEquals(MobileNetworkType.Nr, MobileNetworkTypes.fromRaw(TelephonyManager.NETWORK_TYPE_NR))
        assertEquals(MobileNetworkType.Lte, MobileNetworkTypes.fromRaw(TelephonyManager.NETWORK_TYPE_LTE))
        assertEquals(
            MobileNetworkType.Hspap,
            MobileNetworkTypes.fromRaw(TelephonyManager.NETWORK_TYPE_HSPAP),
        )
        assertEquals(
            MobileNetworkType.Hspa,
            MobileNetworkTypes.fromRaw(TelephonyManager.NETWORK_TYPE_HSDPA),
        )
        assertEquals(
            MobileNetworkType.Umts,
            MobileNetworkTypes.fromRaw(TelephonyManager.NETWORK_TYPE_UMTS),
        )
        assertEquals(
            MobileNetworkType.Gsm,
            MobileNetworkTypes.fromRaw(TelephonyManager.NETWORK_TYPE_EDGE),
        )
        assertEquals(
            MobileNetworkType.Cdma,
            MobileNetworkTypes.fromRaw(TelephonyManager.NETWORK_TYPE_EVDO_A),
        )
        assertEquals(
            MobileNetworkType.IWlan,
            MobileNetworkTypes.fromRaw(TelephonyManager.NETWORK_TYPE_IWLAN),
        )
        assertNull(MobileNetworkTypes.fromRaw(TelephonyManager.NETWORK_TYPE_UNKNOWN))
        assertNull(MobileNetworkTypes.fromRaw(-1))
        assertNull(MobileNetworkTypes.fromRaw(null))
    }
}
