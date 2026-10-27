package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/** T137: weather parsing from saved sample answers, cache and staleness, units and place lookup. */
class T137WeatherTest {
    private fun sample(name: String) = requireNotNull(javaClass.getResource("/weather/$name")) { name }.readText()

    @Test fun fullForecastParses() {
        val f = WeatherParser.forecast(sample("forecast.json"))!!
        assertEquals(3600, f.utcOffsetSeconds)
        val c = f.current!!
        assertEquals(LocalDateTime.of(2026, 10, 26, 9, 15), c.time)
        assertEquals(17.4, c.tempC!!, 0.0)
        assertEquals(16.1, c.feelsC!!, 0.0)
        assertEquals(2, c.code)
        assertEquals(13.7, c.windKmh!!, 0.0)
        assertTrue(c.isDay)
        assertEquals(8, f.hours.size)
        assertEquals(55, f.hours[4].rainPercent)
        assertEquals(5, f.days.size)
        assertEquals(LocalDateTime.of(2026, 10, 26, 7, 32), f.days[0].sunrise)
        assertEquals(1.5, f.days[4].minC!!, 0.0)
    }

    /** Real answers saved from Open-Meteo on 2026-10-26 (Lisbon), so the parser matches the live shape. */
    @Test fun liveAnswersSavedFromOpenMeteoParse() {
        val f = WeatherParser.forecast(sample("live-forecast-2026-10-26.json"))!!
        assertNotNull(f.current!!.tempC)
        assertNotNull(f.current!!.code)
        assertEquals(120, f.hours.size)
        assertEquals(5, f.days.size)
        assertNotNull(f.days[0].sunrise)
        val places = WeatherParser.places(sample("live-geocoding-2026-10-26.json"))!!
        assertEquals("Lisbon", places.first().name)
        assertTrue(places.first().detail.endsWith("Portugal"))
    }

    @Test fun missingAndMalformedFieldsBecomeBlanksNotCrashes() {
        val f = WeatherParser.forecast(sample("forecast-missing.json"))!!
        assertEquals(0, f.utcOffsetSeconds)
        val c = f.current!!
        assertNull(c.tempC); assertNull(c.feelsC); assertNull(c.windKmh)
        assertEquals(3, c.code)
        assertFalse(c.isDay)
        // The bad time is left out; values at missing positions are null.
        assertEquals(2, f.hours.size)
        assertNull(f.hours[0].tempC)
        assertNull(f.hours[1].tempC)
        assertNull(f.hours[0].code)
        assertEquals(1, f.days.size)
        assertEquals(20.1, f.days[0].maxC!!, 0.0)
        assertNull(f.days[0].minC); assertNull(f.days[0].sunrise); assertNull(f.days[0].rainPercent)
    }

    @Test fun errorsAndJunkAreNotForecasts() {
        assertNull(WeatherParser.forecast(sample("forecast-error.json")))
        assertNull(WeatherParser.forecast(""))
        assertNull(WeatherParser.forecast("<html>busy</html>"))
        assertNull(WeatherParser.forecast("[1,2,3]"))
        assertNull(WeatherParser.forecast("{}"))
    }

    @Test fun geocodingKeepsOnlyUsablePlaces() {
        val places = WeatherParser.places(sample("geocoding.json"))!!
        assertEquals(2, places.size)
        assertEquals(WeatherPlace("Lisbon", "Portugal", 38.71667, -9.13333), places[0])
        assertEquals("Maine, United States", places[1].detail)
        assertEquals(emptyList<WeatherPlace>(), WeatherParser.places(sample("geocoding-none.json")))
        assertNull(WeatherParser.places("not json"))
        assertNull(WeatherParser.places(sample("forecast-error.json")))
    }

    @Test fun requestsSendOnlyRoundedCoordinates() {
        val place = WeatherPlace("Lisbon", "Portugal", 38.716671, -9.133333)
        val url = OpenMeteo.forecastUrl(place)
        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?latitude=38.72&longitude=-9.13&"))
        assertFalse(url.contains("Lisbon")); assertFalse(url.contains("Portugal")); assertFalse(url.contains("38.7166"))
        assertEquals("38.72,-9.13", place.key)
        assertTrue(OpenMeteo.geocodingUrl(" São Paulo ").startsWith("https://geocoding-api.open-meteo.com/v1/search?name=S%C3%A3o+Paulo&"))
        assertEquals(setOf("api.open-meteo.com", "geocoding-api.open-meteo.com"), OpenMeteo.Hosts)
    }

