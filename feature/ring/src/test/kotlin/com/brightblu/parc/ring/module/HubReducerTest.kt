package com.brightblu.parc.ring.module

import com.brightblu.parc.model.Metrics
import org.junit.Assert.*
import org.junit.Test

class HubReducerTest {
    private fun run(state: HubUiState, vararg events: HubEvent): Pair<HubUiState, List<HubOutput>> {
        var s = state
        val out = ArrayList<HubOutput>()
        for (e in events) { val r = HubReducer.reduce(s, e); s = r.state; out += r.outputs }
        return s to out
    }
    private val far = Metrics.aimDeadZoneDp + 40f
    private fun hubDown(t: Long = 0) = HubEvent.Down(true, HubUiState.NO_AIM, t)
    private fun aimMove(slot: Int, dx: Float = -far, dy: Float = -10f, back: Boolean = false) =
        HubEvent.Move(dx, dy, slot, back, far)
    private fun up(t: Long = 2000, slot: Int = -1, folder: Boolean = false, dx: Float = -far, dy: Float = -10f, travel: Float = far) =
        HubEvent.Up(t, slot, folder, dx, dy, travel)

    @Test fun downOnSlotStartsTouch() {
        val (s, out) = run(HubUiState(), HubEvent.Down(false, 4, 0))
        assertEquals(TouchPhase.Down, s.touchPhase); assertEquals(4, s.downSlot); assertTrue(out.isEmpty())
    }
    @Test fun upOnSameSlotLaunches() {
        val (s, out) = run(HubUiState(), HubEvent.Down(false, 4, 0), up(100, 4, dx = 0f, dy = 0f, travel = 1f))
        assertEquals(listOf<HubOutput>(HubOutput.Launch(4)), out); assertEquals(TouchPhase.Idle, s.touchPhase)
    }
    @Test fun upOnFolderOpensIt() {
        val (_, out) = run(HubUiState(), HubEvent.Down(false, 2, 0), up(100, 2, folder = true))
        assertEquals(listOf<HubOutput>(HubOutput.OpenFolder(2)), out)
    }
    @Test fun upOnAnotherSlotDoesNothing() {
        val (_, out) = run(HubUiState(), HubEvent.Down(false, 2, 0), up(100, 3))
        assertTrue(out.isEmpty())
    }
    @Test fun moveChangesAimAndTicks() {
        val (s, out) = run(HubUiState(), hubDown(), aimMove(3), aimMove(5))
        assertEquals(5, s.aimedSlot); assertEquals(TouchPhase.Aiming, s.touchPhase)
        assertEquals(listOf<HubOutput>(HubOutput.Tick, HubOutput.Tick), out)
    }
    @Test fun sameAimDoesNotTickTwice() {
        val (_, out) = run(HubUiState(), hubDown(), aimMove(3), aimMove(3))
        assertEquals(1, out.size)
    }
    @Test fun insideDeadZoneDoesNotAim() {
        val (s, out) = run(HubUiState(), hubDown(), HubEvent.Move(-1f, -1f, 3, false, 1.4f))
        assertEquals(TouchPhase.Down, s.touchPhase); assertTrue(out.isEmpty())
    }
    @Test fun upAfterAimLaunchesAimedSlot() {
        val (_, out) = run(HubUiState(), hubDown(), aimMove(3), up(slot = 3))
        assertEquals(listOf(HubOutput.Tick, HubOutput.Launch(3)), out)
    }
    @Test fun cancelDoesNothing() {
        val (s, out) = run(HubUiState(), hubDown(), aimMove(3), HubEvent.Cancel)
        assertEquals(TouchPhase.Idle, s.touchPhase); assertEquals(listOf<HubOutput>(HubOutput.Tick), out)
        assertEquals(HubUiState.NO_AIM, s.aimedSlot)
    }
    @Test fun quickHubTapGoesBack() {
        val (_, out) = run(HubUiState(), hubDown(0), up(100, dx = 0f, dy = 0f, travel = 0.5f))
        assertEquals(listOf<HubOutput>(HubOutput.Back), out)
    }
    @Test fun slowHubPressWithoutAimDoesNothing() {
        val (_, out) = run(HubUiState(), hubDown(0), up(Metrics.longPressMs + 50, dx = 0f, dy = 0f, travel = 0.5f))
        assertTrue(out.isEmpty())
    }
    @Test fun pushToLowerRightSelectsBack() {
        val (s, out) = run(HubUiState(), hubDown(), HubEvent.Move(far, far, 3, true, far), up(dx = far, dy = far))
        assertEquals(HubUiState.NO_AIM, s.aimedSlot)
        assertEquals(listOf(HubOutput.Tick, HubOutput.Back), out)
    }
    @Test fun lowerRightIsNotBackOnHome() {
        val (s, _) = run(HubUiState(), hubDown(), HubEvent.Move(far, far, 3, false, far))
        assertEquals(3, s.aimedSlot)
    }
    @Test fun folderOpenAndCloseTrackMode() {
        val (open, _) = run(HubUiState(), HubEvent.Open(HubMode.Folder))
        assertEquals(HubMode.Folder, open.mode); assertTrue(open.transitioning)
        val (settled, _) = run(open, HubEvent.Progress(1f, settled = true))
        assertFalse(settled.transitioning); assertEquals(1f, settled.openProgress, 0f)
        val (closed, _) = run(settled, HubEvent.Close)
        assertEquals(HubMode.Home, closed.mode); assertTrue(closed.transitioning)
    }
    @Test fun openingTheCurrentModeOrClosingHomeIsNoOp() {
        assertEquals(HubUiState(), run(HubUiState(), HubEvent.Close).first)
        val folder = run(HubUiState(), HubEvent.Open(HubMode.Folder)).first
        assertEquals(folder, run(folder, HubEvent.Open(HubMode.Folder)).first)
    }
    @Test fun touchDuringTransitionIsCaughtNotDropped() {
        val (s, out) = run(HubUiState(), HubEvent.Open(HubMode.Layer2), HubEvent.Progress(0.4f),
            HubEvent.Down(false, 1, 0), up(100, 1, dx = 0f, dy = 0f, travel = 1f))
        assertEquals(listOf<HubOutput>(HubOutput.Launch(1)), out)
        assertTrue(s.transitioning); assertEquals(0.4f, s.openProgress, 0f)
    }
    @Test fun openDuringTouchKeepsTouch() {
        val (s, _) = run(HubUiState(), hubDown(), HubEvent.Open(HubMode.Folder))
        assertEquals(TouchPhase.Down, s.touchPhase); assertEquals(HubMode.Folder, s.mode)
    }
    @Test fun progressIsClampedAndNanIgnored() {
        assertEquals(1f, run(HubUiState(), HubEvent.Progress(3f)).first.openProgress, 0f)
        assertEquals(0f, run(HubUiState(), HubEvent.Progress(Float.NaN)).first.openProgress, 0f)
    }
    @Test fun strayMoveAndUpWhileIdleAreIgnored() {
        val (s, out) = run(HubUiState(), aimMove(3), up())
        assertEquals(HubUiState(), s); assertTrue(out.isEmpty())
    }
    @Test fun rotationOffsetsAreRecorded() {
        assertEquals(2f, run(HubUiState(), HubEvent.Rotated("A4", 2f)).first.arcOffsets["A4"]!!, 0f)
    }
    @Test fun holderExposesTouchProgress() {
        val outs = ArrayList<HubOutput>()
        val h = HubModuleState(onOutput = { outs += it })
        assertEquals(0f, h.touchProgress, 0f)
        h.dispatch(HubEvent.Down(true, -1, 0)); assertEquals(1f, h.touchProgress, 0f)
        h.dispatch(HubEvent.Up(50, -1, false, 0f, 0f, 0f)); assertEquals(0f, h.touchProgress, 0f)
        assertEquals(listOf<HubOutput>(HubOutput.Back), outs)
    }
}
