package com.brightblu.parc.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.brightblu.parc.core.data.comms.CommsCannedReplies
import com.brightblu.parc.core.data.comms.CommsPackages
import com.brightblu.parc.core.data.skins.WallpaperLook
import com.brightblu.parc.core.data.skins.WallpaperMode
import com.brightblu.parc.model.AppId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Haptic strength, DESIGN §2.7. Stored by name so a later token can be added without a migration. */
enum class HapticsLevel { Off, Light, Strong }

/** Immutable settings snapshot (DESIGN §2.7/§3): tokens only, no UI state. Non-null. */
data class ParcSettings(
    val skinId: String = ParcPrefs.DefaultSkinId,
    val haptics: HapticsLevel = HapticsLevel.Light,
    val clockVisible: Boolean = true,
    val statsVisible: Boolean = ParcPrefs.DefaultStatsVisible,
    val statsExpanded: Boolean = ParcPrefs.DefaultStatsExpanded,
    val onboardingCompleted: Boolean = false,
    /** T96: light/dark choice and source look of the generated Wallpaper skin. */
    val wallpaperMode: WallpaperMode = WallpaperMode.System,
    val wallpaperLook: WallpaperLook = WallpaperLook.Midnight,
)

/**
 * DataStore-backed settings: skin, haptics, clock, stats module, onboarding (PROJECT.md §6.3 keeps
 * pref tokens here and the layout in Room). Thread-safe: [settings] is a cold flow over DataStore's
 * atomic snapshot, writes use `updateData`. Unknown stored values fall back to the default instead of
 * throwing, so a downgrade can never leave the launcher without settings. Pure parsing lives in
 * [toSettings].
 */
class ParcPrefs(private val store: DataStore<Preferences>) {