    @Test fun refreshAtMostEveryThirtyMinutes() {
        val min = 60_000L
        val now = 1_000_000_000L
        // Nothing saved yet: fetch at once; a failure may retry after 5 minutes.
        assertTrue(WeatherRefresh.shouldFetch(now, null, null, false))
        assertFalse(WeatherRefresh.shouldFetch(now, now - 4 * min, null, false))
        assertTrue(WeatherRefresh.shouldFetch(now, now - 5 * min, null, false))
        // A saved forecast for this place: wait 30 minutes after the last try and the last answer.
        assertFalse(WeatherRefresh.shouldFetch(now, now - 29 * min, now - 29 * min, true))
        assertFalse(WeatherRefresh.shouldFetch(now, now - 10 * min, now - 60 * min, true))
        assertTrue(WeatherRefresh.shouldFetch(now, now - 30 * min, now - 30 * min, true))
        // A forecast for another place does not count.
        assertTrue(WeatherRefresh.shouldFetch(now, null, now - min, false))
        // A clock moved backwards does not freeze the forecast forever.
        assertTrue(WeatherRefresh.shouldFetch(now, now + 60 * min, now + 60 * min, true))
    }

    @Test fun stalenessLabels() {
        val min = 60_000L
        assertEquals("Updated just now", WeatherRefresh.updatedLabel(0, 30_000))
        assertEquals("Updated 14 min ago", WeatherRefresh.updatedLabel(0, 14 * min))
        assertEquals("Updated 2 h ago", WeatherRefresh.updatedLabel(0, 150 * min))
        assertEquals("Updated 1 day ago", WeatherRefresh.updatedLabel(0, 30 * 60 * min))
        assertEquals("Updated 3 days ago", WeatherRefresh.updatedLabel(0, 3 * 24 * 60 * min))
        assertEquals("Updated just now", WeatherRefresh.updatedLabel(10 * min, 0))
        assertFalse(WeatherRefresh.isOld(0, 179 * min))
        assertTrue(WeatherRefresh.isOld(0, 180 * min))
    }

    @Test fun cacheMatchesOnlyTheChosenPlace() {
        val place = WeatherPlace("Lisbon", "", 38.72, -9.14)
        assertTrue(WeatherSaved(place, cacheBody = "{}", cachedAt = 1, cachePlace = place.key).cacheMatchesPlace)
        assertFalse(WeatherSaved(place, cacheBody = "{}", cachedAt = 1, cachePlace = "1.00,1.00").cacheMatchesPlace)
        assertFalse(WeatherSaved(null, cacheBody = "{}", cachedAt = 1, cachePlace = place.key).cacheMatchesPlace)
        assertFalse(WeatherSaved(place, cacheBody = null, cachedAt = 1, cachePlace = place.key).cacheMatchesPlace)
    }

    @Test fun unitsFollowTheRegionUnlessChosen() {
        assertEquals(TempUnit.Fahrenheit, WeatherUnits.resolve(UnitChoice.Auto, Locale.US))
        assertEquals(TempUnit.Celsius, WeatherUnits.resolve(UnitChoice.Auto, Locale("pt", "PT")))
        assertEquals(TempUnit.Celsius, WeatherUnits.resolve(UnitChoice.Auto, Locale.ENGLISH))
        assertEquals(TempUnit.Celsius, WeatherUnits.resolve(UnitChoice.Celsius, Locale.US))
        assertEquals(TempUnit.Fahrenheit, WeatherUnits.resolve(UnitChoice.Fahrenheit, Locale.UK))
        assertEquals("17°", WeatherUnits.degrees(17.4, TempUnit.Celsius))
        assertEquals("63°", WeatherUnits.degrees(17.4, TempUnit.Fahrenheit))
        assertEquals("-4°", WeatherUnits.degrees(-4.4, TempUnit.Celsius))
        assertEquals("0°", WeatherUnits.degrees(-0.4, TempUnit.Celsius))
        assertEquals("32°", WeatherUnits.degrees(0.0, TempUnit.Fahrenheit))
        assertEquals("–", WeatherUnits.degrees(null, TempUnit.Celsius))
        assertEquals("14 km/h", WeatherUnits.wind(13.7, TempUnit.Celsius))
        assertEquals("9 mph", WeatherUnits.wind(13.7, TempUnit.Fahrenheit))
        assertEquals(UnitChoice.Auto, UnitChoice.decode(null))
        assertEquals(UnitChoice.Auto, UnitChoice.decode("kelvin"))
        assertEquals(UnitChoice.Fahrenheit, UnitChoice.decode("f"))
    }

