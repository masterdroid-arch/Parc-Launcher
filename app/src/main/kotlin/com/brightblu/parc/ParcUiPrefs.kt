package com.brightblu.parc

import kotlinx.coroutines.channels.awaitClose
import android.content.Context
import com.brightblu.parc.model.Layer

/** Stable `arcId=offset;arcId=offset` codec for persisted rotation offsets. Pure and unit-tested. */
internal object RotationCodec {
    fun encode(map: Map<String, Float>): String = map.entries.joinToString(";") { "${it.key}=${it.value}" }

    fun decode(source: String): Map<String, Float> {
        if (source.isBlank()) return emptyMap()
        val out = HashMap<String, Float>()
        for (part in source.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val key = part.substring(0, eq)
            val value = part.substring(eq + 1).toFloatOrNull() ?: continue
            if (key.isBlank()) continue
            out[key] = value
        }
        return out
    }
}

/** One week, in milliseconds: the search button keeps its caption for the first week of use (T35). */
internal const val SearchHintWeekMillis = 7L * 24 * 60 * 60 * 1000

/**
 * Search-button discoverability rule (T35, pure): the caption and the start pulse stay until the
 * first week is up, or until a search has succeeded, whichever comes first.
 */
internal fun searchHintVisible(now: Long, firstSeen: Long, succeeded: Boolean): Boolean =
    !succeeded && now - firstSeen < SearchHintWeekMillis

/**
 * App-owned transient UI persistence (T29/T35): the top-level mode (Home vs All-apps), the settled
 * rotation offset per arc, and the first-use search hint state. These are UI state, not layout data,
 * so they live here in `:app` rather than in the off-limits `:core:data` settings store.
 */
