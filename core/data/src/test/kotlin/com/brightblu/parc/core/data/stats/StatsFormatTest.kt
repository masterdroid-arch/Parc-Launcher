package com.brightblu.parc.core.data.stats

import org.junit.Assert.assertEquals
import org.junit.Test

/** Formatter tests; every formatter must show the unavailable token for a null value. */
class StatsFormatTest {

    @Test
    fun `byte rates use decimal units and one fraction digit`() {
        assertEquals("0 B/s", StatsFormat.bytesPerSecond(0L))
        assertEquals("999 B/s", StatsFormat.bytesPerSecond(999L))
        assertEquals("1.0 KB/s", StatsFormat.bytesPerSecond(1_000L))
        assertEquals("1.5 KB/s", StatsFormat.bytesPerSecond(1_536L))
        assertEquals("1.2 MB/s", StatsFormat.bytesPerSecond(1_234_567L))
        assertEquals("2.0 GB/s", StatsFormat.bytesPerSecond(2_000_000_000L))
    }

    @Test
    fun `byte rates keep the sign and show the unavailable token for null`() {
        assertEquals("-1.5 KB/s", StatsFormat.bytesPerSecond(-1_536L))

        val noRate: Long? = null
        assertEquals(StatsFormat.Unavailable, StatsFormat.bytesPerSecond(noRate))
    }

    @Test
    fun `temperatures show one decimal and a degree sign`() {
        assertEquals("38.5°", StatsFormat.temperature(38.5f))
        assertEquals("37.0°", StatsFormat.temperature(37f))
        assertEquals("-2.0°", StatsFormat.temperature(-2f))

        val noTemperature: Float? = null
        assertEquals(StatsFormat.Unavailable, StatsFormat.temperature(noTemperature))
    }

    @Test
    fun `percentages cover the whole and fractional forms`() {
        assertEquals("45%", StatsFormat.percent(45))
        assertEquals("0%", StatsFormat.percent(0))
        assertEquals("12.4%", StatsFormat.percent(12.44f))

        val noWhole: Int? = null
        val noFraction: Float? = null
        assertEquals(StatsFormat.Unavailable, StatsFormat.percent(noWhole))
        assertEquals(StatsFormat.Unavailable, StatsFormat.percent(noFraction))
    }

    @Test
    fun `current is whole milliamperes with its sign`() {
        assertEquals("-1205 mA", StatsFormat.milliAmps(-1205f))
        assertEquals("850 mA", StatsFormat.milliAmps(850.4f))

        val noCurrent: Float? = null
        assertEquals(StatsFormat.Unavailable, StatsFormat.milliAmps(noCurrent))
    }

    @Test
    fun `cpu clocks are gigahertz with one decimal`() {
        assertEquals("1.8 GHz", StatsFormat.gigaHertz(1_800_000))
        assertEquals("2.2 GHz", StatsFormat.gigaHertz(2_200_000))
        assertEquals("0.6 GHz", StatsFormat.gigaHertz(600_000))

        val noClock: Int? = null
        assertEquals(StatsFormat.Unavailable, StatsFormat.gigaHertz(noClock))
    }

    @Test
    fun `headroom shows two decimals`() {
        assertEquals("0.42", StatsFormat.headroom(0.4213f))
        assertEquals("1.00", StatsFormat.headroom(1f))

        val noHeadroom: Float? = null
        assertEquals(StatsFormat.Unavailable, StatsFormat.headroom(noHeadroom))
    }

    @Test
    fun `uptime is days, hours, minutes or seconds`() {
        assertEquals("0s", StatsFormat.uptime(0L))
        assertEquals("45s", StatsFormat.uptime(45_000L))
        assertEquals("12m 30s", StatsFormat.uptime(750_000L))
        assertEquals("4h 12m", StatsFormat.uptime(15_120_000L))
        assertEquals("3d 4h 12m", StatsFormat.uptime(274_320_000L))
    }

    @Test
    fun `a missing or negative uptime is unavailable`() {
        val noUptime: Long? = null
        assertEquals(StatsFormat.Unavailable, StatsFormat.uptime(noUptime))
        assertEquals(StatsFormat.Unavailable, StatsFormat.uptime(-1L))
    }
}
