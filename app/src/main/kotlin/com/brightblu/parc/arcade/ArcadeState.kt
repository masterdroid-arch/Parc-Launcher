package com.brightblu.parc.arcade

import java.time.LocalDate

/**
 * T200: what the arcade remembers, forever and in the backup: which games are unlocked, the best score of each, and the day
 * the earned-unlock watch began (a calm day only counts if it is a full day after that). Three small text values in the hub
 * DataStore (`arcade_games`, `arcade_best`, `arcade_since`); old, empty or garbled text reads as nothing unlocked.
 */
internal data class ArcadeState(
    val unlocked: Set<String> = emptySet(),
    val best: Map<String, Int> = emptyMap(),
    val since: LocalDate? = null,
) {
    /** The arcade exists (tab, settings row) only while at least one game this build knows is unlocked. */
    val open: Boolean get() = unlocked.any { it in ArcadeCatalog.ids }
    fun isUnlocked(gameId: String) = gameId in unlocked
    fun bestOf(game: ArcadeGame): Int = best[game.bestKey] ?: 0
    fun unlock(gameIds: Collection<String>): ArcadeState = if (gameIds.all { it in unlocked }) this else copy(unlocked = unlocked + gameIds)
    /** Only ever raises a best score. */
    fun withBest(game: ArcadeGame, score: Int): ArcadeState =
        if (score <= bestOf(game)) this else copy(best = best + (game.bestKey to score))

    companion object {
        const val GamesKey = "arcade_games"
        const val BestKey = "arcade_best"
        const val SinceKey = "arcade_since"

        fun decodeGames(raw: String?): Set<String> =
            raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() && it.length <= 32 && it.all { c -> c.isLowerCase() || c.isDigit() || c == '_' } }.toSet()
        fun encodeGames(games: Set<String>): String = games.sorted().joinToString(",")

        fun decodeBest(raw: String?): Map<String, Int> = buildMap {
            raw.orEmpty().split(',').forEach { entry ->
                val parts = entry.split(':')
                val score = parts.getOrNull(1)?.trim()?.toIntOrNull()
                val key = parts.getOrNull(0)?.trim().orEmpty()
                if (parts.size == 2 && key.isNotEmpty() && score != null && score >= 0) put(key, score)
            }
        }
        fun encodeBest(best: Map<String, Int>): String = best.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }

        fun decodeSince(raw: String?): LocalDate? = runCatching { LocalDate.parse(raw.orEmpty().trim()) }.getOrNull()

        fun decode(games: String?, best: String?, since: String?) =
            ArcadeState(decodeGames(games), decodeBest(best), decodeSince(since))
    }
}
