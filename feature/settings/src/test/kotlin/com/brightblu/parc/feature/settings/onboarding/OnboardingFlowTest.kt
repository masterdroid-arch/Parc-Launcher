package com.brightblu.parc.feature.settings.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Navigation and status rules, with no Android type involved. */
class OnboardingFlowTest {

    @Test
    fun `a readable probe becomes Done or Todo`() {
        assertEquals(
            OnboardingStatus.Done,
            OnboardingFlow.status(OnboardingStep.DefaultHome, OnboardingProbes(homeRoleHeld = true)),
        )
        assertEquals(
            OnboardingStatus.Todo,
            OnboardingFlow.status(OnboardingStep.DefaultHome, OnboardingProbes(homeRoleHeld = false)),
        )
        assertEquals(
            OnboardingStatus.Done,
            OnboardingFlow.status(
                OnboardingStep.RestrictedSettings,
                OnboardingProbes(notificationAccessGranted = true),
            ),
        )
        assertEquals(
            OnboardingStatus.Done,
            OnboardingFlow.status(
                OnboardingStep.Battery,
                OnboardingProbes(batteryUnrestricted = true),
            ),
        )
    }

    @Test
    fun `an unreadable source is Unknown, never a guess`() {
        OnboardingStep.entries.forEach { step ->
            assertEquals(
                "$step must not claim a state",
                OnboardingStatus.Unknown,
                OnboardingFlow.status(step, OnboardingProbes()),
            )
        }
    }

    @Test
    fun `autostart stays Unknown however the other probes read`() {
        val probes = OnboardingProbes(
            homeRoleHeld = true,
            notificationAccessGranted = true,
            batteryUnrestricted = true,
        )

        assertEquals(OnboardingStatus.Unknown, OnboardingFlow.status(OnboardingStep.Autostart, probes))
    }

    @Test
    fun `statuses covers every step in script order`() {
        val probes = OnboardingProbes(homeRoleHeld = true, notificationAccessGranted = false)
        val statuses = OnboardingFlow.statuses(probes)

        assertEquals(OnboardingScript.steps.map { it.step }, statuses.keys.toList())
        assertEquals(OnboardingStatus.Done, statuses[OnboardingStep.DefaultHome])
        assertEquals(OnboardingStatus.Todo, statuses[OnboardingStep.RestrictedSettings])
        assertEquals(OnboardingStatus.Unknown, statuses[OnboardingStep.Battery])
    }

    @Test
    fun `next advances and clamps on the last step`() {
        val first = OnboardingUiState()
        val second = OnboardingFlow.next(first)
        assertEquals(1, second.index)

        val last = OnboardingUiState(index = OnboardingScript.steps.lastIndex)
        assertEquals(last, OnboardingFlow.next(last))
    }

    @Test
    fun `back steps down and clamps on the first step`() {
        val second = OnboardingUiState(index = 1)
        assertEquals(0, OnboardingFlow.back(second).index)

        val first = OnboardingUiState(index = 0)
        assertEquals(first, OnboardingFlow.back(first))
    }

    @Test
    fun `goTo moves to a named step and leaves a state that does not contain it alone`() {
        val state = OnboardingUiState()

        assertEquals(4, OnboardingFlow.goTo(state, OnboardingStep.Battery).index)
        assertEquals(2, OnboardingFlow.goTo(state, OnboardingStep.DefaultHome).index)

        val truncated = OnboardingUiState(steps = OnboardingScript.steps.take(2))
        assertEquals(truncated, OnboardingFlow.goTo(truncated, OnboardingStep.Battery))
    }

    @Test
    fun `a stale index cannot crash the screen`() {
        val tooHigh = OnboardingUiState(index = 99)
        val tooLow = OnboardingUiState(index = -3)

        assertEquals(OnboardingStep.Autostart, tooHigh.current.step)
        assertEquals(OnboardingStep.MainApps, tooLow.current.step)
        assertEquals("Step 6 of 6", tooHigh.progress)
        assertEquals("Step 1 of 6", tooLow.progress)
    }

    @Test
    fun `first, last and progress describe the position faithfully`() {
        val first = OnboardingUiState(index = 0)
        assertTrue(first.isFirst)
        assertFalse(first.isLast)
        assertEquals("Step 1 of 6", first.progress)

        val last = OnboardingUiState(index = OnboardingScript.steps.lastIndex)
        assertFalse(last.isFirst)
        assertTrue(last.isLast)
        assertEquals("Step 6 of 6", last.progress)
    }

    @Test
    fun `the current status defaults to Unknown and the label to empty`() {
        val state = OnboardingUiState(index = 3)

        assertEquals(OnboardingStatus.Unknown, state.currentStatus)
        assertEquals("", state.currentStatusLabel)

        val known = state.copy(
            statuses = mapOf(OnboardingStep.RestrictedSettings to OnboardingStatus.Done),
        )
        assertEquals(OnboardingStatus.Done, known.currentStatus)
        assertEquals("Set", known.currentStatusLabel)
    }

    @Test
    fun `withStatuses replaces the map without moving the step`() {
        val state = OnboardingUiState(index = 2, statuses = mapOf(OnboardingStep.Battery to OnboardingStatus.Todo))
        val refreshed = OnboardingFlow.withStatuses(state, OnboardingProbes(batteryUnrestricted = true))

        assertEquals(2, refreshed.index)
        assertEquals(OnboardingStatus.Done, refreshed.statuses[OnboardingStep.Battery])
        assertEquals(OnboardingStatus.Unknown, refreshed.statuses[OnboardingStep.Autostart])
    }
}
