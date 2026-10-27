package com.brightblu.parc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/*
 * T137: the Weather page's framework-free part. This file exists only in the weather variant
 * (`src/personalWeather`); the normal builds never compile it.
 */

/** A place the user picked. Only these four values are stored, and only lat/lon are sent. */
internal data class WeatherPlace(val name: String, val detail: String, val latitude: Double, val longitude: Double) {
    /** Identifies the cached forecast's place; rounded like the request. */
    val key: String get() = OpenMeteo.coordinate(latitude) + "," + OpenMeteo.coordinate(longitude)
}

/** The one service the weather variant talks to. https only, these two hosts only. */
internal object OpenMeteo {
    const val ForecastHost = "api.open-meteo.com"
    const val GeocodingHost = "geocoding-api.open-meteo.com"
    val Hosts = setOf(ForecastHost, GeocodingHost)

    /** Two decimals (about 1 km): enough for a forecast, less precise than the stored point. */
    fun coordinate(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    /** The forecast request: the coordinates plus fixed field names. Nothing about the phone. */
    fun forecastUrl(place: WeatherPlace): String =
        "https://$ForecastHost/v1/forecast?latitude=${coordinate(place.latitude)}&longitude=${coordinate(place.longitude)}" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,is_day" +
            "&hourly=temperature_2m,weather_code,precipitation_probability" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset" +
            "&timezone=auto&forecast_days=5&wind_speed_unit=kmh"

    /** The city lookup: the typed name, sent once when the user taps Search. */
    fun geocodingUrl(name: String): String =
        "https://$GeocodingHost/v1/search?name=${URLEncoder.encode(name.trim(), "UTF-8")}&count=5&format=json"
}

internal enum class WeatherKind { Clear, PartlyCloudy, Cloudy, Fog, Drizzle, Rain, Snow, Thunder, Unknown }

/** WMO weather interpretation codes, as Open-Meteo documents them. */
internal object WeatherCodes {
    fun kind(code: Int?): WeatherKind = when (code) {
        0, 1 -> WeatherKind.Clear
        2 -> WeatherKind.PartlyCloudy
        3 -> WeatherKind.Cloudy
        45, 48 -> WeatherKind.Fog
        51, 53, 55, 56, 57 -> WeatherKind.Drizzle
        61, 63, 65, 66, 67, 80, 81, 82 -> WeatherKind.Rain
        71, 73, 75, 77, 85, 86 -> WeatherKind.Snow
        95, 96, 99 -> WeatherKind.Thunder
        else -> WeatherKind.Unknown
    }

    fun text(code: Int?): String = when (code) {
        0 -> "Clear"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71, 73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80, 81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Weather"
    }
}

internal data class WeatherCurrent(
    val time: LocalDateTime?, val tempC: Double?, val feelsC: Double?, val code: Int?, val windKmh: Double?, val isDay: Boolean,
)
internal data class WeatherHour(val time: LocalDateTime, val tempC: Double?, val code: Int?, val rainPercent: Int?)
internal data class WeatherDay(
    val date: LocalDate, val code: Int?, val maxC: Double?, val minC: Double?, val rainPercent: Int?,
    val sunrise: LocalDateTime?, val sunset: LocalDateTime?,
)
internal data class WeatherForecast(
    val utcOffsetSeconds: Int, val current: WeatherCurrent?, val hours: List<WeatherHour>, val days: List<WeatherDay>,
)

/** Defensive parsing: any missing or malformed field becomes null or is left out, never a crash. */
internal object WeatherParser {
    private val json = Json { ignoreUnknownKeys = true }

    private fun root(text: String): JsonObject? = try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    }

    private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonArray?.at(index: Int): JsonElement? = this?.getOrNull(index)
    private fun dateTime(text: String?): LocalDateTime? = text?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    private fun date(text: String?): LocalDate? = text?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    private fun percent(value: Double?): Int? = value?.roundToInt()?.coerceIn(0, 100)
    private fun code(value: Double?): Int? = value?.roundToInt()

