package com.brightblu.parc.feature.settings.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the four-step flow (T18, F-16).
 *
 * Threading: [state] is a [StateFlow]; the persisted completed flag is collected in [scope] and the
 * finishing write is launched into the same scope, so no write blocks a frame. Reads from any thread
 * are safe. The store stays the source of truth: [finish] updates the state optimistically and the
 * next collected snapshot confirms it.
 *
 * [probe] is read at construction and on every [refresh], never cached beyond that. A probe that
 * throws yields [OnboardingProbes] with all-null fields, which resolves every status to Unknown, so a
 * broken reader can only make the flow quieter, never wrong.
 *
 * The flow leaves itself: the host observes [OnboardingUiState.completed] and closes. Nothing here
 * changes a system setting; the actions it exposes only open a Settings page.
 */
class OnboardingController(
    private val store: OnboardingStore,
    private val probe: OnboardingProbe,
    private val scope: CoroutineScope,
) {
    private val stateFlow = MutableStateFlow(
        OnboardingFlow.withStatuses(OnboardingUiState(), readProbes()),
    )
    val state: StateFlow<OnboardingUiState> = stateFlow.asStateFlow()

    init {
        scope.launch {
            store.settings.collect { snapshot ->
                stateFlow.update { it.copy(completed = snapshot.onboardingCompleted) }
            }
        }
    }

    /** Re-reads the probes. The host calls this when it resumes, after the user came back from a page. */
    fun refresh() {
        stateFlow.update { OnboardingFlow.withStatuses(it, readProbes()) }
    }

    /** Advances one step; on the last step it finishes the flow instead of standing still. */
    fun next() {
        val current = stateFlow.value
        if (current.isLast) finish() else stateFlow.value = OnboardingFlow.next(current)
    }

    /** Steps back; a no-op on the first step, so Back can never leave the flow. */
    fun back() {
        stateFlow.update { OnboardingFlow.back(it) }
    }

    /** Jumps to a step. Only a host or the debug review screen uses this. */
    fun goTo(step: OnboardingStep) {
        stateFlow.update { OnboardingFlow.goTo(it, step) }
    }

    /** Leaves the flow now. The remaining steps are not shown again. */
    fun skip() {
        finish()
    }

    /** Marks the flow complete and persists it. Idempotent. */
    fun finish() {
        stateFlow.update { it.copy(completed = true) }
        scope.launch { store.setOnboardingCompleted(true) }
    }

    private fun readProbes(): OnboardingProbes = try {
        probe.read()
    } catch (_: Exception) {
        OnboardingProbes()
    }
}
