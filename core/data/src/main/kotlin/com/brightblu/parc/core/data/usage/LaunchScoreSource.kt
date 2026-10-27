package com.brightblu.parc.core.data.usage

import com.brightblu.parc.model.AppId

/**
 * Decayed launch scores for the usage layer (T34).
 *
 * The frozen `UsageRepository` answers folder questions (`rank(folderId)`) and recents, but the
 * usage layer needs the raw decayed score of any app to compare a challenger with an incumbent, so
 * this narrow interface exposes exactly that and nothing else. [UsageRepositoryImpl] implements it
 * over the same in-memory launch log; a test can implement it with a map.
 */
interface LaunchScoreSource {
    /**
     * Score per app with at least one launch; an absent app scores 0. [nowMillis] is the instant the
     * scores are decayed to. Safe to call from any thread.
     */
    fun scores(nowMillis: Long): Map<AppId, Double>

    /**
     * Returns once the cold-start read of the launch log has finished (or failed). The usage layer
     * must not rank from a log that is still empty, so it awaits this before its first pass.
     */
    suspend fun awaitLoaded()
}