    @Test fun viewUsesCurrentWhileFreshAndTheHourAfterwards() {
        val f = WeatherParser.forecast(sample("forecast.json"))!!
        // 09:20 local (UTC+1) = 08:20 UTC.
        val fresh = WeatherPresentation.view(f, Instant.parse("2026-10-26T08:20:00Z"))
        assertEquals(17.4, fresh.tempC!!, 0.0)
        assertEquals(16.1, fresh.feelsC!!, 0.0)
        assertEquals(LocalDateTime.of(2026, 10, 26, 9, 0), fresh.hours.first().time)
        assertEquals(6, fresh.hours.size)
        assertEquals(LocalDate.of(2026, 10, 26), fresh.today!!.date)
        assertEquals(60, fresh.rainPercent)
        // Four hours later the saved current reading is not shown as "now".
        val later = WeatherPresentation.view(f, Instant.parse("2026-10-26T12:10:00Z"))
        assertEquals(20.0, later.tempC!!, 0.0)
        assertNull(later.feelsC)
        assertEquals(61, later.code)
        assertEquals(LocalDateTime.of(2026, 10, 26, 13, 0), later.hours.first().time)
        // Next day: today's row moves on.
        val tomorrow = WeatherPresentation.view(f, Instant.parse("2026-10-27T10:00:00Z"))
        assertEquals(LocalDate.of(2026, 10, 27), tomorrow.today!!.date)
        assertEquals(4, tomorrow.days.size)
        assertTrue(tomorrow.hours.isEmpty())
    }

    @Test fun weatherCodesHaveWordsAndShapes() {
        assertEquals(WeatherKind.Clear, WeatherCodes.kind(0))
        assertEquals(WeatherKind.Rain, WeatherCodes.kind(61))
        assertEquals(WeatherKind.Thunder, WeatherCodes.kind(99))
        assertEquals(WeatherKind.Unknown, WeatherCodes.kind(null))
        assertEquals("Partly cloudy", WeatherCodes.text(2))
        assertEquals("Weather", WeatherCodes.text(1234))
    }

    @Test fun errorsReadCalmly() {
        WeatherProblem.entries.forEach {
            assertTrue(WeatherMessages.problem(it, true).endsWith("."))
            assertFalse(WeatherMessages.problem(it, false).contains("!"))
        }
        assertTrue(WeatherMessages.problem(WeatherProblem.Offline, true).contains("saved forecast"))
    }

    @Test fun pageIsListedHiddenAndInsertedAfterClockForOldConfigs() {
        assertTrue(WeatherVariant.Available)
        assertEquals(1, HubCatalog.pages.indexOf("weather"))
        assertTrue("weather" in hubTemplates && "weather" in hubPageOptions)
        assertFalse("weather" in HubConfig().pages)
        val old = "clock:true::;system:true:battery:;power:true:charging:"
        val config = HubConfig.decode(old)
        assertEquals("weather", config.orderedPages[1])
        assertTrue("weather" in config.hiddenPages)
        assertTrue("weather" in HubConfig.decode(config.showPage("weather", true).encode()).pages)
    }

    @Test fun demoUsesAFixedSampleWithoutNetwork() {
        assertTrue("weather" in DemoHubPages)
        val now = LocalDateTime.of(2026, 10, 26, 9, 40)
        val f = WeatherDemo.forecast(now)
        val view = WeatherPresentation.view(f, Instant.parse("2026-10-26T09:40:00Z"))
        assertEquals(17.0, view.tempC!!, 0.0)
        assertEquals(5, view.days.size)
        assertEquals(LocalDateTime.of(2026, 10, 26, 9, 0), view.hours.first().time)
    }
}
