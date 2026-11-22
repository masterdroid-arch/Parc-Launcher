package com.brightblu.parc

import androidx.compose.runtime.Composable
import com.brightblu.parc.model.Skin

/**
 * T137: the normal builds (personal and play) have no Weather page. This stub is all they compile;
 * the page, its network code and its store live only in `src/personalWeather`. The build checks
 * that these builds have no INTERNET permission and no network classes.
 */
internal object WeatherVariant {
    const val Available = false
    const val PageId = "weather"

    @Composable fun Page(skin: Skin, active: Boolean, openOptions: () -> Unit) = Unit
    @Composable fun DemoPage(skin: Skin) = Unit
    @Composable fun Options(skin: Skin) = Unit

    /** T194: these builds have no internet, so no Translate search mode. */
    fun extraSearchModes(): List<com.brightblu.parc.feature.search.SearchMode> = emptyList()

    /** These builds have no Weather places, so nothing of Weather is in their backup. */
    fun backupStores(context: android.content.Context): List<com.brightblu.parc.core.data.backup.BackupStore> = emptyList()

    fun hasBackupKey(context: android.content.Context) = false

    /** T189: the Sky page's place. These builds have no weather places, so there is none; the page uses the typed one. */
    @Composable fun homePlace(): SkyPlace? = null
    /** T198: no saved places in these builds, so no weather for a named one. */
    @Composable fun placeNow(nameContains: String): PlaceWeatherNow? = null
    suspend fun today(context: android.content.Context, now: java.time.Instant): TodayWeather? = null
    suspend fun todayPlace(context: android.content.Context): SkyPlace? = null
}