    /** Null when the text is not a forecast at all (not JSON, an error object, or no usable part). */
    fun forecast(text: String): WeatherForecast? {
        val root = root(text) ?: return null
        val offset = root["utc_offset_seconds"].number()?.roundToInt()?.coerceIn(-18 * 3600, 18 * 3600) ?: 0
        val current = (root["current"] as? JsonObject)?.let { c ->
            WeatherCurrent(
                time = dateTime(c["time"].text()),
                tempC = c["temperature_2m"].number(),
                feelsC = c["apparent_temperature"].number(),
                code = code(c["weather_code"].number()),
                windKmh = c["wind_speed_10m"].number(),
                isDay = c["is_day"].number()?.let { it != 0.0 } ?: true,
            ).takeIf { it.tempC != null || it.code != null }
        }
        val hourly = root["hourly"] as? JsonObject
        val hours = hourly?.array("time")?.mapIndexedNotNull { index, element ->
            val time = dateTime(element.text()) ?: return@mapIndexedNotNull null
            WeatherHour(time, hourly.array("temperature_2m").at(index).number(), code(hourly.array("weather_code").at(index).number()),
                percent(hourly.array("precipitation_probability").at(index).number()))
        }.orEmpty()
        val daily = root["daily"] as? JsonObject
        val days = daily?.array("time")?.mapIndexedNotNull { index, element ->
            val day = date(element.text()) ?: return@mapIndexedNotNull null
            WeatherDay(day, code(daily.array("weather_code").at(index).number()),
                daily.array("temperature_2m_max").at(index).number(), daily.array("temperature_2m_min").at(index).number(),
                percent(daily.array("precipitation_probability_max").at(index).number()),
                dateTime(daily.array("sunrise").at(index).text()), dateTime(daily.array("sunset").at(index).text()))
        }.orEmpty()
        if (current == null && hours.isEmpty() && days.isEmpty()) return null
        return WeatherForecast(offset, current, hours, days)
    }

    /** Places from the geocoding answer. No `results` (no match) is an empty list; null when unreadable. */
    fun places(text: String): List<WeatherPlace>? {
        val root = root(text) ?: return null
        if (root["error"] != null) return null
        val results = root["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val name = item["name"].text()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val lat = item["latitude"].number()?.takeIf { it in -90.0..90.0 } ?: return@mapNotNull null
            val lon = item["longitude"].number()?.takeIf { it in -180.0..180.0 } ?: return@mapNotNull null
            val detail = listOfNotNull(item["admin1"].text(), item["country"].text())
                .map { it.trim() }.filter { it.isNotEmpty() && it != name }.distinct().joinToString(", ")
            WeatherPlace(name, detail, lat, lon)
        }
    }
}

internal enum class TempUnit { Celsius, Fahrenheit }

/** Auto follows the phone's region; the user can choose either unit. */
internal enum class UnitChoice(val id: String, val label: String) {
    Auto("auto", "Automatic (by region)"), Celsius("c", "Celsius"), Fahrenheit("f", "Fahrenheit");
    companion object { fun decode(raw: String?) = entries.firstOrNull { it.id == raw } ?: Auto }
}

internal object WeatherUnits {
    private val fahrenheitRegions = setOf("US", "PR", "GU", "VI", "AS", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH")
    fun resolve(choice: UnitChoice, locale: Locale): TempUnit = when (choice) {
        UnitChoice.Celsius -> TempUnit.Celsius
        UnitChoice.Fahrenheit -> TempUnit.Fahrenheit
        UnitChoice.Auto -> if (locale.country.uppercase(Locale.ROOT) in fahrenheitRegions) TempUnit.Fahrenheit else TempUnit.Celsius
    }
    fun degrees(celsius: Double?, unit: TempUnit): String {
        celsius ?: return "–"
        val value = if (unit == TempUnit.Fahrenheit) celsius * 9 / 5 + 32 else celsius
        val rounded = value.roundToInt()
        return "${if (rounded == 0) 0 else rounded}°"
    }
    fun wind(kmh: Double?, unit: TempUnit): String {
        kmh ?: return "–"
        return if (unit == TempUnit.Fahrenheit) "${(kmh / 1.609344).roundToInt()} mph" else "${kmh.roundToInt()} km/h"
    }
    fun percent(value: Int?): String = value?.let { "$it %" } ?: "–"
}

/** When the page may ask Open-Meteo again. Only ever called while the page is on screen. */
internal object WeatherRefresh {
    const val IntervalMs = 30 * 60_000L
    /** With nothing saved for this place yet, a failed try may repeat sooner. */
    const val FirstLoadRetryMs = 5 * 60_000L
    /** A saved forecast older than this is labelled as old. */
    const val OldAfterMs = 3 * 3_600_000L

