package com.brightblu.parc.ring.module

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brightblu.parc.model.ArcFeel
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.ring.ArcMotion
import com.brightblu.parc.ring.ArcMotionState
import com.brightblu.parc.ring.ArcMotionStep
import com.brightblu.parc.ring.ArcPhase
import com.brightblu.parc.ring.RingAim
import kotlin.math.hypot

enum class HubMode { Home, Folder, Layer2 }

enum class TouchPhase { Idle, Down, Aiming }

/** Immutable picture of the module. [aimedSlot] is a scene slot index, [NO_AIM] or [AIM_BACK]. */
data class HubUiState(
    val mode: HubMode = HubMode.Home,
    /** 0 = Home arcs, 1 = fully open (folder or Layer 2); between while a transition runs. */
    val openProgress: Float = 0f,
    val transitioning: Boolean = false,
    val arcOffsets: Map<String, Float> = emptyMap(),
    val aimedSlot: Int = NO_AIM,
    val touchPhase: TouchPhase = TouchPhase.Idle,
    val downOnHub: Boolean = false,
    val downSlot: Int = NO_AIM,
    val downAtMs: Long = 0L,
    val travelDp: Float = 0f,
    /** T148: the arc being turned or settling, its motion, and the Feel choices that shape it. */
    val arcId: String = "",
    val arcMotion: ArcMotionState = ArcMotionState(),
    val arcFeel: ArcFeel = ArcFeel(),
) {
    companion object {
        const val NO_AIM = -1
        const val AIM_BACK = RingAim.BACK
    }
}

sealed interface HubEvent {
    /** [slot] is the scene slot under the finger (or [HubUiState.NO_AIM]). */
    data class Down(val onHub: Boolean, val slot: Int, val atMs: Long) : HubEvent
    /**
     * Finger moved. [dxDp]/[dyDp] are relative to the hub centre, [slotAim] is the joystick's
     * nearest slot, [backAllowed] is false on Home (Back exists only in other layers, T27).
     * [travelDp] is the accumulated path length since Down.
     */
    data class Move(val dxDp: Float, val dyDp: Float, val slotAim: Int, val backAllowed: Boolean,
        val travelDp: Float) : HubEvent
    data class Up(val atMs: Long, val slot: Int, val slotIsFolder: Boolean, val dxDp: Float, val dyDp: Float,
        val travelDp: Float) : HubEvent
    data object Cancel : HubEvent
    data class Open(val mode: HubMode) : HubEvent
    data object Close : HubEvent
    data class Progress(val value: Float, val settled: Boolean = false) : HubEvent
    data class Rotated(val arcId: String, val offset: Float) : HubEvent
    /** T148: finger down on a rotating arc, catching it if it is settling. */
    data class ArcDown(val arcId: String, val offset: Float) : HubEvent
    /** The finger moved the arc by [deltaOffset] slots. */
    data class ArcDrag(val deltaOffset: Float) : HubEvent
    /** Finger up with the finger's velocity in offset slots per second; [timeScale] <= 0 jumps. */
    data class ArcRelease(val velocity: Float, val timeScale: Float = 1f) : HubEvent
    /** One frame of settling, [dtSeconds] of real time. */
    data class ArcFrame(val dtSeconds: Float) : HubEvent
    data class ArcFeelChanged(val feel: ArcFeel) : HubEvent
}

sealed interface HubOutput {
    data class Launch(val slot: Int) : HubOutput
    data class OpenFolder(val slot: Int) : HubOutput
    data object Tick : HubOutput
    data object Back : HubOutput
}

data class HubReduction(val state: HubUiState, val outputs: List<HubOutput> = emptyList())

/** Pure reducer: no Android, no clock, no coroutines. */
object HubReducer {
    fun reduce(state: HubUiState, event: HubEvent): HubReduction = when (event) {
        is HubEvent.Down -> HubReduction(state.copy(touchPhase = TouchPhase.Down, downOnHub = event.onHub,
            downSlot = if (event.onHub) HubUiState.NO_AIM else event.slot, downAtMs = event.atMs,
            travelDp = 0f, aimedSlot = HubUiState.NO_AIM))
        is HubEvent.Move -> move(state, event)
        is HubEvent.Up -> up(state, event)
        HubEvent.Cancel -> {
            val arc = arcStep(state, ArcMotion.release(state.arcMotion, 0f, state.arcFeel, 1f))
            HubReduction(idle(arc.state), arc.outputs)
        }
        is HubEvent.Open -> {
            // Opening an already-open mode is a no-op; a touch in progress is left alone.
            if (event.mode == state.mode) HubReduction(state)
            else HubReduction(state.copy(mode = event.mode, transitioning = true))
        }
        HubEvent.Close -> if (state.mode == HubMode.Home) HubReduction(state)
            else HubReduction(state.copy(mode = HubMode.Home, transitioning = true))
        is HubEvent.Progress -> HubReduction(state.copy(
            openProgress = if (event.value.isFinite()) event.value.coerceIn(0f, 1f) else state.openProgress,
            transitioning = if (event.settled) false else state.transitioning))
        is HubEvent.Rotated -> HubReduction(state.copy(arcOffsets = state.arcOffsets + (event.arcId to event.offset)))
        is HubEvent.ArcDown -> {
            val base = if (state.arcId == event.arcId && state.arcMotion.phase == ArcPhase.Settling) state.arcMotion
            else ArcMotion.rest(event.offset)
            arcStep(state.copy(arcId = event.arcId), ArcMotionStep(ArcMotion.down(base), false))
        }
        is HubEvent.ArcDrag -> arcStep(state, ArcMotion.drag(state.arcMotion, event.deltaOffset))
        is HubEvent.ArcRelease -> arcStep(state,
            ArcMotion.release(state.arcMotion, event.velocity, state.arcFeel, event.timeScale))
        is HubEvent.ArcFrame -> arcStep(state, ArcMotion.step(state.arcMotion, event.dtSeconds))
        is HubEvent.ArcFeelChanged -> HubReduction(state.copy(arcFeel = event.feel))
    }

