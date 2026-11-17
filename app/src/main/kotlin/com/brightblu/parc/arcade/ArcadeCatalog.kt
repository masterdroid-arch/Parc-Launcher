package com.brightblu.parc.arcade

/**
 * T200: the hidden arcade is a registry of games. Each game has a stable lowercase [id], the [title] shown on its tile once it
 * is unlocked, the id of its unlock rule ([unlockRuleId]) and the key its best score is stored under ([bestKey]).
 *
 * To add a game in a later release:
 *  1. write its own code in its own file (Orbit is `OrbitSim.kt` and `OrbitGame.kt`; the arcade shell knows nothing about it);
 *  2. add one [ArcadeGame] line to [ArcadeCatalog.games] with a new unlock rule id;
 *  3. add one `when` branch for its id in `ArcadeGameUi` (ArcadePage.kt), which gives the shell the game's composable;
 *  4. wire whatever detector or check fires its rule through `ArcadeUnlocker.fire(ruleId)`.
 * Nothing else changes: the tile, the stored best score, the backup and the invisibility of a locked game follow from the entry.
 */
internal data class ArcadeGame(val id: String, val title: String, val unlockRuleId: String, val bestKey: String)

internal object ArcadePage { const val Id = "arcade" }

/** Ids of unlock rules. A rule can be met in more than one way; every way reports the same id. */
internal object ArcadeRules {
    /** Orbit: a calm day (screen time under the 14-day average) or three turns of a Home arc and one back. */
    const val CalmDayOrArcSpin = "calm_day_or_arc_spin"

    /** Lander (T211): the first day that reaches the step goal, or seven taps on the Sky page's moon within five seconds. */
    const val StepGoalOrMoonTap = "step_goal_or_moon_tap"
}

internal object ArcadeCatalog {
    val games: List<ArcadeGame> = listOf(
        ArcadeGame(id = "orbit", title = "Orbit", unlockRuleId = ArcadeRules.CalmDayOrArcSpin, bestKey = "orbit"),
        ArcadeGame(id = "lander", title = "Lander", unlockRuleId = ArcadeRules.StepGoalOrMoonTap, bestKey = "lander"),
    )
    val ids: Set<String> = games.map { it.id }.toSet()
    fun game(id: String): ArcadeGame? = games.firstOrNull { it.id == id }
    fun lockedBy(ruleId: String): List<ArcadeGame> = games.filter { it.unlockRuleId == ruleId }
}