internal class ParcUiPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("parc_ui", Context.MODE_PRIVATE)

    fun layoutCounts() = com.brightblu.parc.model.LayoutCounts(
        prefs.getInt("arcs_shown", 4), prefs.getInt("icons_per_arc", 0)).normalized()

    fun setLayoutCounts(counts: com.brightblu.parc.model.LayoutCounts) {
        val safe = counts.normalized()
        prefs.edit().putInt("arcs_shown", safe.arcs).putInt("icons_per_arc", safe.icons).apply()
    }

    val countsFlow: kotlinx.coroutines.flow.Flow<com.brightblu.parc.model.LayoutCounts> get() = kotlinx.coroutines.flow.callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(layoutCounts()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(layoutCounts())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /** The persisted top-level mode; folders are never persisted. */
    fun mode(): Layer =
        if (prefs.getString(KeyMode, null) == Layer.AllApps.name) Layer.AllApps else Layer.Home

    fun setMode(layer: Layer) {
        val editor = prefs.edit()
        if (layer == Layer.AllApps) editor.putString(KeyMode, Layer.AllApps.name)
        else editor.remove(KeyMode)
        editor.apply()
    }

    fun rotations(): Map<String, Float> = RotationCodec.decode(prefs.getString(KeyRotations, "").orEmpty())

    fun setRotation(arcId: String, offset: Float) {
        val map = rotations().toMutableMap()
        map[arcId] = offset
        prefs.edit().putString(KeyRotations, RotationCodec.encode(map)).apply()
    }

    /** When the launcher was first used; records [now] the first time it is called. */
    fun searchFirstSeen(now: Long = System.currentTimeMillis()): Long {
        val existing = prefs.getLong(KeySearchFirstSeen, 0L)
        if (existing != 0L) return existing
        prefs.edit().putLong(KeySearchFirstSeen, now).apply()
        return now
    }

    fun searchSucceeded(): Boolean = prefs.getBoolean(KeySearchSucceeded, false)

    fun markSearchSucceeded() {
        if (!searchSucceeded()) prefs.edit().putBoolean(KeySearchSucceeded, true).apply()
    }

    fun searchHintActive(now: Long = System.currentTimeMillis(), firstSeen: Long = searchFirstSeen(now)): Boolean =
        searchHintVisible(now, firstSeen, searchSucceeded())

    /** First-run gesture hint (T30): shown once, until the user dismisses it. */
    fun hintSeen(): Boolean = prefs.getBoolean(KeyHintSeen, false)

    fun markHintSeen() {
        if (!hintSeen()) prefs.edit().putBoolean(KeyHintSeen, true).apply()
    }

    /** Layout preference (T30): staggered by default, aligned spokes when on (RingLayoutOptions). */
    fun alignAcrossArcs(): Boolean = prefs.getBoolean(KeyAlignAcrossArcs, false)

    fun setAlignAcrossArcs(aligned: Boolean) {
        prefs.edit().putBoolean(KeyAlignAcrossArcs, aligned).apply()
    }

    /** Hub joystick aim (T30): on by default; off leaves the hub as a tap target only. */
    fun joystickAim(): Boolean = prefs.getBoolean(KeyJoystickAim, true)

    fun setJoystickAim(enabled: Boolean) {
        prefs.edit().putBoolean(KeyJoystickAim, enabled).apply()
    }

    fun hubDoubleTap(): Boolean = prefs.getBoolean("hub_double_tap", true)

    fun setHubDoubleTap(enabled: Boolean) {
        prefs.edit().putBoolean("hub_double_tap", enabled).apply()
    }

    fun notificationBadges(): Boolean = prefs.getBoolean("notification_badges", true)

    fun setNotificationBadges(enabled: Boolean) {
        prefs.edit().putBoolean("notification_badges", enabled).apply()
    }

    fun badgesInAllApps(): Boolean = prefs.getBoolean("badges_in_all_apps", false)

    fun setBadgesInAllApps(enabled: Boolean) {
        prefs.edit().putBoolean("badges_in_all_apps", enabled).apply()
    }

    /** T97: `Notifications in menus`, on by default. */
    fun menuNotifications(): Boolean = prefs.getBoolean("menu_notifications", true)

    fun setMenuNotifications(enabled: Boolean) {
        prefs.edit().putBoolean("menu_notifications", enabled).apply()
    }

    /** T97: `Show message text`, on by default; off shows only the notification title. */
    fun menuNotificationText(): Boolean = prefs.getBoolean("menu_notification_text", true)

    fun setMenuNotificationText(enabled: Boolean) {
        prefs.edit().putBoolean("menu_notification_text", enabled).apply()
    }

    /** T98: how long an unlock lasts; an unknown stored value falls back to one minute. */
    fun lockTimeout(): com.brightblu.parc.core.data.lock.UnlockTimeout =
        com.brightblu.parc.core.data.lock.UnlockTimeout.fromId(prefs.getString("lock_timeout", null))

    fun setLockTimeout(timeout: com.brightblu.parc.core.data.lock.UnlockTimeout) {
        prefs.edit().putString("lock_timeout", timeout.id).apply()
    }

    /** T98: show `Locked` instead of the folder name while a folder is locked. Off by default. */
    fun lockHideName(): Boolean = prefs.getBoolean("lock_hide_name", false)

    fun setLockHideName(hidden: Boolean) {
        prefs.edit().putBoolean("lock_hide_name", hidden).apply()
    }

    /** T100: Agenda page choices; stored as text so the backup carries them like the other text keys. */
    fun agenda(): com.brightblu.parc.core.data.agenda.AgendaSettings =
        com.brightblu.parc.core.data.agenda.AgendaSettings.decode(prefs.getString("agenda_days", null), prefs.getString("agenda_excluded_calendars", null))

    fun setAgenda(settings: com.brightblu.parc.core.data.agenda.AgendaSettings) {
        prefs.edit().putString("agenda_days", settings.encodeDays())
            .putString("agenda_excluded_calendars", settings.encodeExcluded()).apply()
    }

    /**
     * T75: `Mark work apps`. On is the default and keeps today's look whenever no work profile is
     * installed; off removes the briefcase from every view, including All apps.
     */
    fun markWorkApps(): Boolean = prefs.getBoolean("mark_work_apps", true)

    fun setMarkWorkApps(enabled: Boolean) {
        prefs.edit().putBoolean("mark_work_apps", enabled).apply()
    }

    private companion object {
        const val KeyMode = "mode"
        const val KeyRotations = "rotations"
        const val KeySearchFirstSeen = "search_first_seen"
        const val KeySearchSucceeded = "search_succeeded"
        const val KeyHintSeen = "hint_seen"
        const val KeyAlignAcrossArcs = "align_across_arcs"
        const val KeyJoystickAim = "joystick_aim"
    }
}
