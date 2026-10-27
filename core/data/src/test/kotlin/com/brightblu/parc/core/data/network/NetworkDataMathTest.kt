package com.brightblu.parc.core.data.network

import com.brightblu.parc.core.data.apps.testEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T51 pure network aggregation: uid merging, package join, ordering and the top-five cap. */
class NetworkDataMathTest {

    @Test
    fun `device totals clamp negative counters to zero`() {
        val totals = NetworkDataMath.totals(NetworkBytes(rxBytes = -5L, txBytes = 12L))

        assertEquals(DataTotals(rxBytes = 0L, txBytes = 12L), totals)
        assertNull(NetworkDataMath.totals(null))
    }

    @Test
    fun `wifi and mobile buckets for the same package are summed`() {
        val app = testEntry("instagram", label = "Instagram")
        val wifi = listOf(UidBytes(10, app.packageName, rxBytes = 1_000L, txBytes = 500L))
        val mobile = listOf(
            UidBytes(10, app.packageName, rxBytes = 2_000L, txBytes = 250L),
            UidBytes(20, app.packageName, rxBytes = 500L, txBytes = 250L),
        )

        val top = NetworkDataMath.topApps(wifi, mobile, listOf(app), ownPackage = "com.brightblu.parc")

        assertEquals(1, top.size)
        assertEquals(app.id, top.single().appId)
        assertEquals(3_500L, top.single().rxBytes)
        assertEquals(1_000L, top.single().txBytes)
        assertEquals(4_500L, top.single().totalBytes)
    }

    @Test
    fun `packages are sorted by total bytes and capped at five`() {
        val apps = (1..7).map { testEntry("app$it", label = "App$it") }
        val buckets = apps.mapIndexed { index, app ->
            UidBytes(100 + index, app.packageName, rxBytes = (index + 1) * 100L, txBytes = 0L)
        }

        val top = NetworkDataMath.topApps(
            wifi = buckets,
            mobile = null,
            installed = apps,
            ownPackage = "com.brightblu.parc",
        )

        assertEquals(5, top.size)
        assertEquals(
            listOf("App7", "App6", "App5", "App4", "App3"),
            top.map { it.label },
        )
    }

    @Test
    fun `ties sort by label case-insensitively then package`() {
        val beta = testEntry("beta", label = "beta")
        val alpha = testEntry("alpha", label = "Alpha")
        val zeta = testEntry("zeta", label = "Zeta")
        val buckets = listOf(
            UidBytes(1, zeta.packageName, 100L, 0L),
            UidBytes(2, beta.packageName, 100L, 0L),
            UidBytes(3, alpha.packageName, 100L, 0L),
        )

        val top = NetworkDataMath.topApps(buckets, null, listOf(zeta, beta, alpha), "com.brightblu.parc")

        assertEquals(listOf("Alpha", "beta", "Zeta"), top.map { it.label })
    }

    @Test
    fun `unknown, unlabelled, parc and zero-byte packages are omitted`() {
        val known = testEntry("known", label = "Known")
        val parc = testEntry("parc", packageName = "com.brightblu.parc", label = "Parc")
        val buckets = listOf(
            UidBytes(1, known.packageName, 100L, 0L),
            UidBytes(2, "com.system.process", 900L, 0L),
            UidBytes(3, null, 800L, 0L),
            UidBytes(4, "", 700L, 0L),
            UidBytes(5, parc.packageName, 600L, 0L),
            UidBytes(6, known.packageName, 0L, 0L),
        )

        val top = NetworkDataMath.topApps(buckets, null, listOf(known, parc), "com.brightblu.parc")

        assertEquals(listOf("Known"), top.map { it.label })
    }

    @Test
    fun `negative buckets are clamped rather than subtracted`() {
        val app = testEntry("a", label = "A")
        val buckets = listOf(
            UidBytes(1, app.packageName, rxBytes = -100L, txBytes = 50L),
            UidBytes(2, app.packageName, rxBytes = 300L, txBytes = -100L),
        )

        val top = NetworkDataMath.topApps(buckets, null, listOf(app), "com.brightblu.parc")

        assertEquals(300L, top.single().rxBytes)
        assertEquals(50L, top.single().txBytes)
    }

    @Test
    fun `a null transport does not erase the other one`() {
        val app = testEntry("a", label = "A")
        val mobile = listOf(UidBytes(1, app.packageName, 400L, 100L))

        val top = NetworkDataMath.topApps(null, mobile, listOf(app), "com.brightblu.parc")

        assertEquals(500L, top.single().totalBytes)
    }

    @Test
    fun `no buckets at all is an empty list`() {
        assertTrue(NetworkDataMath.topApps(null, null, emptyList(), "com.brightblu.parc").isEmpty())
        assertTrue(
            NetworkDataMath.topApps(emptyList(), emptyList(), emptyList(), "com.brightblu.parc")
                .isEmpty(),
        )
    }

    @Test
    fun `a zero limit returns nothing`() {
        val app = testEntry("a", label = "A")
        val buckets = listOf(UidBytes(1, app.packageName, 100L, 0L))

        assertTrue(
            NetworkDataMath.topApps(buckets, null, listOf(app), "com.brightblu.parc", limit = 0)
                .isEmpty(),
        )
    }
}
