package com.brightblu.parc.feature.settings.onboarding

import com.brightblu.parc.core.data.ParcSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [OnboardingStore]. Writes are recorded and pushed back into the same flow, so a test can
 * see both what was persisted and what the controller does with the next snapshot.
 */
internal class FakeOnboardingStore(initial: ParcSettings = ParcSettings()) : OnboardingStore {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<ParcSettings> = state

    val writes = mutableListOf<Boolean>()

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        writes += completed
        state.value = state.value.copy(onboardingCompleted = completed)
    }

    /** Simulates a change made outside the flow (another screen, a restore). */
    fun push(snapshot: ParcSettings) {
        state.value = snapshot
    }
}

/** [OnboardingProbe] over a mutable snapshot; counts reads so a test can prove refresh happened. */
internal class FakeOnboardingProbe(initial: OnboardingProbes = OnboardingProbes()) : OnboardingProbe {
    private var current = initial
    var reads = 0
        private set

    override fun read(): OnboardingProbes {
        reads++
        return current
    }

    fun push(probes: OnboardingProbes) {
        current = probes
    }
}

/** A probe whose source is broken; the controller must degrade to all-Unknown, never throw. */
internal class ThrowingOnboardingProbe : OnboardingProbe {
    override fun read(): OnboardingProbes = throw IllegalStateException("probe unavailable")
}
