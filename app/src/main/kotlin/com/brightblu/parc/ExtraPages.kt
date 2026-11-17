package com.brightblu.parc

import android.content.Context
import androidx.compose.runtime.Composable
import com.brightblu.parc.core.data.backup.BackupStore
import com.brightblu.parc.model.Skin
import java.time.Instant
import java.time.ZoneId

/**
 * T198: a hub page that only some builds carry. The public sources know nothing about what the pages are:
 * `PersonalExtras.pages` is empty here (play build and the public snapshot) and is filled by a source set that is
 * not exported. A page has a stable lowercase id, starts hidden, and is otherwise a normal hub page.
 */
internal class ExtraHubPage(
    val id: String,
    val title: String,
    val template: HubTemplateSpec,
    val options: HubPageOptionSpec,
    val body: @Composable (skin: Skin, active: Boolean) -> Unit,
    val demo: @Composable (skin: Skin) -> Unit,
    val optionChoices: @Composable (skin: Skin) -> Unit,
    /** An optional Today line; null when the page has nothing to say now. */
    val todayLine: suspend (context: Context, now: Instant, zone: ZoneId) -> String?,
)

internal interface ExtraPageSet {
    val pages: List<ExtraHubPage>
    fun backupStores(context: Context): List<BackupStore>
    /** 1.5.1: a second clock zone shown on Today until the user picks one in the page options. Public builds have none. */
    val defaultClockZone: String? get() = null
}

internal fun extraPage(id: String): ExtraHubPage? = PersonalExtras.pages.firstOrNull { it.id == id }

/** The weather now at a saved place, as far as the build knows it. */
internal data class PlaceWeatherNow(val place: String, val now: String, val old: Boolean)
