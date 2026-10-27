package com.brightblu.parc.core.data.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two stored focus documents (T72): round trip, tolerance and the duplicate-name rule. */
class FocusJsonTest {

    private val work = mode(
        name = "Work",
        schedule = schedule(
            days = setOf(FocusDay.Mon, FocusDay.Tue, FocusDay.Wed, FocusDay.Thu, FocusDay.Fri),
            startHour = 9,
            endHour = 17,
        ),
        hidden = setOf("com.social.app", "com.news.app"),
        silenceBadges = true,
    )

    private val night = mode(
        name = "Night",
        schedule = schedule(days = setOf(FocusDay.Mon), startHour = 22, endHour = 7),
        hidden = emptySet(),
    )

    @Test
    fun `a mode list round trips in order`() {
        val modes = listOf(work, night)

        val decoded = FocusJson.decodeModes(FocusJson.encodeModes(modes))

        assertEquals(modes, decoded)
        assertEquals(listOf("Work", "Night"), decoded.map { it.name })
        assertEquals(work.schedule, decoded[0].schedule)
        assertEquals(work.hiddenPackages, decoded[0].hiddenPackages)
        assertTrue(decoded[0].silenceBadges)
    }

    @Test
    fun `an empty list round trips as empty`() {
        assertEquals("[]", FocusJson.encodeModes(emptyList()))
        assertEquals(emptyList<FocusMode>(), FocusJson.decodeModes(FocusJson.encodeModes(emptyList())))
    }

    @Test
    fun `a missing or blank document reads as no modes`() {
        assertEquals(emptyList<FocusMode>(), FocusJson.decodeModes(null))
        assertEquals(emptyList<FocusMode>(), FocusJson.decodeModes(""))
        assertEquals(emptyList<FocusMode>(), FocusJson.decodeModes("   "))
    }

    @Test
    fun `a corrupt document reads as no modes instead of throwing`() {
        assertEquals(emptyList<FocusMode>(), FocusJson.decodeModes("{not json"))

        assertTrue(
            "a document with an invalid minute must be refused as a whole",
            FocusJson.decodeModes(
                """[{"name":"Work","schedule":{"days":["Mon"],"startMinute":-5,"endMinute":60}}]""",
            ).isEmpty(),
        )
        assertTrue(
            FocusJson.decodeModes(
                """[{"name":"Work","schedule":{"days":["Funday"],"startMinute":0,"endMinute":60}}]""",
            ).isEmpty(),
        )
        assertTrue(
            FocusJson.decodeModes(
                """[{"name":"","schedule":{"days":[],"startMinute":0,"endMinute":0}}]""",
            ).isEmpty(),
        )
    }

    @Test
    fun `unknown keys are ignored so a newer build can add a field`() {
        val decoded = FocusJson.decodeModes(
            """
            [{"name":"Work","hiddenPackages":[],"silenceBadges":false,"futureFlag":7,
              "schedule":{"days":["Mon"],"startMinute":540,"endMinute":1020,"enabled":true,
                          "futureScheduleFlag":"x"}}]
            """.trimIndent(),
        )

        assertEquals(1, decoded.size)
        assertEquals("Work", decoded[0].name)
        assertEquals(setOf(FocusDay.Mon), decoded[0].schedule.days)
    }

    @Test
    fun `duplicate names collapse to the first occurrence`() {
        val first = mode("Work", hidden = setOf("com.first"))
        val second = mode("Work", hidden = setOf("com.second"))

        val decoded = FocusJson.decodeModes(FocusJson.encodeModes(listOf(first, second)))

        assertEquals(1, decoded.size)
        assertEquals(setOf("com.first"), decoded[0].hiddenPackages)
    }

    @Test
    fun `missing fields fall back to the documented defaults`() {
        val decoded = FocusJson.decodeModes(
            """[{"name":"Work","schedule":{"days":["Mon"],"startMinute":540,"endMinute":1020}}]""",
        )

        assertEquals(1, decoded.size)
        assertEquals(emptySet<String>(), decoded[0].hiddenPackages)
        assertEquals(false, decoded[0].silenceBadges)
        assertTrue(decoded[0].schedule.enabled)
    }

    @Test
    fun `an override round trips both directions`() {
        val on = FocusOverride("Work", on = true, untilMillis = 1_700_000_000_000L)
        val off = FocusOverride("Night", on = false, untilMillis = null)

        assertEquals(on, FocusJson.decodeOverride(FocusJson.encodeOverride(on)))
        assertEquals(off, FocusJson.decodeOverride(FocusJson.encodeOverride(off)))
    }

    @Test
    fun `a missing or corrupt override reads as none`() {
        assertNull(FocusJson.decodeOverride(null))
        assertNull(FocusJson.decodeOverride(""))
        assertNull(FocusJson.decodeOverride("[]"))
        assertNull(FocusJson.decodeOverride("""{"modeName":"","on":true}"""))
        assertNull(FocusJson.decodeOverride("""{"modeName":"Work","on":true,"untilMillis":0}"""))
    }

    @Test
    fun `an unknown override key is tolerated`() {
        val decoded = FocusJson.decodeOverride(
            """{"modeName":"Work","on":true,"untilMillis":42,"future":"x"}""",
        )

        assertEquals(FocusOverride("Work", on = true, untilMillis = 42L), decoded)
    }
}
