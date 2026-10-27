package com.brightblu.parc.feature.settings.onboarding

/**
 * The S13 script: four plain screens, one per ask (DESIGN §5, PROJECT.md §6.3 item 4, F-16).
 *
 * The copy explains what to change and where, nothing more. It is data, so the order, the wording and
 * the offered actions are asserted by unit tests; the screen is a dumb renderer of this list.
 */
object OnboardingScript {

    val steps: List<OnboardingStepSpec> = listOf(
        OnboardingStepSpec(
            step = OnboardingStep.MainApps,
            title = "Your four main apps",
            lines = listOf(
                "Pick the apps you reach for first. They go on the inner arc, closest to your thumb.",
                "Tap an app to add it. Use up and down to reorder, or x to remove it.",
                "You can change this later in Settings.",
            ),
            actions = emptyList(),
        ),
        OnboardingStepSpec(
            step = OnboardingStep.Permissions,
            title = "Make Parc smarter",
            lines = listOf(
                "These are optional. Parc works without them, and you can set each one later in " +
                    "Settings, under Permissions.",
                "Focus can optionally sync Do Not Disturb. Allow Do Not Disturb access in Settings, under Permissions, only if you want this.",
                "Usage access lets Parc learn which apps you use, show screen time and data use.",
                "Notification access shows unread dots and powers the Comms page. Contacts search " +
                    "is asked for in Settings, only when you want it.",
                "Calendar access is asked for on the Agenda page of the stats hub, only when you want to see your next events. " +
                    "Search can also find calendar events and your photos, videos and music by name. It asks once, from the search sheet, and you can say no.",
                "Notifications are asked for the first time you start a timer on the Timers page, so a finished timer can ring.",
            ),
            actions = listOf(
                OnboardingAction(OnboardingTarget.UsageAccess, "Open usage access"),
                OnboardingAction(OnboardingTarget.NotificationAccess, "Open notification access"),
            ),
        ),
        OnboardingStepSpec(
            step = OnboardingStep.DefaultHome,
            title = "Make Parc your home screen",
            lines = listOf(
                "The system asks which app should be your home screen. Pick Parc.",
                "If the picker does not appear, open Home settings and select Parc there.",
            ),
            actions = listOf(
                OnboardingAction(OnboardingTarget.HomeRole, "Open home settings"),
            ),
        ),
        OnboardingStepSpec(
            step = OnboardingStep.RestrictedSettings,
            title = "Allow restricted settings",
            lines = listOf(
                "Parc is installed by hand, not from a store, so Android hides the notification " +
                    "switch until you allow restricted settings for it.",
                "Open the app info page, tap the three dots, then Allow restricted settings.",
                "After that, turn on notification access for Parc so the unread dots work.",
            ),
            actions = listOf(
                OnboardingAction(OnboardingTarget.AppInfo, "Open app info"),
                OnboardingAction(OnboardingTarget.NotificationAccess, "Open notification access"),
            ),
        ),
        OnboardingStepSpec(
            step = OnboardingStep.Battery,
            title = "Battery and recents",
            lines = listOf(
                "Some devices stop background apps. Set Parc to No restrictions under Battery " +
                    "saver so the launcher keeps running.",
                "Then open Recents, long-press Parc and tap the lock. That keeps Parc alive when " +
                    "you clear your recent apps.",
            ),
            actions = listOf(
                OnboardingAction(OnboardingTarget.AppInfo, "Open app info"),
                OnboardingAction(OnboardingTarget.BatteryOptimization, "Open battery settings"),
            ),
        ),
        OnboardingStepSpec(
            step = OnboardingStep.Autostart,
            title = "Turn on Autostart",
            lines = listOf(
                "Some devices stop Parc while it sits in the background. Autostart lets the " +
                    "system start Parc again after a kill.",
                "Turn Parc on in Autostart. If the page does not open, it is under Security, then " +
                    "Autostart.",
            ),
            actions = listOf(
                OnboardingAction(OnboardingTarget.Autostart, "Open Autostart"),
                OnboardingAction(OnboardingTarget.AppInfo, "Open app info"),
            ),
        ),
    )

    /** The spec for [step]; the flow's list always contains every entry. */
    fun spec(step: OnboardingStep): OnboardingStepSpec =
        steps.first { it.step == step }

    /**
     * Short state text for a step, or an empty string when Parc cannot read the state. Empty means
     * the screen shows nothing rather than a guess.
     */
    fun statusLabel(status: OnboardingStatus): String = when (status) {
        OnboardingStatus.Done -> "Set"
        OnboardingStatus.Todo -> "Not set yet"
        OnboardingStatus.Unknown -> ""
    }
}
