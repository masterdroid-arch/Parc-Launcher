package com.brightblu.parc.core.data

import android.graphics.Bitmap
import com.brightblu.parc.core.data.profiles.LaunchOutcome
import com.brightblu.parc.core.data.profiles.ProfileKind

/** Convenience builder for the plain activity snapshot used across tests. */
internal fun sourceActivity(
    component: String,
    label: String,
    packageName: String = component.substringBefore('/'),
    isSystem: Boolean = false,
    installTime: Long = 0L,
    userSerial: Long = 0L,
): SourceActivity = SourceActivity(component, packageName, label, isSystem, installTime, userSerial)

/** Convenience builder for one source profile. */
internal fun sourceProfile(
    serial: Long,
    kind: ProfileKind = if (serial == 0L) ProfileKind.Personal else ProfileKind.Work,
    paused: Boolean = false,
): SourceProfile = SourceProfile(serial, kind, paused)

/** A source snapshot with the personal user at serial 0 unless a test says otherwise. */
internal fun sourceProfiles(
    currentSerial: Long = 0L,
    vararg profiles: SourceProfile,
): SourceProfileSet = SourceProfileSet(
    currentSerial = currentSerial,
    profiles = if (profiles.isEmpty()) listOf(sourceProfile(currentSerial, ProfileKind.Personal)) else profiles.toList(),
)

/** Scriptable [LauncherSource]; no Android calls beyond returning null. */
internal class FakeLauncherSource(
    var entries: List<SourceActivity> = emptyList(),
    var profileSet: SourceProfileSet = sourceProfiles(),
) : LauncherSource {
    var activitiesCalls = 0
        private set
    var profilesCalls = 0
        private set
    var shortcutsCalls = 0
        private set
    var shortcutsResult: List<SourceShortcut> = emptyList()
    var shortcutsThrows = false
    var hasShortcutPermission = true
    var launchFailure: RuntimeException? = null
    var launchOutcome: LaunchOutcome = LaunchOutcome.Started

    /** Component and serial of every start request, in order. */
    val launched = mutableListOf<Pair<String, Long>>()

    /** Component and serial of every shortcut request, in order. */
    val shortcutRequests = mutableListOf<Pair<String, Long>>()

    /** Component and serial of every icon request, in order. */
    val iconRequests = mutableListOf<Triple<String, Long, Int>>()

    private val callbacks = mutableListOf<LauncherCallback>()

    override fun activities(): List<SourceActivity> {
        activitiesCalls++
        return entries
    }

    override fun profiles(): SourceProfileSet {
        profilesCalls++
        return profileSet
    }

    override fun registerCallback(callback: LauncherCallback) {
        callbacks.add(callback)
    }

    override fun unregisterCallback(callback: LauncherCallback) {
        callbacks.remove(callback)
    }

    override fun startMainActivity(component: String, userSerial: Long): LaunchOutcome {
        launchFailure?.let { throw it }
        launched += component to userSerial
        return launchOutcome
    }

    override fun shortcuts(component: String, userSerial: Long): List<SourceShortcut> {
        shortcutsCalls++
        shortcutRequests += component to userSerial
        if (shortcutsThrows) throw SecurityException("denied")
        return shortcutsResult
    }

    override fun hasShortcutHostPermission(): Boolean = hasShortcutPermission

    override fun renderIcon(component: String, userSerial: Long, sizePx: Int): Bitmap? {
        iconRequests += Triple(component, userSerial, sizePx)
        return null
    }

    fun fireChanged() {
        callbacks.toList().forEach { it.onPackagesChanged() }
    }
}