    private fun arcStep(state: HubUiState, step: ArcMotionStep): HubReduction {
        val moved = state.copy(arcMotion = step.state,
            arcOffsets = if (state.arcId.isEmpty()) state.arcOffsets else state.arcOffsets + (state.arcId to step.state.offset))
        return HubReduction(moved, if (step.tick) listOf(HubOutput.Tick) else emptyList())
    }

    private fun idle(state: HubUiState) = state.copy(touchPhase = TouchPhase.Idle, downOnHub = false,
        downSlot = HubUiState.NO_AIM, aimedSlot = HubUiState.NO_AIM, travelDp = 0f)

    private fun move(state: HubUiState, e: HubEvent.Move): HubReduction {
        if (state.touchPhase == TouchPhase.Idle) return HubReduction(state)
        val travelled = state.copy(travelDp = e.travelDp)
        // Only a gesture that started on the hub aims, and only beyond the dead zone.
        if (!state.downOnHub) return HubReduction(travelled)
        val reach = hypot(e.dxDp, e.dyDp)
        if (state.touchPhase != TouchPhase.Aiming && reach < Metrics.aimDeadZoneDp) return HubReduction(travelled)
        val aimed = when {
            e.backAllowed && RingAim.isBack(e.dxDp, e.dyDp) -> HubUiState.AIM_BACK
            else -> e.slotAim
        }
        val tick = aimed != state.aimedSlot && aimed != HubUiState.NO_AIM
        return HubReduction(travelled.copy(touchPhase = TouchPhase.Aiming, aimedSlot = aimed),
            if (tick) listOf(HubOutput.Tick) else emptyList())
    }

    private fun up(state: HubUiState, e: HubEvent.Up): HubReduction {
        if (state.touchPhase == TouchPhase.Idle) return HubReduction(state)
        val done = idle(state)
        val age = (e.atMs - state.downAtMs).coerceAtLeast(0L)
        val out: List<HubOutput> = if (state.downOnHub) {
            val reach = hypot(e.dxDp, e.dyDp)
            val aiming = state.touchPhase == TouchPhase.Aiming
            val tap = age < Metrics.longPressMs &&
                (reach <= Metrics.aimDeadZoneDp || e.travelDp < Metrics.hubTapTravelDp)
            when {
                tap -> listOf(HubOutput.Back)
                aiming && state.aimedSlot == HubUiState.AIM_BACK -> listOf(HubOutput.Back)
                aiming && state.aimedSlot >= 0 ->
                    listOf(if (e.slotIsFolder) HubOutput.OpenFolder(state.aimedSlot) else HubOutput.Launch(state.aimedSlot))
                else -> emptyList()
            }
        } else if (state.downSlot >= 0 && e.slot == state.downSlot && age < Metrics.longPressMs) {
            listOf(if (e.slotIsFolder) HubOutput.OpenFolder(e.slot) else HubOutput.Launch(e.slot))
        } else emptyList()
        return HubReduction(done, out)
    }
}

/**
 * State holder for [HubModule]. Observable [ui] plus one read-only value for the rest of the
 * screen: [touchProgress]. Outputs go to [onOutput]; the renderer's own callbacks still do the
 * real work in T142, so the host may leave [onOutput] empty.
 */
@Stable
class HubModuleState(initial: HubUiState = HubUiState(), private val onOutput: (HubOutput) -> Unit = {}) {
    var ui: HubUiState by mutableStateOf(initial)
        private set

    /** 0 when idle, 1 while a finger is on the module. T143 will smooth this with a spring. */
    val touchProgress: Float get() = if (ui.touchPhase == TouchPhase.Idle) 0f else 1f

    fun dispatch(event: HubEvent) {
        val r = HubReducer.reduce(ui, event)
        ui = r.state
        r.outputs.forEach(onOutput)
    }
}
