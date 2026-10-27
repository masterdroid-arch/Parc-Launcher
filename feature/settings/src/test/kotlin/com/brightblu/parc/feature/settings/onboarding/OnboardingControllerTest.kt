package com.brightblu.parc.feature.settings.onboarding

import com.brightblu.parc.core.data.ParcSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The controller: the persisted flag is the source of truth, the probes are read again on refresh, and
 * a broken probe can only make the flow quieter. The scope is [Dispatchers.Unconfined], so a write is
 * applied before the next assertion.
 */
class OnboardingControllerTest {

    private fun controller(
        store: OnboardingStore = FakeOnboardingStore(),
        probe: OnboardingProbe = FakeOnboardingProbe(),
    ): Pair<OnboardingController, CoroutineScope> {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        return OnboardingController(store, probe, scope) to scope
    }

    @Test
    fun `a fresh install opens on the first step and is not completed`() {
        val (controller, scope) = controller()

        assertEquals(0, controller.state.value.index)
        assertEquals(OnboardingStep.MainApps, controller.state.value.current.step)
        assertFalse(controller.state.value.completed)
        scope.cancel()
    }

    @Test
    fun `the statuses are read once at construction, before any interaction`() {
        val probe = FakeOnboardingProbe(OnboardingProbes(homeRoleHeld = true, batteryUnrestricted = false))
        val (controller, scope) = controller(probe = probe)

        assertEquals(1, probe.reads)
        assertEquals(OnboardingStatus.Done, controller.state.value.statuses[OnboardingStep.DefaultHome])
        assertEquals(
            OnboardingStatus.Todo,
            controller.state.value.statuses[OnboardingStep.Battery],
        )
        assertEquals(
            OnboardingStatus.Unknown,
            controller.state.value.statuses[OnboardingStep.Autostart],
        )
        scope.cancel()
    }

    @Test
    fun `next walks the six steps and finishes on the last one`() {
        val store = FakeOnboardingStore()
        val (controller, scope) = controller(store)

        controller.next()
        assertEquals(1, controller.state.value.index)
        controller.next()
        assertEquals(2, controller.state.value.index)
        controller.next()
        assertEquals(3, controller.state.value.index)
        controller.next()
        assertEquals(4, controller.state.value.index)
        controller.next()
        assertEquals(5, controller.state.value.index)
        assertFalse(controller.state.value.completed)

        controller.next()

        assertTrue(controller.state.value.completed)
        assertEquals(5, controller.state.value.index)
        assertEquals(listOf(true), store.writes)
        scope.cancel()
    }

    @Test
    fun `back never leaves the flow`() {
        val (controller, scope) = controller()

        controller.back()
        assertEquals(0, controller.state.value.index)

        controller.next()
        controller.back()
        assertEquals(0, controller.state.value.index)
        scope.cancel()
    }

    @Test
    fun `goTo opens a named step`() {
        val (controller, scope) = controller()

        controller.goTo(OnboardingStep.Battery)

        assertEquals(OnboardingStep.Battery, controller.state.value.current.step)
        assertEquals("Step 5 of 6", controller.state.value.progress)
        scope.cancel()
    }

    @Test
    fun `skip marks the flow complete and persists it`() {
        val store = FakeOnboardingStore()
        val (controller, scope) = controller(store)

        controller.skip()

        assertTrue(controller.state.value.completed)
        assertEquals(listOf(true), store.writes)
        scope.cancel()
    }

    @Test
    fun `finish is idempotent`() {
        val store = FakeOnboardingStore()
        val (controller, scope) = controller(store)

        controller.finish()
        controller.finish()

        assertTrue(controller.state.value.completed)
        assertEquals(listOf(true, true), store.writes)
        scope.cancel()
    }

    @Test
    fun `refresh picks up a probe that changed while the user was in Settings`() {
        val probe = FakeOnboardingProbe(OnboardingProbes(homeRoleHeld = false))
        val (controller, scope) = controller(probe = probe)
        assertEquals(OnboardingStatus.Todo, controller.state.value.statuses[OnboardingStep.DefaultHome])

        // The user set Parc as home, then came back to the flow.
        probe.push(OnboardingProbes(homeRoleHeld = true, notificationAccessGranted = true))
        controller.refresh()

        assertEquals(2, probe.reads)
        assertEquals(OnboardingStatus.Done, controller.state.value.statuses[OnboardingStep.DefaultHome])
        assertEquals(
            OnboardingStatus.Done,
            controller.state.value.statuses[OnboardingStep.RestrictedSettings],
        )
        assertEquals(0, controller.state.value.index)
        scope.cancel()
    }

    @Test
    fun `a probe that throws yields unknown statuses instead of failing`() {
        val (controller, scope) = controller(probe = ThrowingOnboardingProbe())

        assertTrue(
            controller.state.value.statuses.values.all { it == OnboardingStatus.Unknown },
        )

        controller.refresh()

        assertTrue(
            controller.state.value.statuses.values.all { it == OnboardingStatus.Unknown },
        )
        scope.cancel()
    }

    @Test
    fun `an external store change sets completed without a tap`() {
        val store = FakeOnboardingStore()
        val (controller, scope) = controller(store)

        store.push(ParcSettings(onboardingCompleted = true))

        assertTrue(controller.state.value.completed)
        assertEquals(emptyList<Boolean>(), store.writes)
        scope.cancel()
    }

    @Test
    fun `an install that already completed onboarding starts completed`() {
        val (controller, scope) = controller(
            store = FakeOnboardingStore(ParcSettings(onboardingCompleted = true)),
        )

        assertTrue(controller.state.value.completed)
        assertEquals(0, controller.state.value.index)
        scope.cancel()
    }
}
