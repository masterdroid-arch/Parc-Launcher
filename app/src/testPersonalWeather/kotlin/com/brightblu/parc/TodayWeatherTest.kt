package com.brightblu.parc

import java.time.Instant
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class TodayWeatherTest {
    private val now = Instant.parse("2026-10-26T08:15:00Z")
    private val home = WeatherPlace("Home", "", 38.72, -9.14)
    private val away = WeatherPlace("Away", "", 46.35, 48.04)
    private val body = requireNotNull(javaClass.getResource("/weather/forecast.json")).readText()
    private fun book() = WeatherBook(listOf(home, away), selectedKey = away.key, units = UnitChoice.Celsius,
        caches = mapOf(home.key to PlaceCache(body, now.toEpochMilli(), null)))

    @Test fun homeCacheShowsNowAndHighLowWithChosenUnitsEvenWhenAwaySelected() {
        val data = todayWeather(book(), now, Locale.UK)!!
        assertEquals("Home", data.place)
        assertEquals("17°", data.now)
        assertFalse(data.old)
        assertTrue(TodayLines.weather(data).contains("high ${data.high} · low ${data.low}"))
        assertEquals("63°", todayWeather(book().copy(units = UnitChoice.Fahrenheit), now, Locale.UK)!!.now)
    }
    @Test fun emptyBrokenOrOutOfDateForecastHasNothingToSay() {
        assertNull(todayWeather(WeatherBook(), now, Locale.UK))
        assertNull(todayWeather(book().copy(caches = emptyMap()), now, Locale.UK))
        assertNull(todayWeather(book().copy(caches = mapOf(home.key to PlaceCache("broken", 0, null))), now, Locale.UK))
        assertNull(todayWeather(book(), now.plusSeconds(10 * 86400), Locale.UK))
    }
    @Test fun oldCacheIsLabeledAndTimestampFreeCurrentCannotPretendToBeNow() {
        val old = book().copy(caches = mapOf(home.key to PlaceCache(body, now.minusSeconds(4 * 3600).toEpochMilli(), null)))
        assertTrue(todayWeather(old, now, Locale.UK)!!.old)
        assertTrue(TodayLines.weather(todayWeather(old, now, Locale.UK)!!).endsWith("old forecast"))
        val noTime = """{"utc_offset_seconds":0,"current":{"temperature_2m":20},"daily":{"time":["2026-10-26"],"temperature_2m_max":[22],"temperature_2m_min":[15]}}"""
        assertNull(todayWeather(book().copy(caches = mapOf(home.key to PlaceCache(noTime, now.toEpochMilli(), null))), now, Locale.UK))
    }
}
