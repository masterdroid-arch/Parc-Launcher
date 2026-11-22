package com.brightblu.parc

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brightblu.parc.feature.settings.ui.SettingKind
import com.brightblu.parc.feature.settings.ui.SettingRow
import com.brightblu.parc.feature.settings.ui.SettingsCard
import com.brightblu.parc.feature.settings.ui.SettingsSegmented
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.parcRowPadding
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/** T137: the weather variant's implementation of the hook the shared hub code calls. */
internal object WeatherVariant {
    const val Available = true
    const val PageId = "weather"

    @Composable fun Page(skin: Skin, active: Boolean, openOptions: () -> Unit) = WeatherHubPage(skin, active, openOptions, demo = false)
    @Composable fun DemoPage(skin: Skin) = WeatherHubPage(skin, active = false, openOptions = {}, demo = true)
    @Composable fun Options(skin: Skin) = WeatherOptions(skin)

    /** T196: the Translate search mode (DeepL). Only this build lists it. */
    fun extraSearchModes(): List<com.brightblu.parc.feature.search.SearchMode> = listOf(TranslateSearchMode())

    /** M13 review: the saved places travel in the backup (not the cached forecasts). */
    fun backupStores(context: android.content.Context): List<com.brightblu.parc.core.data.backup.BackupStore> = listOf(
        com.brightblu.parc.core.data.backup.BackupStore(com.brightblu.parc.core.data.backup.BackupSpecs.weather,
            WeatherBackupAccess(com.brightblu.parc.core.data.backup.PreferencesStoreAccess(context.applicationContext.weatherDataStore))),
        DeepLKeyStore.of(context).backupStore(),
    )

    fun hasBackupKey(context: android.content.Context) = PrefsKeyBlobStorage(context).read() != null

    /** T192: cached home forecast only; Today never starts a request. */
    suspend fun today(context: android.content.Context, now: Instant): TodayWeather? =
        todayWeather(WeatherStore(context).read(), now, Locale.getDefault())

    suspend fun todayPlace(context: android.content.Context): SkyPlace? {
        val book = WeatherStore(context).read()
        val place = book.places.firstOrNull() ?: return null
        return skyPlaceOf(place, book.caches[place.key]?.body)
    }

    /**
     * T189: the first saved place (home) for the Sky page, with the UTC offset of its saved forecast when there is one.
     * Reads only what is already saved; it never asks the network for anything.
     */
    /** T198: the saved forecast of the first saved place whose name contains [nameContains]; reads only what is saved, never asks the network. */
    @Composable fun placeNow(nameContains: String): PlaceWeatherNow? {
        val context = androidx.compose.ui.platform.LocalContext.current
        val store = remember(context) { WeatherStore(context) }
        val book = store.book.collectAsState(initial = null).value
        return remember(book, nameContains) { book?.let { placeWeatherNow(it, nameContains, Instant.now(), Locale.getDefault()) } }
    }

    @Composable fun homePlace(): SkyPlace? {
        val context = androidx.compose.ui.platform.LocalContext.current
        val store = remember(context) { WeatherStore(context) }
        val book = store.book.collectAsState(initial = null).value
        val home = book?.places?.firstOrNull()
        val body = home?.let { book?.caches?.get(it.key)?.body }
        return remember(home, body) { home?.let { skyPlaceOf(it, body) } }
    }
}

/** The weather place as a sky place; the offset comes from the saved forecast's `utc_offset_seconds`, null when nothing is saved. */
internal fun skyPlaceOf(place: WeatherPlace, forecastBody: String?): SkyPlace =
    SkyPlace(place.latitude, place.longitude, place.name, forecastBody?.let { WeatherParser.forecast(it)?.utcOffsetSeconds })


/** Asks Open-Meteo for the forecast at most once at a time, and only when the refresh rule allows. */
internal object WeatherFetcher {
    private val lock = Mutex()

    sealed interface Outcome {
        data object NotDue : Outcome
        data object Updated : Outcome
        data class Failed(val problem: WeatherProblem) : Outcome
    }

