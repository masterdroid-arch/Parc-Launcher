package com.brightblu.parc

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brightblu.parc.feature.settings.ui.SettingKind
import com.brightblu.parc.feature.settings.ui.SettingRow
import com.brightblu.parc.feature.settings.ui.SettingsCard
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.Metrics
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
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/** T137: the weather variant's implementation of the hook the shared hub code calls. */
internal object WeatherVariant {
    const val Available = true
    const val PageId = "weather"

    @Composable fun Page(skin: Skin, active: Boolean, openOptions: () -> Unit) = WeatherHubPage(skin, active, openOptions, demo = false)
    @Composable fun DemoPage(skin: Skin) = WeatherHubPage(skin, active = false, openOptions = {}, demo = true)
    @Composable fun Options(skin: Skin) = WeatherOptions(skin)
}

/** Asks Open-Meteo for the forecast at most once at a time, and only when the refresh rule allows. */
internal object WeatherFetcher {
    private val lock = Mutex()

    sealed interface Outcome {
        data object NotDue : Outcome
        data object Updated : Outcome
        data class Failed(val problem: WeatherProblem) : Outcome
    }

    /** Never called from the background: only from the page while it is on screen. */
    suspend fun refreshIfDue(store: WeatherStore, now: Long): Outcome = lock.withLock {
        withContext(Dispatchers.IO) {
            val saved = store.read()
            val place = saved.place ?: return@withContext Outcome.NotDue
            if (!WeatherRefresh.shouldFetch(now, saved.lastAttempt, saved.cachedAt, saved.cacheMatchesPlace)) return@withContext Outcome.NotDue
            store.markAttempt(now)
            when (val result = WeatherHttp.get(OpenMeteo.forecastUrl(place))) {
                is FetchResult.Failed -> Outcome.Failed(result.problem)
                is FetchResult.Ok -> if (WeatherParser.forecast(result.body) == null) {
                    ParcLog.w("Weather", "forecast answer could not be read")
                    Outcome.Failed(WeatherProblem.BadResponse)
                } else {
                    store.saveForecast(result.body, now, place.key)
                    Outcome.Updated
                }
            }
        }
    }

    sealed interface Lookup {
        data class Found(val places: List<WeatherPlace>) : Lookup
        data class Failed(val message: String) : Lookup
    }

