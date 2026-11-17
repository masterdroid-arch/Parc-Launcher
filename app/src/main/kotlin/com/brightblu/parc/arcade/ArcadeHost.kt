package com.brightblu.parc.arcade

import androidx.compose.runtime.staticCompositionLocalOf
import com.brightblu.parc.core.data.ledger.DayLedger
import com.brightblu.parc.core.data.steps.StepOptions
import com.brightblu.parc.HubConfigStore
import com.brightblu.parc.ring.JoystickCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * T200: the bridge between Home and the game on the Arcade page. While a game runs ([playing]) and the user has the hub joystick
 * switched on, the ring renderer hands the hub touch to [onStick] instead of aiming at apps; the game reads [stickX]/[stickY].
 * The page also reads [playing] to keep the hub's page swipe out of the way of a drag that steers.
 */
internal class ArcadeHost(val joystickEnabled: () -> Boolean, val hapticsEnabled: () -> Boolean) : JoystickCapture {
    @Volatile var playing = false
    @Volatile var stickX = 0f
    @Volatile var stickY = 0f
    @Volatile var stickActive = false
    override fun captures(): Boolean = playing && joystickEnabled()
    override fun onStick(dxDp: Float, dyDp: Float, active: Boolean) { stickX = dxDp; stickY = dyDp; stickActive = active }
}

internal val LocalArcadeHost = staticCompositionLocalOf<ArcadeHost?> { null }

/** T211: the Sky page's moon taps report here; null in previews, the gallery and demo mode. */
internal val LocalArcadeUnlocker = staticCompositionLocalOf<ArcadeUnlocker?> { null }

/** Set at the moment a game unlocks; the tab strip springs the new tab in the next time it draws the arcade tab (once). */
internal object ArcadeReveal {
    @Volatile var pending = false
    fun take(): Boolean { val p = pending; pending = false; return p }
}

/**
 * T200: watches for the two ways of unlocking, and remembers the result in the hub DataStore (so it is in the backup). One per
 * process, made in [com.brightblu.parc.AppGraph]. [unlocked] emits the id of each game as it unlocks, for the pulse and the haptic.
 */
internal class ArcadeUnlocker(private val store: HubConfigStore, private val scope: CoroutineScope,
    private val today: () -> LocalDate = { LocalDate.now() }) {
    private val events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val unlocked: SharedFlow<String> = events
    private val detector = ArcSpinDetector()
    private val moonTaps = MoonTapDetector()
    @Volatile private var cached = ArcadeState()
    @Volatile private var started = false

    /** Starts watching the stored state, the ledger and the step goal. Call once. */
    fun watch(ledger: StateFlow<DayLedger>) {
        if (started) return
        started = true
        scope.launch { store.arcade.collect { cached = it } }
        scope.launch { combine(ledger, store.stepOptions) { days, options -> days to options }.collect { (days, options) -> checkLedger(days, options) } }
    }

    /** A finger turned a Home arc. Does nothing once the arc-turn way has nothing left to unlock. */
    fun onArcDrag(arcId: String, turns: Float, uptimeMs: Long) {
        if (!ArcadeUnlockLogic.pending(cached, ArcadeRules.CalmDayOrArcSpin)) return
        if (detector.feed(arcId, turns, uptimeMs)) fire(ArcadeRules.CalmDayOrArcSpin)
    }

    /** T211: one tap on the Sky page's moon. Seven inside five seconds unlock Lander. */
    fun onMoonTap(uptimeMs: Long) {
        if (!ArcadeUnlockLogic.pending(cached, ArcadeRules.StepGoalOrMoonTap)) return
        if (moonTaps.feed(uptimeMs)) fire(ArcadeRules.StepGoalOrMoonTap)
    }

    fun fire(ruleId: String) { scope.launch { commit { ArcadeUnlockLogic.unlockByRule(it, ruleId) } } }

    private suspend fun checkLedger(ledger: DayLedger, options: StepOptions) {
        val current = store.arcade.first()
        if (!ArcadeUnlockLogic.pending(current, ArcadeRules.CalmDayOrArcSpin) &&
            !ArcadeUnlockLogic.pending(current, ArcadeRules.StepGoalOrMoonTap)) return
        commit {
            val earned = ArcadeUnlockLogic.checkEarned(it, ledger, today())
            ArcadeUnlockLogic.checkStepGoal(earned, ledger, today(), options.goal)
        }
    }

    private suspend fun commit(change: (ArcadeState) -> ArcadeState) {
        // The tab may be on screen before this returns (the config flow reacts to the write), so the reveal flag is raised first.
        val (before, after) = store.arcadeEdit { prev ->
            val next = change(prev)
            if (ArcadeUnlockLogic.newlyUnlocked(prev, next).isNotEmpty()) ArcadeReveal.pending = true
            next
        }
        ArcadeUnlockLogic.newlyUnlocked(before, after).forEach { events.emit(it) }
    }
}
