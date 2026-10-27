package com.brightblu.parc.feature.settings.onboarding

/**
 * Ordered system-page candidates per target, and the pick rule (T18).
 *
 * The actions are plain strings, not `android.provider.Settings` constants, because this decision is
 * unit-tested on the JVM and because the vendor Autostart action has no platform constant at all.
 * Each documented string is the platform value of the matching `Settings.ACTION_*` constant; a test
 * pins the ones that also exist as constants elsewhere in the project.
 *
 * Nothing here needs a permission and nothing changes a setting. The one platform action that would
 * ask for a permission, `android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, is deliberately
 * absent and a test asserts it can never appear in a candidate list.
 */
object OnboardingTargets {

    const val AppDetailsSettings: String = "android.settings.APPLICATION_DETAILS_SETTINGS"
    const val HomeSettings: String = "android.settings.HOME_SETTINGS"
    const val IgnoreBatteryOptimizationSettings: String =
        "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"
    const val NotificationListenerSettings: String =
        "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
    const val Settings: String = "android.settings.SETTINGS"
    const val UsageAccessSettings: String = "android.settings.USAGE_ACCESS_SETTINGS"

    /** Vendor Autostart action, unverified: see the T18 report. */
    const val VendorAutoStart: String = "miui.intent.action.OP_AUTO_START"

    /** The actions Parc is allowed to offer. Anything else is a bug, not a variation. */
    val Allowed: Set<String> = setOf(
        AppDetailsSettings,
        HomeSettings,
        IgnoreBatteryOptimizationSettings,
        NotificationListenerSettings,
        Settings,
        UsageAccessSettings,
        VendorAutoStart,
    )

    /** Actions that would request a permission or change a setting directly. Never offered. */
    val Forbidden: Set<String> = setOf(
        "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    )

    /**
     * Candidates for [target], best first. Every list ends with a page that exists on every Android
     * build, so [pick] always has something to return.
     */
    fun candidatesFor(target: OnboardingTarget): List<String> = when (target) {
        OnboardingTarget.AppInfo -> listOf(AppDetailsSettings)
        OnboardingTarget.HomeRole -> listOf(HomeSettings, Settings)
        OnboardingTarget.BatteryOptimization -> listOf(
            IgnoreBatteryOptimizationSettings,
            AppDetailsSettings,
        )
        OnboardingTarget.Autostart -> listOf(VendorAutoStart, AppDetailsSettings)
        OnboardingTarget.NotificationAccess -> listOf(NotificationListenerSettings, AppDetailsSettings)
        OnboardingTarget.UsageAccess -> listOf(UsageAccessSettings, AppDetailsSettings)
    }

    /**
     * The first candidate [canResolve] accepts; the last candidate when none resolves, so the caller
     * still has a defined intent to try (a missing Settings page should degrade, never crash).
     */
    fun pick(target: OnboardingTarget, canResolve: (String) -> Boolean): String {
        val candidates = candidatesFor(target)
        return candidates.firstOrNull(canResolve) ?: candidates.last()
    }
}