    suspend fun lookUp(name: String): Lookup = withContext(Dispatchers.IO) {
        when (val result = WeatherHttp.get(OpenMeteo.geocodingUrl(name))) {
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

/** Demo mode: a fixed sample built around the current hour, never fetched and never stored. */
internal object WeatherDemo {
    const val PlaceName = "Sample city"
    const val UpdatedMinutesAgo = 12L

    fun forecast(now: LocalDateTime): WeatherForecast {
        val hour = now.withMinute(0).withSecond(0).withNano(0)
        val temps = listOf(17.0, 18.0, 19.0, 19.0, 18.0, 16.0, 15.0, 14.0, 13.0, 13.0, 12.0, 12.0)
        val codes = listOf(2, 2, 1, 1, 3, 61, 61, 3, 2, 0, 0, 0)
        val rain = listOf(10, 10, 5, 5, 30, 60, 55, 30, 10, 5, 0, 0)
        val hours = temps.indices.map { WeatherHour(hour.plusHours(it.toLong()), temps[it], codes[it], rain[it]) }
        val day = now.toLocalDate()
        val days = listOf(
            WeatherDay(day, 2, 19.0, 11.0, 60, day.atTime(7, 32), day.atTime(18, 21)),
            WeatherDay(day.plusDays(1), 61, 16.0, 10.0, 80, null, null),
            WeatherDay(day.plusDays(2), 3, 17.0, 9.0, 20, null, null),
            WeatherDay(day.plusDays(3), 0, 21.0, 10.0, 0, null, null),
            WeatherDay(day.plusDays(4), 95, 18.0, 12.0, 70, null, null),
        )
        return WeatherForecast(0, WeatherCurrent(now, 17.0, 16.0, 2, 14.0, true), hours, days)
    }
}

@Composable
private fun WeatherHubPage(skin: Skin, active: Boolean, openOptions: () -> Unit, demo: Boolean) {
    val context = LocalContext.current
    val store = remember(context) { WeatherStore(context.applicationContext) }
    val saved by store.saved.collectAsState(initial = null)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val placeKey = saved?.place?.key
    var problem by remember(placeKey) { mutableStateOf<WeatherProblem?>(null) }
    if (!demo) LaunchedEffect(active, placeKey) {
        // Only while the page is on screen and Parc is in front: never in the background.
        while (active) {
            now = System.currentTimeMillis()
            if (placeKey != null) when (val outcome = WeatherFetcher.refreshIfDue(store, now)) {
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
        val local = remember { LocalDateTime.now() }
        val forecast = remember(local) { WeatherDemo.forecast(local) }
        WeatherBody(skin, WeatherPresentation.view(forecast, Instant.now()), WeatherDemo.PlaceName,
            "Updated ${WeatherDemo.UpdatedMinutesAgo} min ago", false, null, unit, hour24, locale)
        return
    }
    val current = saved ?: run { CellLabel("Loading…", skin); return }
    val place = current.place ?: run {
        HubPermissionCard("Type your city once in the page options. Parc then sends only its coordinates to Open-Meteo for the forecast.",
            "Choose city", skin) { openOptions() }
        return
    }
    val body = current.cacheBody.takeIf { current.cacheMatchesPlace }
    val forecast = remember(body) { body?.let(WeatherParser::forecast) }
    val fetchedAt = current.cachedAt
    if (forecast == null || fetchedAt == null) {
        CellLabel(problem?.let { WeatherMessages.problem(it, hasSaved = false) } ?: "Loading the forecast for ${place.name}…", skin)
        return
    }
    val old = WeatherRefresh.isOld(fetchedAt, now)
    val status = problem?.let { WeatherMessages.problem(it, hasSaved = true) }
    WeatherBody(skin, WeatherPresentation.view(forecast, Instant.ofEpochMilli(now)), place.name,
        WeatherRefresh.updatedLabel(fetchedAt, now), old, status, unit, hour24, locale)
}

@Composable
private fun WeatherBody(
    skin: Skin, view: WeatherView, placeName: String, updated: String, old: Boolean, status: String?,
    unit: TempUnit, hour24: Boolean, locale: Locale,
) {
    val size = LocalHubSize.current
    val temp = WeatherUnits.degrees(view.tempC, unit)
    val condition = WeatherCodes.text(view.code)
    val kind = WeatherCodes.kind(view.code)
    val high = WeatherUnits.degrees(view.today?.maxC, unit)
    val low = WeatherUnits.degrees(view.today?.minC, unit)
    val updatedLine = "$placeName · $updated" + if (old) " · old forecast" else ""
    val timeFormat = remember(hour24, locale) { DateTimeFormatter.ofPattern(if (hour24) "HH:mm" else "h:mm a", locale) }
    val hourFormat = remember(hour24, locale) { DateTimeFormatter.ofPattern(if (hour24) "HH" else "h a", locale) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberHubScrollState()), verticalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
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
            BasicText(updatedLine, style = hudLabelStyle(skin).let { if (old) it.copy(color = Color(skin.colors.warn.toInt())) else it }, maxLines = 2)
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
                            WeatherIcon(WeatherCodes.kind(hour.code), isDayAt(hour.time, view), 22f, skin)
                            BasicText(WeatherUnits.degrees(hour.tempC, unit), style = hudValueStyle(skin), maxLines = 1)
                        }
                    }
                }
            }
            val sunrise = view.today?.sunrise?.format(timeFormat)
            val sunset = view.today?.sunset?.format(timeFormat)
            if (sunrise != null || sunset != null)
                BasicText("Sunrise ${sunrise ?: "–"} · Sunset ${sunset ?: "–"}", style = hudValueStyle(skin), maxLines = 2)
            if (size == HubSize.Tall) view.days.forEach { day ->
                DayRow(day, unit, locale, skin)
            }
        }
        status?.let { BasicText(it, style = hudLabelStyle(skin).copy(color = Color(skin.colors.warn.toInt())), maxLines = 3) }
    }
}

