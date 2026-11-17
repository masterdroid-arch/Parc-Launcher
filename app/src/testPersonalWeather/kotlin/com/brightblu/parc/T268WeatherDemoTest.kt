package com.brightblu.parc

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T268: the weather demo has fictional places and a different forecast for each. */
class T268WeatherDemoTest {
    private val now = LocalDateTime.of(2026, 10, 26, 9, 40)

    @Test
    fun theDemoHasSeveralFictionalPlaces() {
        assertTrue(WeatherDemo.Places.size >= 2)
        assertEquals(WeatherDemo.Places.size, WeatherDemo.Places.distinct().size)
        assertTrue(WeatherDemo.Places.all { it.isNotBlank() })
        assertFalse(WeatherDemo.Places.any { it.equals("Sample city", ignoreCase = true) })
        assertEquals(WeatherDemo.Places.first(), WeatherDemo.PlaceName)
    }

    @Test
    fun movingBetweenPlacesWrapsAtBothEnds() {
        assertEquals(1, WeatherDemo.cycle(0, 1))
        assertEquals(WeatherDemo.Places.lastIndex, WeatherDemo.cycle(0, -1))
        assertEquals(0, WeatherDemo.cycle(WeatherDemo.Places.lastIndex, 1))
        assertEquals(WeatherDemo.Places.lastIndex, WeatherDemo.cycle(0, -1 + WeatherDemo.Places.size))
    }

    @Test
    fun eachPlaceHasItsOwnForecast() {
        val first = WeatherDemo.forecast(now, 0)
        val second = WeatherDemo.forecast(now, 1)
        val third = WeatherDemo.forecast(now, 2)
        assertEquals(17.0, first.current?.tempC ?: 0.0, 0.0)
        assertNotEquals(first.current?.tempC, second.current?.tempC)
        assertNotEquals(second.current?.tempC, third.current?.tempC)
        assertNotEquals(first.days.first().maxC, second.days.first().maxC)
        assertEquals(7, first.days.size)
        assertEquals(7 * 24, first.hours.size)
        assertEquals(7 * 24, third.hours.size)
    }
}
