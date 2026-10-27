package com.brightblu.parc.feature.settings.onboarding

/**
 * The first-run steps (DESIGN §5 S13, PROJECT.md §6.3 item 4, F-16, T62). Declaration order is the
 * order the flow walks them in: main apps, optional permissions, default home, restricted settings, battery, autostart.
 *
 * [id] is stable and lower snake case so a debug host or a saved screen argument can name a step.
 */
enum class OnboardingStep(val id: String) {
    MainApps("main_apps"),
    Permissions("permissions"),
    DefaultHome("default_home"),
    RestrictedSettings("restricted_settings"),
    Battery("battery"),
    Autostart("autostart"),
    ;

    companion object {
        /** The step named [id], or null when nothing matches. */
        fun byId(id: String): OnboardingStep? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A system page the flow can offer to open. Parc never changes the setting itself: it opens the page
 * and the user flips the switch (PROJECT.md §6.3 item 4, "screens, not code").
 */
enum class OnboardingTarget {
    /** Parc's own app info page, where "Allow restricted settings" lives on Android 13+. */
    AppInfo,

    /** Android's default-home chooser. */
    HomeRole,

    /** The battery optimization list. */
    BatteryOptimization,

    /** Vendor Autostart. No public API exists; the first candidate is a vendor intent. */
    Autostart,

    /** Notification access under Special access (T15's listener). */
    NotificationAccess,

    /** Usage access under Special access (learned layer, screen time, data usage). */
    UsageAccess,
}

/** What a step can open: [target] plus the row label shown to the user. */
data class OnboardingAction(val target: OnboardingTarget, val label: String)

/** A target paired with the concrete action string that was picked for this device. */
data class ResolvedAction(val target: OnboardingTarget, val action: String)

/** One screen (S13): a title, plain body lines and zero or more settings pages to open. */
data class OnboardingStepSpec(
    val step: OnboardingStep,
    val title: String,
    val lines: List<String>,
    val actions: List<OnboardingAction>,
)

/**
 * What the flow can say about a step, given only what Parc is allowed to read.
 *
 * [Unknown] is not a failure: Parc has no API for vendor Autostart and does not ask for one, so the
 * screen claims nothing and just explains. Never guess a state (T20's rule, applied here).
 */
enum class OnboardingStatus { Done, Todo, Unknown }

/**
 * Readable state of the four asks. Every field is nullable; null means "not readable on this build
 * or this device", which resolves to [OnboardingStatus.Unknown]. All fields are plain values so the
 * resolution rules are unit-tested without Android.
 */
data class OnboardingProbes(
    val homeRoleHeld: Boolean? = null,
    val notificationAccessGranted: Boolean? = null,
    val batteryUnrestricted: Boolean? = null,
    val usageAccessGranted: Boolean? = null,
)

/**
 * Reads [OnboardingProbes] from the platform. Implementations must be cheap, must not cache across
 * a Settings visit, and should return null for a source that throws or is missing.
 */
interface OnboardingProbe {
    fun read(): OnboardingProbes
}