    /** Never called from the background: only from the page while it is on screen. One place at a time. */
    suspend fun refreshIfDue(store: WeatherStore, now: Long, key: String): Outcome = lock.withLock {
        withContext(Dispatchers.IO) {
            val book = store.read()
            val place = book.places.firstOrNull { it.key == key } ?: return@withContext Outcome.NotDue
            val saved = book.saved(place)
            if (!WeatherRefresh.shouldFetch(now, saved.lastAttempt, saved.cachedAt, saved.cacheMatchesPlace)) return@withContext Outcome.NotDue
            store.markAttempt(key, now)
            when (val result = WeatherHttp.get(OpenMeteo.forecastUrl(place))) {
                is FetchResult.Failed -> Outcome.Failed(result.problem)
                is FetchResult.Ok -> if (WeatherParser.forecast(result.body) == null) {
                    ParcLog.w("Weather", "forecast answer could not be read")
                    Outcome.Failed(WeatherProblem.BadResponse)
                } else {
                    store.saveForecast(key, result.body, now)
                    Outcome.Updated
                }
            }
        }
    }

    sealed interface Lookup {
        data class Found(val places: List<WeatherPlace>) : Lookup
        data class Failed(val message: String) : Lookup
    }

    suspend fun lookUp(name: String, phone: Locale): Lookup = withContext(Dispatchers.IO) {
        when (val result = WeatherHttp.get(OpenMeteo.geocodingUrl(name, GeocodingLanguage.forQuery(name, phone)))) {
            is FetchResult.Failed -> Lookup.Failed(WeatherMessages.problem(result.problem, hasSaved = false))
            is FetchResult.Ok -> {
                val places = WeatherParser.places(result.body)
                when {
                    places == null -> Lookup.Failed(WeatherMessages.problem(WeatherProblem.BadResponse, hasSaved = false))
                    places.isEmpty() -> Lookup.Failed(WeatherMessages.NoPlaceFound)
                    else -> Lookup.Found(places)
                }
            }
        }
    }
}

/** Demo mode: fixed samples built around the current hour, never fetched and never stored. */
internal object WeatherDemo {
    /** Fictional places; none of them is a real city name or a saved place. */
    val Places = listOf("Alder Cove", "Brindle Point", "Cairn Valley")
    val PlaceName: String get() = Places.first()
    const val UpdatedMinutesAgo = 12L

    /** Move between the fictional places, wrapping at both ends. */
    fun cycle(index: Int, step: Int): Int = Math.floorMod(index + step, Places.size)

    fun forecast(now: LocalDateTime, place: Int = 0): WeatherForecast {
        val hour = now.withMinute(0).withSecond(0).withNano(0)
        val day = now.toLocalDate()
        val shift = when (place.coerceIn(0, Places.lastIndex)) { 0 -> 0.0; 1 -> -4.0; else -> 3.0 }
        val temps = listOf(17.0, 18.0, 19.0, 19.0, 18.0, 16.0, 15.0, 14.0, 13.0, 13.0, 12.0, 12.0)
            .map { it + shift }
        val codes = listOf(2, 2, 1, 1, 3, 61, 61, 3, 2, 0, 0, 0)
        val rain = listOf(10, 10, 5, 5, 30, 60, 55, 30, 10, 5, 0, 0)
        // Seven days of hours: the first twelve from now are fixed, the rest repeat a gentle daily swing.
        val hours = (0 until 7 * 24).map { i ->
            val time = day.atStartOfDay().plusHours(i.toLong())
            val ahead = ChronoUnit.HOURS.between(hour, time).toInt()
            if (ahead in temps.indices) WeatherHour(time, temps[ahead], codes[ahead], rain[ahead])
            else WeatherHour(time, 12.0 + shift + 6.0 * kotlin.math.sin((time.hour - 9) * Math.PI / 12), codes[i % codes.size], rain[(i / 3) % rain.size])
        }
        val days = listOf(
            WeatherDay(day, 2, 19.0 + shift, 11.0 + shift, 60, day.atTime(7, 32), day.atTime(18, 21)),
            WeatherDay(day.plusDays(1), 61, 16.0 + shift, 10.0 + shift, 80, day.plusDays(1).atTime(7, 34), day.plusDays(1).atTime(18, 19)),
            WeatherDay(day.plusDays(2), 3, 17.0 + shift, 9.0 + shift, 20, day.plusDays(2).atTime(7, 35), day.plusDays(2).atTime(18, 17)),
            WeatherDay(day.plusDays(3), 0, 21.0 + shift, 10.0 + shift, 0, day.plusDays(3).atTime(7, 36), day.plusDays(3).atTime(18, 15)),
            WeatherDay(day.plusDays(4), 95, 18.0 + shift, 12.0 + shift, 70, day.plusDays(4).atTime(7, 38), day.plusDays(4).atTime(18, 14)),
            WeatherDay(day.plusDays(5), 63, 15.0 + shift, 9.0 + shift, 65, day.plusDays(5).atTime(7, 39), day.plusDays(5).atTime(18, 12)),
            WeatherDay(day.plusDays(6), 1, 18.0 + shift, 8.0 + shift, 10, day.plusDays(6).atTime(7, 41), day.plusDays(6).atTime(18, 10)),
        )
        return WeatherForecast(0, WeatherCurrent(now, 17.0 + shift, 16.0 + shift, 2, 14.0, true), hours, days)
    }
}


