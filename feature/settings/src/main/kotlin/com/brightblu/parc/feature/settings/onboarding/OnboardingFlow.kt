package com.brightblu.parc.feature.settings.onboarding

/**
 * Immutable onboarding screen state (T18). Non-null.
 *
 * [steps] is the whole script so a host can show a progress line; [index] always addresses a real
 * entry ([current] clamps, so a host cannot crash the screen with a stale index). [statuses] holds
 * only what Parc can read; a missing entry means Unknown. [completed] mirrors the persisted flag,
 * and it is the host's signal to close the flow.
 */
data class OnboardingUiState(
    val steps: List<OnboardingStepSpec> = OnboardingScript.steps,
    val index: Int = 0,
    val statuses: Map<OnboardingStep, OnboardingStatus> = emptyMap(),
    val completed: Boolean = false,
) {
    /** The step on screen. Safe for any [index]. */
    val current: OnboardingStepSpec
        get() = steps[index.coerceIn(0, steps.lastIndex)]

    val isFirst: Boolean get() = index <= 0

    val isLast: Boolean get() = index >= steps.lastIndex

    /** "Step 2 of 4" (DESIGN §5 S13 keeps the flow plain and countable). */
    val progress: String get() = "Step ${index.coerceIn(0, steps.lastIndex) + 1} of ${steps.size}"

    /** Status of the step on screen; Unknown when it was never probed. */
    val currentStatus: OnboardingStatus
        get() = statuses[current.step] ?: OnboardingStatus.Unknown

    /** Status text for the step on screen, empty when Parc cannot read it. */
    val currentStatusLabel: String get() = OnboardingScript.statusLabel(currentStatus)
}

/**
 * Pure navigation and status rules (T18). No Android type, no clock, no persisted write: every output
 * is a function of the input, which is what makes the ordering, the clamping and the "never guess"
 * rule unit-testable.
 */
object OnboardingFlow {

    /** What Parc can say about [step] from [probes]. A null probe is [OnboardingStatus.Unknown]. */
    fun status(step: OnboardingStep, probes: OnboardingProbes): OnboardingStatus = when (step) {
        // The picker has no system probe; the screen shows the selection instead (T62).
        OnboardingStep.MainApps -> OnboardingStatus.Unknown
        OnboardingStep.Permissions -> probes.usageAccessGranted.toStatus()
        OnboardingStep.DefaultHome -> probes.homeRoleHeld.toStatus()
        OnboardingStep.RestrictedSettings -> probes.notificationAccessGranted.toStatus()
        OnboardingStep.Battery -> probes.batteryUnrestricted.toStatus()
        // No Android API reports vendor Autostart, and Parc will not guess one.
        OnboardingStep.Autostart -> OnboardingStatus.Unknown
    }

    /** Every step's status, keyed by step, in script order. */
    fun statuses(probes: OnboardingProbes): Map<OnboardingStep, OnboardingStatus> =
        OnboardingScript.steps.associate { it.step to status(it.step, probes) }

    /** The next step, clamped at the last. Completion is the controller's decision, not this one. */
    fun next(state: OnboardingUiState): OnboardingUiState =
        if (state.isLast) state else state.copy(index = state.index + 1)

    /** The previous step, clamped at the first. */
    fun back(state: OnboardingUiState): OnboardingUiState =
        if (state.isFirst) state else state.copy(index = state.index - 1)

    /** Jumps to [step] when it is in the script; otherwise the state is unchanged. */
    fun goTo(state: OnboardingUiState, step: OnboardingStep): OnboardingUiState {
        val target = state.steps.indexOfFirst { it.step == step }
        return if (target < 0) state else state.copy(index = target)
    }

    /** The same state with freshly probed statuses. */
    fun withStatuses(state: OnboardingUiState, probes: OnboardingProbes): OnboardingUiState =
        state.copy(statuses = statuses(probes))

    private fun Boolean?.toStatus(): OnboardingStatus = when (this) {
        true -> OnboardingStatus.Done
        false -> OnboardingStatus.Todo
        null -> OnboardingStatus.Unknown
    }
}
