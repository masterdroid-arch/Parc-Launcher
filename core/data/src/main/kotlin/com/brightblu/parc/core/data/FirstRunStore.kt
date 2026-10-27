package com.brightblu.parc.core.data

import kotlinx.coroutines.flow.first

/**
 * When the currently stored layout was first seeded (T62). Apps already installed at that moment are
 * the baseline: an uncategorised one stays unfiled instead of landing in New. Apps installed later
 * still go to New, which is what that folder is for.
 *
 * A null value means "no baseline recorded", which is every install that predates T62; those keep
 * the old reconcile behaviour rather than suddenly emptying New.
 */
internal interface FirstRunStore {
    suspend fun seededAt(): Long?

    suspend fun setSeededAt(millis: Long)
}

/** DataStore-backed [FirstRunStore] over [ParcPrefs]. */
internal class PrefsFirstRunStore(private val prefs: ParcPrefs) : FirstRunStore {

    override suspend fun seededAt(): Long? = prefs.layoutSeededAt.first()

    override suspend fun setSeededAt(millis: Long) = prefs.setLayoutSeededAt(millis)
}