@Composable
private fun WeatherHubPage(skin: Skin, active: Boolean, openOptions: () -> Unit, demo: Boolean) {
    val context = LocalContext.current
    val store = remember(context) { WeatherStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val saved by store.book.collectAsState(initial = null)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val placeKey = saved?.place?.key
    var problem by remember(placeKey) { mutableStateOf<WeatherProblem?>(null) }
    var openDay by remember(placeKey) { mutableStateOf<LocalDate?>(null) }
    if (!demo) LaunchedEffect(active, placeKey) {
        // Only while the page is on screen and Parc is in front: never in the background. Only the place on screen is asked for.
        while (active) {
            now = System.currentTimeMillis()
            if (placeKey != null) when (val outcome = WeatherFetcher.refreshIfDue(store, now, placeKey)) {
                WeatherFetcher.Outcome.NotDue -> Unit
                WeatherFetcher.Outcome.Updated -> problem = null
                is WeatherFetcher.Outcome.Failed -> problem = outcome.problem
            }
            delay(60_000)
        }
    }
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val unit = WeatherUnits.resolve(saved?.units ?: UnitChoice.Auto, locale)
    val hour24 = remember(context) { DateFormat.is24HourFormat(context) }
    if (demo) {
        var demoPlace by remember { mutableIntStateOf(0) }
        val local = remember { LocalDateTime.now() }
        val forecast = remember(local, demoPlace) { WeatherDemo.forecast(local, demoPlace) }
        WeatherBody(skin, forecast, WeatherPresentation.view(forecast, Instant.now()),
            WeatherPlaceLine(WeatherDemo.Places[demoPlace], WeatherDemo.Places.size, demoPlace) { step ->
                demoPlace = WeatherDemo.cycle(demoPlace, step)
            },
            "Updated ${WeatherDemo.UpdatedMinutesAgo} min ago", false, null, unit, hour24, locale, openDay) { openDay = it }
        return
    }
    val book = saved ?: run { CellLabel("Loading…", skin); return }
    val place = book.place ?: run {
        HubPermissionCard("Type your city once in the page options. Parc then sends only its coordinates to Open-Meteo for the forecast.",
            "Choose city", skin) { openOptions() }
        return
    }
    val current = book.shown
    val line = WeatherPlaceLine(place.name, book.places.size, book.places.indexOfFirst { it.key == place.key }.coerceAtLeast(0)) { step ->
        WeatherPlaces.cycle(book.places, place.key, step)?.let { next -> scope.launch { store.select(next.key) } }
    }
    val body = current.cacheBody.takeIf { current.cacheMatchesPlace }
    val forecast = remember(body) { body?.let(WeatherParser::forecast) }
    val fetchedAt = current.cachedAt
    if (forecast == null || fetchedAt == null) {
        Column(Modifier.fillMaxSize().hubPageScroll(), verticalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
            PlaceRow(line, skin)
            CellLabel(problem?.let { WeatherMessages.problem(it, hasSaved = false) } ?: "Loading the forecast for ${place.name}…", skin)
        }
        return
    }
    val old = WeatherRefresh.isOld(fetchedAt, now)
    val status = problem?.let { WeatherMessages.problem(it, hasSaved = true) }
    WeatherBody(skin, forecast, WeatherPresentation.view(forecast, Instant.ofEpochMilli(now)), line,
        WeatherRefresh.updatedLabel(fetchedAt, now), old, status, unit, hour24, locale, openDay) { openDay = it }
}

/** The place row's content: the name, how many places there are, which one this is, and how to move to another. */
private class WeatherPlaceLine(val name: String, val count: Int, val index: Int, val cycle: (Int) -> Unit)

/** The row at the top of the page: the place name, with arrows beside it when there is more than one place. */
@Composable
private fun PlaceRow(line: WeatherPlaceLine, skin: Skin) {
    val many = line.count > 1
    if (!many) {
        BasicText(line.name, style = hudValueStyle(skin), maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val arrow = Modifier.heightIn(min = Metrics.touchTargetDp.dp).width(Metrics.touchTargetDp.dp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(arrow.clickable(role = Role.Button) { line.cycle(-1) }.semantics { contentDescription = "Previous place" }, contentAlignment = Alignment.Center) {
            BasicText("‹", style = hudValueStyle(skin))
        }
        Box(Modifier.weight(1f).heightIn(min = Metrics.touchTargetDp.dp).clickable(role = Role.Button) { line.cycle(1) }
            .semantics(mergeDescendants = true) { contentDescription = "${line.name}, place ${line.index + 1} of ${line.count}. Tap for the next place" },
            contentAlignment = Alignment.Center) {
            BasicText("${line.name} · ${line.index + 1}/${line.count}", style = hudValueStyle(skin), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(arrow.clickable(role = Role.Button) { line.cycle(1) }.semantics { contentDescription = "Next place" }, contentAlignment = Alignment.Center) {
            BasicText("›", style = hudValueStyle(skin))
        }
    }
}

@Composable
private fun WeatherBody(
    skin: Skin, forecast: WeatherForecast, view: WeatherView, place: WeatherPlaceLine, updated: String, old: Boolean, status: String?,
    unit: TempUnit, hour24: Boolean, locale: Locale, openDay: LocalDate?, onOpenDay: (LocalDate?) -> Unit,
) {
    val size = LocalHubSize.current
    val temp = WeatherUnits.degrees(view.tempC, unit)
    val condition = WeatherCodes.text(view.code)
    val kind = WeatherCodes.kind(view.code)
    val high = WeatherUnits.degrees(view.today?.maxC, unit)
    val low = WeatherUnits.degrees(view.today?.minC, unit)
    val updatedLine = "${place.name} · $updated" + if (old) " · old forecast" else ""
    val timeFormat = remember(hour24, locale) { DateTimeFormatter.ofPattern(if (hour24) "HH:mm" else "h:mm a", locale) }
    val hourFormat = remember(hour24, locale) { DateTimeFormatter.ofPattern(if (hour24) "HH" else "h a", locale) }
    val warn = Color(skin.colors.warn.toInt())
    val day = openDay?.let { date -> view.days.firstOrNull { it.date == date } ?: forecast.days.firstOrNull { it.date == date } }
    // M13 review: the system Back gesture closes the day detail before anything else (a registered handler runs first).
    androidx.activity.compose.BackHandler(enabled = day != null) { onOpenDay(null) }
    Column(Modifier.fillMaxSize().hubPageScroll(), verticalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
        PlaceRow(place, skin)
        if (day != null) {
            DayDetail(day, WeatherPresentation.hoursOn(forecast, day.date), forecast.days, view.localDate, unit, hourFormat, timeFormat, locale, skin) { onOpenDay(null) }
            BasicText(updatedLine, style = hudLabelStyle(skin).let { if (old) it.copy(color = warn) else it }, maxLines = 2)
            status?.let { BasicText(it, style = hudLabelStyle(skin).copy(color = warn), maxLines = 3) }
            return@Column
        }
        Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "$temp, $condition. High $high, low $low. $updatedLine" },
            verticalAlignment = Alignment.CenterVertically) {
            WeatherIcon(kind, view.isDay, if (size == HubSize.Compact) 36f else 56f, skin)
            Spacer(Modifier.width(Metrics.hudCellGapDp.dp * 1.5f))
            if (size == HubSize.Compact) Column {
                BasicText("$temp · $condition", style = hudValueStyle(skin), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText("H $high · L $low · $updated", style = hudLabelStyle(skin), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else HubHero(temp, condition + (view.feelsC?.let { " · feels like ${WeatherUnits.degrees(it, unit)}" } ?: ""), skin)
        }
        if (size != HubSize.Compact) {
            BasicText(updatedLine, style = hudLabelStyle(skin).let { if (old) it.copy(color = warn) else it }, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
                HudCell(skin, Modifier.weight(1f)) { Readout("High / low", "$high / $low", skin) }
                HudCell(skin, Modifier.weight(1f)) { Readout("Rain", WeatherUnits.percent(view.rainPercent), skin) }
                HudCell(skin, Modifier.weight(1f)) { Readout("Wind", WeatherUnits.wind(view.windKmh, unit), skin) }
            }
            if (view.hours.isNotEmpty()) HudCell(skin, Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    view.hours.forEach { hour ->
                        Column(Modifier.semantics(mergeDescendants = true) {
                            contentDescription = "${hour.time.format(hourFormat)}: ${WeatherUnits.degrees(hour.tempC, unit)}, ${WeatherCodes.text(hour.code)}, rain ${WeatherUnits.percent(hour.rainPercent)}"
                        }, horizontalAlignment = Alignment.CenterHorizontally) {
                            BasicText(hour.time.format(hourFormat), style = hudLabelStyle(skin), maxLines = 1)
                            WeatherIcon(WeatherCodes.kind(hour.code), WeatherPresentation.isDay(hour.time, forecast.days), 22f, skin)
                            BasicText(WeatherUnits.degrees(hour.tempC, unit), style = hudValueStyle(skin), maxLines = 1)
                        }
                    }
                }
            }
            val sunrise = view.today?.sunrise?.format(timeFormat)
            val sunset = view.today?.sunset?.format(timeFormat)
            if (sunrise != null || sunset != null)
                BasicText("Sunrise ${sunrise ?: "–"} · Sunset ${sunset ?: "–"}", style = hudValueStyle(skin), maxLines = 2)
        }
        // T184: the coming days at every hub size (the rows used to be drawn only at Tall). The page scrolls, nothing is dropped.
        view.days.forEach { d -> DayRow(d, view.localDate, unit, locale, skin) { onOpenDay(d.date) } }
        status?.let { BasicText(it, style = hudLabelStyle(skin).copy(color = warn), maxLines = 3) }
    }
}

private fun dayName(date: LocalDate, today: LocalDate?, locale: Locale): String =
    if (date == today) "Today" else date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, locale)

@Composable
private fun DayRow(day: WeatherDay, today: LocalDate?, unit: TempUnit, locale: Locale, skin: Skin, onClick: () -> Unit) {
    val name = dayName(day.date, today, locale)
    Row(Modifier.fillMaxWidth().heightIn(min = Metrics.touchTargetDp.dp).clickable(role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) {
            contentDescription = "$name: ${WeatherCodes.text(day.code)}, ${WeatherUnits.degrees(day.minC, unit)} to ${WeatherUnits.degrees(day.maxC, unit)}, rain ${WeatherUnits.percent(day.rainPercent)}. Tap for the hours"
        }, verticalAlignment = Alignment.CenterVertically) {
        BasicText(name, Modifier.weight(1f), style = hudValueStyle(skin), maxLines = 1)
        WeatherIcon(WeatherCodes.kind(day.code), true, 24f, skin)
        BasicText(WeatherUnits.percent(day.rainPercent), Modifier.weight(1f).padding(start = Metrics.itemGapDp.dp), style = hudLabelStyle(skin), maxLines = 1)
        BasicText("${WeatherUnits.degrees(day.maxC, unit)} / ${WeatherUnits.degrees(day.minC, unit)}", style = hudValueStyle(skin), maxLines = 1)
    }
}

/** One day's hours, inside the page, with a Back row at the top. All the data comes from the saved forecast. */
@Composable
private fun DayDetail(
    day: WeatherDay, hours: List<WeatherHour>, days: List<WeatherDay>, today: LocalDate?, unit: TempUnit,
    hourFormat: DateTimeFormatter, timeFormat: DateTimeFormatter, locale: Locale, skin: Skin, onBack: () -> Unit,
) {
    val dateFormat = remember(locale) { DateTimeFormatter.ofPattern("EEE d MMM", locale) }
    val title = (if (day.date == today) "Today · " else "") + day.date.format(dateFormat)
    Row(Modifier.fillMaxWidth().heightIn(min = Metrics.touchTargetDp.dp).clickable(role = Role.Button, onClick = onBack)
        .semantics(mergeDescendants = true) { contentDescription = "Back to the coming days" }, verticalAlignment = Alignment.CenterVertically) {
        BasicText("‹ Back", style = hudValueStyle(skin, skin.colors.accent), maxLines = 1)
    }
    Row(Modifier.semantics(mergeDescendants = true) {
        contentDescription = "$title: ${WeatherCodes.text(day.code)}. High ${WeatherUnits.degrees(day.maxC, unit)}, low ${WeatherUnits.degrees(day.minC, unit)}, rain ${WeatherUnits.percent(day.rainPercent)}"
    }, verticalAlignment = Alignment.CenterVertically) {
        WeatherIcon(WeatherCodes.kind(day.code), true, 36f, skin)
        Spacer(Modifier.width(Metrics.hudCellGapDp.dp * 1.5f))
        Column {
            BasicText(title, style = hudValueStyle(skin), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText("${WeatherCodes.text(day.code)} · H ${WeatherUnits.degrees(day.maxC, unit)} · L ${WeatherUnits.degrees(day.minC, unit)} · rain ${WeatherUnits.percent(day.rainPercent)}",
                style = hudLabelStyle(skin), maxLines = 2)
        }
    }
    val sunrise = day.sunrise?.format(timeFormat); val sunset = day.sunset?.format(timeFormat)
    if (sunrise != null || sunset != null) BasicText("Sunrise ${sunrise ?: "–"} · Sunset ${sunset ?: "–"}", style = hudLabelStyle(skin), maxLines = 2)
    if (hours.isEmpty()) BasicText("No hourly forecast saved for this day.", style = hudLabelStyle(skin))
    hours.forEach { hour ->
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "${hour.time.format(hourFormat)}: ${WeatherUnits.degrees(hour.tempC, unit)}, ${WeatherCodes.text(hour.code)}, rain ${WeatherUnits.percent(hour.rainPercent)}"
        }, verticalAlignment = Alignment.CenterVertically) {
            BasicText(hour.time.format(hourFormat), Modifier.weight(1f), style = hudLabelStyle(skin), maxLines = 1)
            WeatherIcon(WeatherCodes.kind(hour.code), WeatherPresentation.isDay(hour.time, days), 24f, skin)
            BasicText(WeatherUnits.percent(hour.rainPercent), Modifier.weight(1f).padding(start = Metrics.itemGapDp.dp), style = hudLabelStyle(skin), maxLines = 1)
            BasicText(WeatherUnits.degrees(hour.tempC, unit), style = hudValueStyle(skin), maxLines = 1)
        }
    }
}

/** The Weather page options: the places (add, remove, reorder, home), units, and a way to forget them all. */
@Composable
private fun WeatherOptions(skin: Skin) {
    val context = LocalContext.current
    val store = remember(context) { WeatherStore(context.applicationContext) }
    val saved by store.book.collectAsState(initial = WeatherBook())
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    val full = saved.places.size >= WeatherPlaces.Max
    SettingsCard(skin) {
        SettingRow("Add place", skin, SettingKind.Page, enabled = !full,
            description = if (full) "Up to ${WeatherPlaces.Max} places. Remove one to add another." else "Type a city name once. No location permission is used.",
            value = "${saved.places.size} of ${WeatherPlaces.Max}") { searching = true }
    }
    saved.places.forEachIndexed { index, place ->
        SettingsCard(skin) {
            BasicText(place.name + if (index == 0) " · Home" else "", Modifier.parcRowPadding(), style = settingsText(skin))
            if (place.detail.isNotBlank()) BasicText(place.detail, Modifier.parcRowPadding(), style = settingsText(skin, caption = true))
            SettingRow("Make home", skin, SettingKind.Action, "Home is the first place", enabled = index > 0, icon = "⌂") {
                scope.launch { store.setHome(place.key) }
            }
            SettingRow("Move up", skin, SettingKind.Action, enabled = index > 0, icon = "↑") { scope.launch { store.movePlace(place.key, -1) } }
            SettingRow("Move down", skin, SettingKind.Action, enabled = index < saved.places.lastIndex, icon = "↓") { scope.launch { store.movePlace(place.key, 1) } }
            SettingRow("Remove ${place.name}", skin, SettingKind.Action, "Removes the place and its saved forecast from this phone",
                destructive = true, icon = "−", divider = false) { scope.launch { store.removePlace(place.key) } }
        }
    }
    SettingsCard(skin) {
        SettingsSegmented("Units", skin, UnitChoice.entries.map { it.label }, UnitChoice.entries.indexOf(saved.units),
            divider = saved.places.isNotEmpty()) { index ->
                scope.launch { store.setUnits(UnitChoice.entries[index]) }
            }
        if (saved.places.isNotEmpty()) SettingRow("Forget all places", skin, SettingKind.Action,
            description = "Removes every place and the saved forecasts from this phone", destructive = true, icon = "−", divider = false) {
            scope.launch { store.forgetAll() }
        }
        BasicText("Parc sends only a place's coordinates (rounded to about 1 km) to Open-Meteo, at most every 30 minutes and only while this page is on screen. " +
            "The name you type is sent once to Open-Meteo's city search, with your phone's language code.", Modifier.parcRowPadding(), style = settingsText(skin, caption = true))
    }
    SettingsCard(skin) {
        // T196: the DeepL key for the Translate search mode lives here, in the internet build's settings.
        val keys = remember(context) { DeepLKeyStore.of(context) }
        val translations = remember(context) { TranslateStore(context) }
        TranslateKeyPanel(skin, keys, translations, onSaved = {}, onClose = null)
    }
    if (searching) CitySearchDialog(skin, onDismiss = { searching = false }) { place ->
        scope.launch { store.addPlace(place) }
        searching = false
    }
}

@Composable
private fun CitySearchDialog(skin: Skin, onDismiss: () -> Unit, onPick: (WeatherPlace) -> Unit) {
    val scope = rememberCoroutineScope()
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<WeatherPlace>>(emptyList()) }
    val demo = DemoMode.enabled
    fun search() {
        if (demo) { message = "City search is off in demo mode."; return }
        if (query.isBlank() || busy) return
        busy = true; message = null; results = emptyList()
        scope.launch {
            when (val found = WeatherFetcher.lookUp(query, locale)) {
                is WeatherFetcher.Lookup.Found -> results = found.places
                is WeatherFetcher.Lookup.Failed -> message = found.message
            }
            busy = false
        }
    }
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismiss,
        containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText("Add a place", style = settingsText(skin)) },
        text = { Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            androidx.compose.material3.OutlinedTextField(query, { query = it.take(80) }, textStyle = settingsText(skin),
                label = { BasicText("City name", style = settingsText(skin)) }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search() }))
            SettingRow(if (busy) "Searching…" else "Search", skin, SettingKind.Action, enabled = !busy && query.isNotBlank(), icon = "›") { search() }
            message?.let { BasicText(it, Modifier.padding(Metrics.sectionHeaderGapDp.dp), style = settingsText(skin, skin.colors.warn)) }
            results.forEach { place ->
                SettingRow(place.name, skin, SettingKind.Action, description = place.detail, icon = "+") { onPick(place) }
            }
        } },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { BasicText("Cancel", style = settingsText(skin)) } })
}