private fun isDayAt(time: LocalDateTime, view: WeatherView): Boolean {
    val rise = view.today?.sunrise ?: return true
    val set = view.today.sunset ?: return true
    if (time.toLocalDate() != rise.toLocalDate()) return time.toLocalTime() in rise.toLocalTime()..set.toLocalTime()
    return time in rise..set
}

@Composable
private fun DayRow(day: WeatherDay, unit: TempUnit, locale: Locale, skin: Skin) {
    val name = if (day.date == LocalDate.now()) "Today" else day.date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, locale)
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        contentDescription = "$name: ${WeatherCodes.text(day.code)}, ${WeatherUnits.degrees(day.minC, unit)} to ${WeatherUnits.degrees(day.maxC, unit)}, rain ${WeatherUnits.percent(day.rainPercent)}"
    }, verticalAlignment = Alignment.CenterVertically) {
        BasicText(name, Modifier.weight(1f), style = hudValueStyle(skin), maxLines = 1)
        WeatherIcon(WeatherCodes.kind(day.code), true, 24f, skin)
        BasicText(WeatherUnits.percent(day.rainPercent), Modifier.weight(1f).padding(start = Metrics.gridDp.dp * 2), style = hudLabelStyle(skin), maxLines = 1)
        BasicText("${WeatherUnits.degrees(day.minC, unit)} / ${WeatherUnits.degrees(day.maxC, unit)}", style = hudValueStyle(skin), maxLines = 1)
    }
}

/** The Weather page options: city (typed once), units, and a way to forget the city. */
@Composable
private fun WeatherOptions(skin: Skin) {
    val context = LocalContext.current
    val store = remember(context) { WeatherStore(context.applicationContext) }
    val saved by store.saved.collectAsState(initial = WeatherSaved())
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    SettingsCard(skin) {
        SettingRow("City", skin, SettingKind.Page, description = "Type a city name once. No location permission is used.",
            value = saved.place?.let { it.name } ?: "Not set") { searching = true }
        BasicText("Units", Modifier.padding(Metrics.gridDp.dp * 2), style = settingsText(skin))
        UnitChoice.entries.forEach { choice ->
            SettingRow(choice.label, skin, SettingKind.Toggle, checked = saved.units == choice) {
                scope.launch { store.setUnits(choice) }
            }
        }
        if (saved.place != null) SettingRow("Forget city", skin, SettingKind.Action,
            description = "Removes the city and the saved forecast from this phone", destructive = true, icon = "−") {
            scope.launch { store.forget() }
        }
        BasicText("Parc sends only the city's coordinates (rounded to about 1 km) to Open-Meteo, at most every 30 minutes and only while this page is on screen. " +
            "The name you type is sent once to Open-Meteo's city search.", Modifier.padding(Metrics.gridDp.dp * 4), style = settingsText(skin, caption = true))
    }
    if (searching) CitySearchDialog(skin, onDismiss = { searching = false }) { place ->
        scope.launch { store.setPlace(place) }
        searching = false
    }
}

@Composable
private fun CitySearchDialog(skin: Skin, onDismiss: () -> Unit, onPick: (WeatherPlace) -> Unit) {
    val scope = rememberCoroutineScope()
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
            when (val found = WeatherFetcher.lookUp(query)) {
                is WeatherFetcher.Lookup.Found -> results = found.places
                is WeatherFetcher.Lookup.Failed -> message = found.message
            }
            busy = false
        }
    }
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismiss,
        containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText("City for Weather", style = settingsText(skin)) },
        text = { Column {
            androidx.compose.material3.OutlinedTextField(query, { query = it.take(80) }, textStyle = settingsText(skin),
                label = { BasicText("City name", style = settingsText(skin)) }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search() }))
            SettingRow(if (busy) "Searching…" else "Search", skin, SettingKind.Action, enabled = !busy && query.isNotBlank(), icon = "›") { search() }
            message?.let { BasicText(it, Modifier.padding(Metrics.gridDp.dp * 2), style = settingsText(skin, skin.colors.warn)) }
            results.forEach { place ->
                SettingRow(place.name, skin, SettingKind.Action, description = place.detail, icon = "+") { onPick(place) }
            }
        } },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { BasicText("Cancel", style = settingsText(skin)) } })
}
