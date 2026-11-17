package com.brightblu.parc

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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

/** T184: seven days, day detail, city search language, and several places. */
class T184WeatherTest {
    private fun sample(name: String) = requireNotNull(javaClass.getResource("/weather/$name")) { name }.readText()
    private val lisbon = WeatherPlace("Lisbon", "Portugal", 38.72, -9.14)
    private val faro = WeatherPlace("Faro", "Portugal", 37.02, -7.93)
    private val porto = WeatherPlace("Porto", "Portugal", 41.15, -8.61)

    /** A seven day answer shaped like Open-Meteo's, built here: 7 daily rows and 7 x 24 hourly rows. */
    private fun sevenDayAnswer(): String {
        val start = LocalDate.of(2026, 11, 10)
        val times = (0 until 168).map { "\"${start.atStartOfDay().plusHours(it.toLong()).toString().take(16)}\"" }
        val temps = (0 until 168).map { 10 + it % 5 }
        val dates = (0 until 7).map { "\"${start.plusDays(it.toLong())}\"" }
        val rises = (0 until 7).map { "\"${start.plusDays(it.toLong())}T07:30\"" }
        val sets = (0 until 7).map { "\"${start.plusDays(it.toLong())}T17:20\"" }
        return """{"utc_offset_seconds":10800,"current":{"time":"2026-11-10T09:15","temperature_2m":8.0,"weather_code":3,"is_day":1},
            "hourly":{"time":$times,"temperature_2m":$temps,"weather_code":${(0 until 168).map { 3 }},"precipitation_probability":${(0 until 168).map { 20 }}},
            "daily":{"time":$dates,"weather_code":${(0 until 7).map { 61 }},"temperature_2m_max":${(0 until 7).map { 12 + it }},
            "temperature_2m_min":${(0 until 7).map { 4 + it }},"precipitation_probability_max":${(0 until 7).map { 10 * it }},"sunrise":$rises,"sunset":$sets}}"""
    }

    @Test fun forecastAsksForSevenDaysAndTheViewShowsTodayPlusSix() {
        assertEquals(7, OpenMeteo.ForecastDays)
        val f = WeatherParser.forecast(sevenDayAnswer())!!
        assertEquals(7, f.days.size)
        assertEquals(168, f.hours.size)
        // 12:00 UTC is 15:00 local (UTC+3): the date at the place is 10 November.
        val view = WeatherPresentation.view(f, Instant.parse("2026-11-10T12:00:00Z"))
        assertEquals(7, view.days.size)
        assertEquals(LocalDate.of(2026, 11, 10), view.days.first().date)
        assertEquals(LocalDate.of(2026, 11, 16), view.days.last().date)
        assertEquals(LocalDate.of(2026, 11, 10), view.localDate)
        // Next day: today drops off, six remain (the saved answer ends on the 16th).
        val later = WeatherPresentation.view(f, Instant.parse("2026-11-11T12:00:00Z"))
        assertEquals(6, later.days.size)
    }

    @Test fun dayDetailListsThatDaysHoursFromTheSavedAnswer() {
        val f = WeatherParser.forecast(sevenDayAnswer())!!
        val hours = WeatherPresentation.hoursOn(f, LocalDate.of(2026, 11, 14))
        assertEquals(24, hours.size)
        assertEquals(LocalDateTime.of(2026, 11, 14, 0, 0), hours.first().time)
        assertEquals(LocalDateTime.of(2026, 11, 14, 23, 0), hours.last().time)
        assertEquals(20, hours[5].rainPercent)
        assertTrue(WeatherPresentation.hoursOn(f, LocalDate.of(2026, 12, 1)).isEmpty())
        // Day or night uses that date's own sunrise and sunset.
        assertTrue(WeatherPresentation.isDay(LocalDateTime.of(2026, 11, 14, 12, 0), f.days))
        assertFalse(WeatherPresentation.isDay(LocalDateTime.of(2026, 11, 14, 20, 0), f.days))
        assertFalse(WeatherPresentation.isDay(LocalDateTime.of(2026, 11, 16, 6, 0), f.days))
        assertTrue(WeatherPresentation.isDay(LocalDateTime.of(2027, 1, 1, 3, 0), f.days)) // unknown date: day
    }

