package com.brightblu.parc

import java.time.Instant
import java.util.Locale

/** No current-hour temperature or today's range means nothing to say, even if a cache exists. */
internal fun todayWeather(book: WeatherBook, now: Instant, locale: Locale): TodayWeather? {
    val place = book.places.firstOrNull() ?: return null
    val cache = book.caches[place.key] ?: return null
    val forecast = cache.body?.let(WeatherParser::forecast) ?: return null
    // A timestamp-free 'current' value cannot truthfully be called weather now.
    val view = WeatherPresentation.view(forecast.copy(current = forecast.current?.takeIf { it.time != null }), now)
    val current = view.tempC ?: return null
    val high = view.today?.maxC ?: return null
    val low = view.today.minC ?: return null
    val unit = WeatherUnits.resolve(book.units, locale)
    return TodayWeather(place.name, WeatherUnits.degrees(current, unit), WeatherUnits.degrees(high, unit),
        WeatherUnits.degrees(low, unit), cache.at == null || now.toEpochMilli() < cache.at || WeatherRefresh.isOld(cache.at, now.toEpochMilli()))
}

/** T198: the weather now at the first saved place whose name contains [nameContains] (any case); null when none is saved or no reading is. */
internal fun placeWeatherNow(book: WeatherBook, nameContains: String, now: Instant, locale: Locale): PlaceWeatherNow? {
    val place = book.places.firstOrNull { it.name.contains(nameContains, ignoreCase = true) } ?: return null
    val cache = book.caches[place.key] ?: return null
    val forecast = cache.body?.let(WeatherParser::forecast) ?: return null
    val view = WeatherPresentation.view(forecast.copy(current = forecast.current?.takeIf { it.time != null }), now)
    val current = view.tempC ?: return null
    val unit = WeatherUnits.resolve(book.units, locale)
    return PlaceWeatherNow(place.name, WeatherUnits.degrees(current, unit),
        cache.at == null || now.toEpochMilli() < cache.at || WeatherRefresh.isOld(cache.at, now.toEpochMilli()))
}
