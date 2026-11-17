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

    /** Today plus the next six days. The hourly rows cover all of them (7 x 24). */
    const val ForecastDays = 7
    const val SearchCount = 10

    /** The forecast request: the coordinates plus fixed field names. Nothing about the phone. */
    fun forecastUrl(place: WeatherPlace): String =
        "https://$ForecastHost/v1/forecast?latitude=${coordinate(place.latitude)}&longitude=${coordinate(place.longitude)}" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,is_day" +
            "&hourly=temperature_2m,weather_code,precipitation_probability" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset" +
            "&timezone=auto&forecast_days=${ForecastDays}&wind_speed_unit=kmh"

    /**
     * The city lookup: the typed name, sent once when the user taps Search, plus a two-letter
     * language code worked out from the phone's language and the typed letters (see
     * [GeocodingLanguage]). Nothing else is sent.
     */
    fun geocodingUrl(name: String, language: String = "en"): String =
        "https://$GeocodingHost/v1/search?name=${URLEncoder.encode(name.trim(), "UTF-8")}&count=$SearchCount" +
            "&language=${language.lowercase(Locale.ROOT)}&format=json"
}

/**
 * Which `language=` to send with a city search. Open-Meteo matches a name against the names in
 * that language (as seen in earlier manual queries of the service, not re-verified here: `Москва` with
 * language en or pt found nothing, with ru or uk it found Moscow; `Moscow` was found with any language). So a query typed in Cyrillic uses
 * the phone's language when that is Russian or Ukrainian, and Russian otherwise; any other query
 * uses the phone's language, or English when the phone's language is not a plain two-letter code.
 */
internal object GeocodingLanguage {
    private val cyrillicLanguages = setOf("ru", "uk")

    fun hasCyrillic(text: String): Boolean = text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC }

    fun forQuery(query: String, phone: Locale): String {
        val own = phone.language.lowercase(Locale.ROOT).takeIf { it.length == 2 && it.all { c -> c in 'a'..'z' } } ?: "en"
        return if (hasCyrillic(query)) (if (own in cyrillicLanguages) own else "ru") else own
    }
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
    /** The date it is now at the place (not the phone), used to write "Today". */
    val localDate: LocalDate? = null,
)

internal object WeatherPresentation {
    /** The current reading is trusted for 90 minutes; after that the matching hour stands in. */
    private const val CurrentValidMinutes = 90L

    fun localNow(forecast: WeatherForecast, now: Instant): LocalDateTime =
        LocalDateTime.ofInstant(now, ZoneOffset.ofTotalSeconds(forecast.utcOffsetSeconds))

    fun view(forecast: WeatherForecast, now: Instant, hourCount: Int = 6, dayCount: Int = OpenMeteo.ForecastDays): WeatherView {
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
            localDate = local.toLocalDate(),
        )
    }

    /** The saved hours of one date, in order: what the day detail lists. */
    fun hoursOn(forecast: WeatherForecast, date: LocalDate): List<WeatherHour> =
        forecast.hours.filter { it.time.toLocalDate() == date }.sortedBy { it.time }

    /** Day or night at [time], from that date's own sunrise and sunset (day when they are unknown). */
    fun isDay(time: LocalDateTime, days: List<WeatherDay>): Boolean {
        val day = days.firstOrNull { it.date == time.toLocalDate() } ?: return true
        val rise = day.sunrise ?: return true
        val set = day.sunset ?: return true
        return time in rise..set
    }
}

/** What each place's saved forecast and the place list look like on the phone; pure, so it can be tested. */
internal object WeatherPlaces {
    const val Max = 6
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(places: List<WeatherPlace>): String = JsonArray(places.map { p ->
        JsonObject(mapOf("name" to JsonPrimitive(p.name), "detail" to JsonPrimitive(p.detail),
            "lat" to JsonPrimitive(p.latitude), "lon" to JsonPrimitive(p.longitude)))
    }).toString()

    /** Never throws: unreadable text is an empty list; bad items, repeats and anything past six are left out. */
    fun decode(text: String?): List<WeatherPlace> {
        if (text.isNullOrBlank()) return emptyList()
        val array = try { json.parseToJsonElement(text) as? JsonArray } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        } ?: return emptyList()
        val places = array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val name = (item["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val detail = (item["detail"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            val lat = (item["lat"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it in -90.0..90.0 } ?: return@mapNotNull null
            val lon = (item["lon"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it in -180.0..180.0 } ?: return@mapNotNull null
            WeatherPlace(name, detail, lat, lon)
        }
        return places.distinctBy { it.key }.take(Max)
    }

    /**
     * Before 1.2.0 the page kept one place under four separate keys. With no list saved yet, that
     * place becomes a one-item list (the home place); with a list, the old keys are ignored.
     */
    fun migrate(list: String?, legacyName: String?, legacyDetail: String?, legacyLat: Double?, legacyLon: Double?): List<WeatherPlace> {
        if (list != null) return decode(list)
        if (legacyName.isNullOrBlank() || legacyLat == null || legacyLon == null) return emptyList()
        if (legacyLat !in -90.0..90.0 || legacyLon !in -180.0..180.0) return emptyList()
        return listOf(WeatherPlace(legacyName, legacyDetail.orEmpty(), legacyLat, legacyLon))
    }

    /** The list with [place] added at the end, or null when it is already there or the list is full. */
    fun add(places: List<WeatherPlace>, place: WeatherPlace): List<WeatherPlace>? =
        if (places.size >= Max || places.any { it.key == place.key }) null else places + place

    fun remove(places: List<WeatherPlace>, key: String): List<WeatherPlace> = places.filterNot { it.key == key }

    /** Moves one place by [steps] positions (negative is towards home). The first place is home. */
    fun move(places: List<WeatherPlace>, key: String, steps: Int): List<WeatherPlace> {
        val from = places.indexOfFirst { it.key == key }
        if (from < 0) return places
        val to = (from + steps).coerceIn(0, places.lastIndex)
        if (to == from) return places
        return places.toMutableList().also { it.add(to, it.removeAt(from)) }
    }

    fun setHome(places: List<WeatherPlace>, key: String): List<WeatherPlace> = move(places, key, -places.size)

    /** The place to show: the chosen one if it is still in the list, otherwise home. */
    fun selected(places: List<WeatherPlace>, key: String?): WeatherPlace? = places.firstOrNull { it.key == key } ?: places.firstOrNull()

    /** The next (or previous) place, wrapping round. */
    fun cycle(places: List<WeatherPlace>, key: String?, step: Int): WeatherPlace? {
        if (places.isEmpty()) return null
        val index = places.indexOfFirst { it.key == key }.coerceAtLeast(0)
        return places[Math.floorMod(index + step, places.size)]
    }
}