    @Test fun cyrillicQueriesUseARussianOrUkrainianLanguageCode() {
        assertEquals("pt", GeocodingLanguage.forQuery("Lisbon", Locale("pt", "PT")))
        assertEquals("en", GeocodingLanguage.forQuery("Lisbon", Locale.US))
        assertEquals("ru", GeocodingLanguage.forQuery("Москва", Locale.US))
        assertEquals("ru", GeocodingLanguage.forQuery("Москва", Locale("pt", "PT")))
        assertEquals("uk", GeocodingLanguage.forQuery("Москва", Locale("uk", "UA")))
        assertEquals("ru", GeocodingLanguage.forQuery("Москва", Locale("bg", "BG")))
        assertEquals("ru", GeocodingLanguage.forQuery("Moscow Москва", Locale.US))
        assertEquals("en", GeocodingLanguage.forQuery("Lisbon", Locale.ROOT))
        assertTrue(GeocodingLanguage.hasCyrillic("Москва") && !GeocodingLanguage.hasCyrillic("Lisbon"))
        assertTrue(OpenMeteo.geocodingUrl("Москва", "ru").contains("name=%D0%9C%D0%BE%D1%81"))
    }

    /** Responses saved from Open-Meteo (the file names carry the recording date): both spellings give Lisbon, Portugal first. */
    @Test fun bothSpellingsOfLisbonFindLisbonPortugal() {
        val english = WeatherParser.places(sample("live-geocoding-lisbon-en-2026-11-17.json"))!!
        assertEquals(10, english.size)
        assertEquals("Lisbon", english.first().name)
        assertEquals("Lisbon District, Portugal", english.first().detail)
        assertEquals(38.73, english.first().latitude, 0.01)
        val portuguese = WeatherParser.places(sample("live-geocoding-lisbon-pt-2026-11-17.json"))!!
        assertEquals(10, portuguese.size)
        assertEquals("Lisboa", portuguese.first().name)
        assertEquals("Distrito de Lisboa, Portugal", portuguese.first().detail)
        assertEquals(english.first().key, portuguese.first().key)
    }

    @Test fun placeListRoundTripsAndSurvivesJunk() {
        val list = listOf(lisbon, faro, porto)
        assertEquals(list, WeatherPlaces.decode(WeatherPlaces.encode(list)))
        assertEquals(emptyList<WeatherPlace>(), WeatherPlaces.decode(null))
        assertEquals(emptyList<WeatherPlace>(), WeatherPlaces.decode("not json"))
        assertEquals(emptyList<WeatherPlace>(), WeatherPlaces.decode("{}"))
        assertEquals(listOf(lisbon), WeatherPlaces.decode("""[{"name":"Lisbon","detail":"Portugal","lat":38.72,"lon":-9.14},{"name":"","lat":1,"lon":1},{"name":"X","lat":99,"lon":1},{"name":"Lisbon","lat":38.72,"lon":-9.14}]"""))
        val many = (0 until 9).map { WeatherPlace("P$it", "", it.toDouble(), it.toDouble()) }
        assertEquals(6, WeatherPlaces.decode(WeatherPlaces.encode(many)).size)
    }

    @Test fun addRemoveMoveHomeAndCycle() {
        val list = listOf(lisbon)
        val two = WeatherPlaces.add(list, faro)!!
        assertEquals(listOf(lisbon, faro), two)
        assertNull(WeatherPlaces.add(two, faro)) // already there
        val full = (0 until 6).map { WeatherPlace("P$it", "", it.toDouble(), it.toDouble()) }
        assertNull(WeatherPlaces.add(full, porto)) // six is the most
        assertEquals(listOf(faro, lisbon, porto), WeatherPlaces.setHome(listOf(lisbon, faro, porto), faro.key))
        assertEquals(listOf(porto, lisbon, faro), WeatherPlaces.setHome(listOf(lisbon, faro, porto), porto.key))
        assertEquals(listOf(lisbon, porto, faro), WeatherPlaces.move(listOf(lisbon, faro, porto), faro.key, 1))
        assertEquals(listOf(lisbon, faro, porto), WeatherPlaces.move(listOf(lisbon, faro, porto), lisbon.key, -1)) // already first
        assertEquals(listOf(lisbon, porto), WeatherPlaces.remove(listOf(lisbon, faro, porto), faro.key))
        val all = listOf(lisbon, faro, porto)
        assertEquals(faro, WeatherPlaces.cycle(all, lisbon.key, 1))
        assertEquals(lisbon, WeatherPlaces.cycle(all, porto.key, 1))
        assertEquals(porto, WeatherPlaces.cycle(all, lisbon.key, -1))
        assertNull(WeatherPlaces.cycle(emptyList(), null, 1))
        assertEquals(lisbon, WeatherPlaces.selected(all, "gone")) // a vanished choice falls back to home
        assertEquals(porto, WeatherPlaces.selected(all, porto.key))
    }

