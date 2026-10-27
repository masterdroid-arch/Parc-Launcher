package com.brightblu.parc.core.data

import com.brightblu.parc.model.AppId
import kotlinx.coroutines.flow.first

/** A picker write that the layout has not applied yet: [revision] ordered, [ids] in slot order. */
internal data class MainAppsUpdate(val revision: Long, val ids: List<AppId>)

/**
 * Storage boundary for the four main apps (T62). The picker writes a list and bumps a revision; the
 * layout repository applies anything newer than the last applied revision, so an edit reaches Home
 * once without ever overriding a later manual arrangement.
 */
internal interface MainAppsStore {
    suspend fun appliedRevision(): Long

    /** The newest picker list when it is newer than [applied], otherwise null. */
    suspend fun pending(applied: Long): MainAppsUpdate?

    suspend fun markApplied(revision: Long)
}

/** DataStore-backed [MainAppsStore] over [ParcPrefs]. */
internal class PrefsMainAppsStore(private val prefs: ParcPrefs) : MainAppsStore {

    override suspend fun appliedRevision(): Long = prefs.mainAppsAppliedRevision.first()

    override suspend fun pending(applied: Long): MainAppsUpdate? {
        val revision = prefs.mainAppsRevision.first()
        if (revision <= applied) return null
        return MainAppsUpdate(revision, prefs.mainApps.first())
    }

    override suspend fun markApplied(revision: Long) {
        prefs.setMainAppsAppliedRevision(revision)
    }
}

/**
 * Encodes the selection in slot order. AppId values contain no newline, so a newline join needs no
 * escaping; an explicitly empty list is stored as an empty string, distinct from "never chosen".
 */
internal fun encodeMainApps(ids: List<AppId>): String =
    ids.map { it.value }.distinct().joinToString("\n")

/** Decodes [encodeMainApps]; a missing or blank value is an empty selection. */
internal fun decodeMainApps(value: String?): List<AppId> =
    value.orEmpty()
        .split('\n')
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .map(::AppId)
        .toList()