    fun shouldFetch(now: Long, lastAttempt: Long?, cachedAt: Long?, cacheMatchesPlace: Boolean): Boolean {
        val hasData = cachedAt != null && cacheMatchesPlace
        val gap = if (hasData) IntervalMs else FirstLoadRetryMs
        // A clock moved backwards makes the age negative: treat it as old, not as fresh forever.
        if (lastAttempt != null && now - lastAttempt in 0 until gap) return false
        if (hasData && now - cachedAt!! in 0 until IntervalMs) return false
        return true
    }

    fun updatedLabel(fetchedAt: Long, now: Long): String {
        val minutes = ((now - fetchedAt).coerceAtLeast(0)) / 60_000
        return when {
            minutes < 1 -> "Updated just now"
            minutes < 60 -> "Updated $minutes min ago"
            minutes < 24 * 60 -> "Updated ${minutes / 60} h ago"
            minutes < 48 * 60 -> "Updated 1 day ago"
            else -> "Updated ${minutes / (24 * 60)} days ago"
        }
    }

    fun isOld(fetchedAt: Long, now: Long): Boolean = now - fetchedAt >= OldAfterMs
}

internal enum class WeatherProblem { Offline, Timeout, RateLimited, Server, BadResponse }

internal object WeatherMessages {
    fun problem(problem: WeatherProblem, hasSaved: Boolean): String = when (problem) {
        WeatherProblem.Offline -> if (hasSaved) "No connection. Showing the saved forecast." else "No connection. The forecast loads when you are online."
        WeatherProblem.Timeout -> "Open-Meteo did not answer in time. Parc tries again later."
        WeatherProblem.RateLimited -> "Open-Meteo is busy. Parc tries again later."
        WeatherProblem.Server -> "Open-Meteo had a problem. Parc tries again later."
        WeatherProblem.BadResponse -> "The forecast could not be read. Parc tries again later."
    }
    const val NoPlaceFound = "No place found with that name. Check the spelling."
}

/** What the page shows, worked out from a (possibly old) forecast and the current moment. */
internal data class WeatherView(
    val tempC: Double?,
    val feelsC: Double?,
    val code: Int?,
    val isDay: Boolean,
    val windKmh: Double?,
    val today: WeatherDay?,
    val hours: List<WeatherHour>,
    val days: List<WeatherDay>,
    val rainPercent: Int?,
)

internal object WeatherPresentation {
    /** The current reading is trusted for 90 minutes; after that the matching hour stands in. */
    private const val CurrentValidMinutes = 90L

    fun localNow(forecast: WeatherForecast, now: Instant): LocalDateTime =
        LocalDateTime.ofInstant(now, ZoneOffset.ofTotalSeconds(forecast.utcOffsetSeconds))

    fun view(forecast: WeatherForecast, now: Instant, hourCount: Int = 6, dayCount: Int = 5): WeatherView {
        val local = localNow(forecast, now)
        val hourStart = local.truncatedTo(ChronoUnit.HOURS)
        val thisHour = forecast.hours.firstOrNull { it.time == hourStart }
        val current = forecast.current?.takeIf { c ->
            c.time == null || kotlin.math.abs(ChronoUnit.MINUTES.between(c.time, local)) <= CurrentValidMinutes
        }
        val today = forecast.days.firstOrNull { it.date == local.toLocalDate() }
        val isDay = current?.isDay ?: today?.let { d ->
            val rise = d.sunrise; val set = d.sunset
            if (rise != null && set != null) local in rise..set else true
        } ?: true
        return WeatherView(
            tempC = current?.tempC ?: thisHour?.tempC,
            feelsC = current?.feelsC,
            code = current?.code ?: thisHour?.code,
            isDay = isDay,
            windKmh = current?.windKmh,
            today = today,
            hours = forecast.hours.filter { it.time >= hourStart }.take(hourCount),
            days = forecast.days.filter { it.date >= local.toLocalDate() }.take(dayCount),
            rainPercent = today?.rainPercent ?: thisHour?.rainPercent,
        )
    }
}