    @Test fun theOldSinglePlaceBecomesAOneItemListWithItsForecast() {
        assertEquals(listOf(lisbon), WeatherPlaces.migrate(null, "Lisbon", "Portugal", 38.72, -9.14))
        assertEquals(emptyList<WeatherPlace>(), WeatherPlaces.migrate(null, null, null, null, null))
        assertEquals(emptyList<WeatherPlace>(), WeatherPlaces.migrate(null, "X", "", 99.0, 0.0))
        // A saved list wins over the old keys.
        assertEquals(listOf(porto), WeatherPlaces.migrate(WeatherPlaces.encode(listOf(porto)), "Lisbon", "", 38.72, -9.14))

        val old = mutablePreferencesOf(
            stringPreferencesKey("place_name") to "Lisbon", stringPreferencesKey("place_detail") to "Portugal",
            doublePreferencesKey("place_lat") to 38.72, doublePreferencesKey("place_lon") to -9.14,
            stringPreferencesKey("cache_body") to "{\"a\":1}", longPreferencesKey("cache_at") to 1234L,
            stringPreferencesKey("cache_place") to lisbon.key, longPreferencesKey("last_attempt") to 1200L,
        )
        val book = WeatherStore.decode(old)
        assertEquals(listOf(lisbon), book.places)
        assertEquals(lisbon, book.place)
        val saved = book.shown
        assertEquals("{\"a\":1}", saved.cacheBody); assertEquals(1234L, saved.cachedAt); assertEquals(1200L, saved.lastAttempt)
        assertTrue(saved.cacheMatchesPlace)
        // The first change moves it into the list form and drops the old keys.
        WeatherStore.migrate(old)
        assertNull(old[stringPreferencesKey("place_name")]); assertNull(old[stringPreferencesKey("cache_body")])
        val migrated = WeatherStore.decode(old)
        assertEquals(listOf(lisbon), migrated.places)
        assertEquals("{\"a\":1}", migrated.shown.cacheBody)
        assertEquals(1234L, migrated.shown.cachedAt)
        // Running it again changes nothing.
        WeatherStore.migrate(old)
        assertEquals(migrated, WeatherStore.decode(old))
    }

    @Test fun emptyAndBrokenStoresDecodeToNoPlaces() {
        assertEquals(emptyList<WeatherPlace>(), WeatherStore.decode(mutablePreferencesOf()).places)
        assertNull(WeatherStore.decode(mutablePreferencesOf()).place)
        assertEquals(emptyList<WeatherPlace>(), WeatherStore.decode(mutablePreferencesOf(stringPreferencesKey("places") to "garbage")).places)
    }

    @Test fun everyPlaceKeepsItsOwnForecastAndOldDataClock() {
        val book = WeatherBook(listOf(lisbon, faro), faro.key, UnitChoice.Auto, mapOf(
            lisbon.key to PlaceCache("{\"l\":1}", 100L, 90L), faro.key to PlaceCache("{\"a\":1}", 19_000_000L, 18_999_000L)))
        assertEquals(faro, book.place)
        assertEquals(19_000_000L, book.shown.cachedAt)
        assertEquals(100L, book.saved(lisbon).cachedAt)
        assertTrue(WeatherRefresh.isOld(book.saved(lisbon).cachedAt!!, 20_000_000L))
        assertFalse(WeatherRefresh.isOld(book.saved(faro).cachedAt!!, 20_000_000L))
        assertNotNull(book.saved(lisbon).cacheBody)
        assertNull(WeatherBook(listOf(porto)).shown.cacheBody)
    }
}