    /**
     * OPUS6 (audit F6): reads never crash the process. A storage error (an [java.io.IOException],
     * which includes a damaged file the corruption handler could not recover) reads as defaults.
     */
    private val safeData: Flow<Preferences> = store.data.catch {
        if (it is java.io.IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw it
    }

    val settings: Flow<ParcSettings> = safeData.map { it.toSettings() }

    /**
     * Learned usage-slot positions as stored, e.g. `A2:0,A2:1,A2:2,A2:3` (T34). Null when no slot
     * list has ever been written; the caller falls back to its default and stores that. The raw
     * string is kept here and decoded by the usage layer, which owns the format.
     */
    val usageSlots: Flow<String?> = safeData.map { it[KeyUsageSlots] }

    /** The 04:00 day key of the last usage re-rank, or null when none has run (T34). */
    val usageRankDay: Flow<Long?> = safeData.map { it[KeyUsageRankDay] }

    /** Seed generation the stored layout was built from, or null when no marker was written (T34). */
    val layoutGeneration: Flow<Int?> = safeData.map { it[KeyLayoutGeneration] }

    /**
     * Comms master switch (T55, default on). Off makes the inbox drop everything and publish an
     * empty conversation list until it is switched back on.
     */
    val commsEnabled: Flow<Boolean> = safeData.map { it[KeyCommsEnabled] ?: true }

    /**
     * Package names allowed to feed the Comms page (T55). Absent means the card's default set;
     * an explicitly stored empty set is preserved and shows nothing.
     */
    val commsApps: Flow<Set<String>> = safeData.map {
        it[KeyCommsApps] ?: CommsPackages.Default
    }

    /**
     * Quick replies for the Comms page (T76) in chip order. A missing key means the card defaults;
     * an explicitly stored empty list is kept empty.
     */
    val commsCannedReplies: Flow<List<String>> = safeData.map {
        decodeCannedReplies(it[KeyCommsCannedReplies])
    }

    /** The card's `Keep history` switch (T76, default on). */
    val commsKeepHistory: Flow<Boolean> = safeData.map { it[KeyCommsKeepHistory] ?: true }

    /** The four main apps the user picked (T62), in slot order. Empty until the picker writes. */
    val mainApps: Flow<List<AppId>> = safeData.map { decodeMainApps(it[KeyMainApps]) }

    /** Bumped on every picker write; lets the layout apply each choice exactly once (T62). */
    val mainAppsRevision: Flow<Long> = safeData.map { it[KeyMainAppsRevision] ?: 0L }

    /** Revision the layout last applied, so a cold start finishes an interrupted picker save. */
    val mainAppsAppliedRevision: Flow<Long> = safeData.map { it[KeyMainAppsAppliedRevision] ?: 0L }

    /** Epoch millis of the first seed (T62); apps installed before it are the baseline. */
    val layoutSeededAt: Flow<Long?> = safeData.map { it[KeyLayoutSeededAt] }

    suspend fun setSkinId(id: String) {
        store.edit { it[KeySkinId] = id }
    }

    suspend fun setHaptics(level: HapticsLevel) {
        store.edit { it[KeyHaptics] = level.name }
    }

    suspend fun setWallpaperMode(mode: WallpaperMode) {
        store.edit { it[KeyWallpaperMode] = mode.name }
    }

    suspend fun setWallpaperLook(look: WallpaperLook) {
        store.edit { it[KeyWallpaperLook] = look.name }
    }

    suspend fun setClockVisible(visible: Boolean) {
        store.edit { it[KeyClockVisible] = visible }
    }

    suspend fun setStatsVisible(visible: Boolean) {
        store.edit { it[KeyStatsVisible] = visible }
    }

    suspend fun setStatsExpanded(expanded: Boolean) {
        store.edit { it[KeyStatsExpanded] = expanded }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        store.edit { it[KeyOnboardingCompleted] = completed }
    }

    /** Stores the encoded learned-slot list (T34); see [usageSlots]. */
    suspend fun setUsageSlots(value: String) {
        store.edit { it[KeyUsageSlots] = value }
    }

    /** Stores the 04:00 day key of the last usage re-rank (T34). */
    suspend fun setUsageRankDay(day: Long) {
        store.edit { it[KeyUsageRankDay] = day }
    }

    /** Stores the seed generation the layout was built from (T34). */
    suspend fun setLayoutGeneration(generation: Int) {
        store.edit { it[KeyLayoutGeneration] = generation }
    }

    /** Turns the Comms data layer on or off (T55); an off layer drops its in-memory messages. */
    suspend fun setCommsEnabled(enabled: Boolean) {
        store.edit { it[KeyCommsEnabled] = enabled }
    }

    /** Replaces the Comms app allow-list (T55). Stored as package names; an empty set is a valid
     * value and is kept, because an empty list must show nothing rather than restore the defaults.
     */
    suspend fun setCommsApps(packages: Set<String>) {
        store.edit { it[KeyCommsApps] = packages.toSet() }
    }

    /** Replaces the canned replies (T76); [CommsCannedReplies.sanitize] is applied on write. */
    suspend fun setCommsCannedReplies(replies: List<String>) {
        store.edit { it[KeyCommsCannedReplies] = encodeCannedReplies(replies) }
    }

    /** Turns the in-memory Comms history on or off (T76). */
    suspend fun setCommsKeepHistory(keep: Boolean) {
        store.edit { it[KeyCommsKeepHistory] = keep }
    }

    /**
     * Replaces the four main apps (T62) and bumps the revision in the same atomic write. The layout
     * repository watches the revision, so an edit reaches Home once and is never replayed later.
     */
    suspend fun setMainApps(ids: List<AppId>) {
        store.edit { prefs ->
            prefs[KeyMainApps] = encodeMainApps(ids)
            prefs[KeyMainAppsRevision] = (prefs[KeyMainAppsRevision] ?: 0L) + 1L
        }
    }

    /** Records the revision the layout has applied (T62). */
    suspend fun setMainAppsAppliedRevision(revision: Long) {
        store.edit { it[KeyMainAppsAppliedRevision] = revision }
    }

    /** Records when the first seed was written (T62); baseline apps never enter New. */
    suspend fun setLayoutSeededAt(millis: Long) {
        store.edit { it[KeyLayoutSeededAt] = millis }
    }

    companion object {
        const val DefaultSkinId = "midnight"
        const val DefaultStatsVisible = true
        const val DefaultStatsExpanded = false
        const val StoreName = "parc_settings"
        internal val KeySkinId = stringPreferencesKey("skin_id")
        internal val KeyWallpaperMode = stringPreferencesKey("wallpaper_skin_mode")
        internal val KeyWallpaperLook = stringPreferencesKey("wallpaper_skin_look")
        internal val KeyHaptics = stringPreferencesKey("haptics")
        internal val KeyClockVisible = booleanPreferencesKey("clock_visible")
        internal val KeyStatsVisible = booleanPreferencesKey("stats_visible")
        internal val KeyStatsExpanded = booleanPreferencesKey("stats_expanded")
        internal val KeyOnboardingCompleted = booleanPreferencesKey("onboarding_completed")

        /** T34 key names as the card asks for them: `usageSlots` and its day marker. */
        internal val KeyUsageSlots = stringPreferencesKey("usageSlots")
        internal val KeyUsageRankDay = longPreferencesKey("usage_rank_day")
        internal val KeyLayoutGeneration = intPreferencesKey("layout_generation")

        /** T55: the Comms switch and the allow-list of posting packages. */
        internal val KeyCommsEnabled = booleanPreferencesKey("comms_enabled")
        internal val KeyCommsApps = stringSetPreferencesKey("comms_apps")

        /** T76: canned replies (newline-separated, ordered) and the history switch. */
        internal val KeyCommsCannedReplies = stringPreferencesKey("comms_canned_replies")
        internal val KeyCommsKeepHistory = booleanPreferencesKey("comms_keep_history")

        /** T62: the picker's four apps, its write revision and the revision the layout applied. */
        internal val KeyMainApps = stringPreferencesKey("main_apps")
        internal val KeyMainAppsRevision = longPreferencesKey("main_apps_revision")
        internal val KeyMainAppsAppliedRevision = longPreferencesKey("main_apps_applied_revision")

        /** T62: when the layout was first seeded, the baseline for the New folder. */
        internal val KeyLayoutSeededAt = longPreferencesKey("layout_seeded_at")

        /** Builds the process-wide settings store for an app context. */
        fun create(context: Context): ParcPrefs = ParcPrefs(context.applicationContext.parcSettingsStore)
    }
}

/** Decodes one DataStore snapshot; missing or unknown values become defaults. */
internal fun Preferences.toSettings(): ParcSettings = ParcSettings(
    skinId = this[ParcPrefs.KeySkinId]?.takeIf { it.isNotBlank() } ?: ParcPrefs.DefaultSkinId,
    haptics = this[ParcPrefs.KeyHaptics]?.let { name ->
        HapticsLevel.entries.firstOrNull { it.name == name }
    } ?: HapticsLevel.Light,
    clockVisible = this[ParcPrefs.KeyClockVisible] ?: true,
    statsVisible = this[ParcPrefs.KeyStatsVisible] ?: ParcPrefs.DefaultStatsVisible,
    statsExpanded = this[ParcPrefs.KeyStatsExpanded] ?: ParcPrefs.DefaultStatsExpanded,
    onboardingCompleted = this[ParcPrefs.KeyOnboardingCompleted] ?: false,
    wallpaperMode = this[ParcPrefs.KeyWallpaperMode]?.let { n -> WallpaperMode.entries.firstOrNull { it.name == n } } ?: WallpaperMode.System,
    wallpaperLook = this[ParcPrefs.KeyWallpaperLook]?.let { n -> WallpaperLook.entries.firstOrNull { it.name == n } } ?: WallpaperLook.Midnight,
)

/**
 * Encodes canned replies in chip order, one per line. [CommsCannedReplies.sanitize] removes line
 * breaks, blanks, duplicates and entries above the ceiling, so a plain newline join needs no
 * escaping. An explicitly empty list is stored as an empty string, which is distinct from a missing
 * key: missing means the card defaults.
 */
internal fun encodeCannedReplies(replies: List<String>): String =
    CommsCannedReplies.sanitize(replies).joinToString("\n")

/** Decodes [encodeCannedReplies]; a missing value is the card default, a stored blank is empty. */
internal fun decodeCannedReplies(value: String?): List<String> =
    if (value == null) {
        CommsCannedReplies.Default
    } else {
        CommsCannedReplies.sanitize(value.split('\n'))
    }

internal val Context.parcSettingsStore by preferencesDataStore(name = ParcPrefs.StoreName,
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler(ParcPrefs.StoreName))
