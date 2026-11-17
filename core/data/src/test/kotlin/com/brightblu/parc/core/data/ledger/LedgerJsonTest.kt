package com.brightblu.parc.core.data.ledger

import java.time.LocalDate
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T185: the persisted document reads old, empty and newer data, and keeps what it does not know. */
class LedgerJsonTest {

    private val d = LocalDate.of(2026, 11, 3)

    @Test
    fun `an empty blank or corrupt document is an empty ledger`() {
        assertEquals(DayLedger.Empty, LedgerJson.decode(null))
        assertEquals(DayLedger.Empty, LedgerJson.decode("   "))
        assertEquals(DayLedger.Empty, LedgerJson.decode("{ not json"))
        assertEquals(DayLedger.Empty, LedgerJson.decode("[1,2]"))
        assertEquals(DayLedger.Empty, LedgerJson.decode("{}"))
    }

    @Test
    fun `a ledger round trips with every field`() {
        val ledger = DayLedger(backfilled = true).withDay(
            LedgerDay(
                date = d, wifiBytes = 10, mobileBytes = 0, screenOnMillis = 5_000, steps = 4_200,
                pickups = 12, phoneFreeMillis = 90_000, closed = true,
                appData = mapOf("a.b" to 7L), appScreen = mapOf("a.b" to 3_000L),
            ),
        ).let { it.copy(months = mapOf("2026-11" to MonthApps(mapOf("a.b" to 7L), mapOf("a.b" to 3_000L)))) }

        val decoded = LedgerJson.decode(LedgerJson.encode(ledger))

        assertEquals(ledger, decoded)
        assertEquals(d, decoded.firstDate)
    }

    @Test
    fun `missing fields are null and a zero is kept as a zero`() {
        val text = """{"v":1,"days":{"${d.toEpochDay()}":{"mobile":0}}}"""

        val day = LedgerJson.decode(text).day(d)!!

        assertNull(day.wifiBytes)
        assertEquals(0L, day.mobileBytes)
        assertNull(day.screenOnMillis)
        assertNull(day.steps)
        assertNull(day.pickups)
        assertNull(day.phoneFreeMillis)
        assertTrue(!day.closed)
        assertTrue(day.hasNetwork)
    }

    @Test
    fun `unknown fields on the document and on a day survive a round trip`() {
        val text = """{"v":9,"future":{"x":1},"days":{"${d.toEpochDay()}":{"wifi":5,"mood":"calm","sleep":[1,2]}}}"""

        val ledger = LedgerJson.decode(text)
        assertEquals(JsonPrimitive("calm"), ledger.day(d)!!.extra["mood"])
        assertTrue("future" in ledger.extra)

        val again = LedgerJson.decode(LedgerJson.encode(ledger))
        assertEquals(ledger.day(d)!!.extra, again.day(d)!!.extra)
        assertEquals(ledger.extra, again.extra)
        assertEquals(5L, again.day(d)!!.wifiBytes)
    }

    @Test
    fun `wrong typed negative and bad entries are dropped without losing the rest`() {
        val good = d.plusDays(1)
        val text = """{"days":{"abc":{"wifi":1},"${d.toEpochDay()}":{"wifi":-4,"screen":"x","steps":"9"},
            "${good.toEpochDay()}":{"wifi":2}},"months":{"bad":{"data":{"a":1}},"2026-11":{"data":{"a":5,"b":-1,"c":"x"}}}}"""

        val ledger = LedgerJson.decode(text)

        assertNull(ledger.day(d)!!.wifiBytes)
        assertNull(ledger.day(d)!!.screenOnMillis)
        assertEquals(2L, ledger.day(good)!!.wifiBytes)
        assertEquals(setOf("2026-11"), ledger.months.keys)
        assertEquals(mapOf("a" to 5L), ledger.months.getValue("2026-11").data)
    }

    @Test
    fun `the stored first date survives even when its day is gone`() {
        val first = LocalDate.of(2026, 8, 12)
        val later = d
        val text = """{"first":${first.toEpochDay()},"days":{"${later.toEpochDay()}":{"wifi":1}}}"""

        assertEquals(first, LedgerJson.decode(text).firstDate)
    }

    @Test
    fun `the backup names the stored key and a restored document decodes`() {
        val key = com.brightblu.parc.core.data.backup.BackupSpecs.ledger.key(LedgerPrefs.KeyName)
        assertNotNull(key)
        assertTrue(com.brightblu.parc.core.data.backup.BackupSpecs.all.any { it.id == "day_ledger" })
        val document = LedgerJson.encode(DayLedger().withDay(LedgerDay(d, wifiBytes = 1, closed = true)))
        assertTrue(document.length <= key!!.maxLength)
        assertEquals(1L, LedgerJson.decode(document).day(d)!!.wifiBytes)
    }
}
