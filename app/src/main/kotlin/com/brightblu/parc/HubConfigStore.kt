package com.brightblu.parc

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

internal val Context.hubDataStore by preferencesDataStore(name = "stats_hub",
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler("stats_hub"))
internal class HubConfigStore(context: Context) {
    private val store = context.applicationContext.hubDataStore
    private val key = stringPreferencesKey("config_v1")
    private val clockKey = stringPreferencesKey("clock_options")
    private val sizeKey = stringPreferencesKey("hub_size")
    private val pageKey = stringPreferencesKey("last_page")
    private val stepOptionsKey = stringPreferencesKey("steps_options")
    private val rhythmOptionsKey = stringPreferencesKey("rhythm_options")
    private val skyPlaceKey = stringPreferencesKey("sky_place")
    private val yearCountdownsKey = stringPreferencesKey("year_countdowns")
    private val yearMetricKey = stringPreferencesKey("year_metric")
    private val yearColourKey = stringPreferencesKey("year_colour")
    private val todayOptionsKey = stringPreferencesKey(TodayOptions.Key)
    private val arcadeGamesKey = stringPreferencesKey(com.brightblu.parc.arcade.ArcadeState.GamesKey)
    private val arcadeBestKey = stringPreferencesKey(com.brightblu.parc.arcade.ArcadeState.BestKey)
    private val arcadeSinceKey = stringPreferencesKey(com.brightblu.parc.arcade.ArcadeState.SinceKey)
    private fun arcadeOf(prefs: Preferences) = com.brightblu.parc.arcade.ArcadeState.decode(prefs[arcadeGamesKey], prefs[arcadeBestKey], prefs[arcadeSinceKey])
    private fun configOf(prefs: Preferences) = HubConfig.decode(prefs[key].orEmpty())
        .copy(size = HubSize.decode(prefs[sizeKey]), clock = ClockOptions.decode(prefs[clockKey]), arcadeOpen = arcadeOf(prefs).open)
    /** T200: the hidden arcade's unlocked games, best scores and the day the earned-unlock watch began. Empty until something is unlocked. */
    val arcade = store.data.map { arcadeOf(it) }.catch {
        if (it is IOException) emit(com.brightblu.parc.arcade.ArcadeState()) else throw it
    }
    /** Applies [change] to the arcade state and saves it; returns the state before and after. */
    suspend fun arcadeEdit(change: (com.brightblu.parc.arcade.ArcadeState) -> com.brightblu.parc.arcade.ArcadeState): Pair<com.brightblu.parc.arcade.ArcadeState, com.brightblu.parc.arcade.ArcadeState> {
        var result = com.brightblu.parc.arcade.ArcadeState() to com.brightblu.parc.arcade.ArcadeState()
        store.edit {
            val before = arcadeOf(it)
            val after = change(before)
            result = before to after
            if (after != before) {
                if (after.unlocked.isEmpty()) it.remove(arcadeGamesKey) else it[arcadeGamesKey] = com.brightblu.parc.arcade.ArcadeState.encodeGames(after.unlocked)
                if (after.best.isEmpty()) it.remove(arcadeBestKey) else it[arcadeBestKey] = com.brightblu.parc.arcade.ArcadeState.encodeBest(after.best)
                val since = after.since
                if (since == null) it.remove(arcadeSinceKey) else it[arcadeSinceKey] = since.toString()
            }
        }
        return result
    }
    val todayOptions = store.data.map { TodayOptions.decode(it[todayOptionsKey]) }.catch {
        if (it is IOException) emit(TodayOptions()) else throw it
    }
    suspend fun setTodayOptions(options: TodayOptions) {
        store.edit { it[todayOptionsKey] = options.encode() }
    }
    val lastPage = store.data.map { it[pageKey] ?: TodayPage.Id }.catch {
        if (it is IOException) emit(TodayPage.Id) else throw it
    }
    suspend fun rememberPage(page: String) {
        store.edit { it[pageKey] = page }
    }
    val config = store.data.map { configOf(it) }.catch {
        if (it is IOException) emit(HubConfig()) else throw it
    }
    /** T185: the remembered period of each period-aware page. */
    val periods = store.data.map { prefs -> HubPeriods.decode { prefs[stringPreferencesKey(it)] } }.catch {
        if (it is IOException) emit(HubPeriods.decode { null }) else throw it
    }
    suspend fun setPeriod(page: String, period: com.brightblu.parc.core.data.ledger.LedgerPeriod) {
        if (page !in HubPeriods.pages) return
        store.edit { it[stringPreferencesKey(HubPeriods.keyName(page))] = period.id }
    }
    /** T187: stride length and daily goal of the Steps page. Old, empty or garbled text reads as the defaults. */
    val stepOptions = store.data.map { com.brightblu.parc.core.data.steps.StepOptions.decode(it[stepOptionsKey]) }.catch {
        if (it is IOException) emit(com.brightblu.parc.core.data.steps.StepOptions()) else throw it
    }
    suspend fun setStepOptions(change: (com.brightblu.parc.core.data.steps.StepOptions) -> com.brightblu.parc.core.data.steps.StepOptions) {
        store.edit { it[stepOptionsKey] = change(com.brightblu.parc.core.data.steps.StepOptions.decode(it[stepOptionsKey])).encode() }
    }
    /** T188: the hours that count for the longest phone-free stretch. Old, empty or garbled text reads as 06:00 to 24:00. */
    val rhythmOptions = store.data.map { com.brightblu.parc.core.data.rhythm.RhythmOptions.decode(it[rhythmOptionsKey]) }.catch {
        if (it is IOException) emit(com.brightblu.parc.core.data.rhythm.RhythmOptions()) else throw it
    }
    suspend fun setRhythmOptions(change: (com.brightblu.parc.core.data.rhythm.RhythmOptions) -> com.brightblu.parc.core.data.rhythm.RhythmOptions) {
        store.edit { it[rhythmOptionsKey] = change(com.brightblu.parc.core.data.rhythm.RhythmOptions.decode(it[rhythmOptionsKey])).encode() }
    }
    /** T189: the place typed by hand for the Sky page (it wins over the weather home place). Old, empty or garbled text reads as none. */
    val skyPlace = store.data.map { SkyPlaceText.decode(it[skyPlaceKey]) }.catch {
        if (it is IOException) emit(null) else throw it
    }
    suspend fun setSkyPlace(place: SkyPlace?) {
        store.edit { if (place == null) it.remove(skyPlaceKey) else it[skyPlaceKey] = SkyPlaceText.encode(place) }
    }
    /** T190: the dates the Year page counts down to. Old, empty or garbled text reads as what can be read. */
    val yearCountdowns = store.data.map { Countdowns.decode(it[yearCountdownsKey]) }.catch {
        if (it is IOException) emit(emptyList()) else throw it
    }
    suspend fun setYearCountdowns(change: (List<Countdown>) -> List<Countdown>) {
        store.edit {
            val next = change(Countdowns.decode(it[yearCountdownsKey]))
            if (next.isEmpty()) it.remove(yearCountdownsKey) else it[yearCountdownsKey] = Countdowns.encode(next)
        }
    }
    /** T190: what colours the past dots of the Year grid. Unknown or missing text reads as no colour. */
    val yearMetric = store.data.map { YearMetric.fromId(it[yearMetricKey]) }.catch {
        if (it is IOException) emit(YearMetric.None) else throw it
    }
    suspend fun setYearMetric(metric: YearMetric) {
        store.edit { it[yearMetricKey] = metric.id }
    }
    /** T238: the colour of the filled Year dots; unknown or missing text reads as Accent. */
    val yearColour = store.data.map { YearColour.fromId(it[yearColourKey]) }.catch {
        if (it is IOException) emit(YearColour.Accent) else throw it
    }
    suspend fun setYearColour(colour: YearColour) {
        store.edit { it[yearColourKey] = colour.id }
    }
    suspend fun update(change: (HubConfig) -> HubConfig) {
        store.edit {
            val next = change(configOf(it))
            it[key] = next.encode()
            it[sizeKey] = next.size.id
            it[clockKey] = next.clock.encode()
        }
    }
}
