package com.brightblu.parc.core.data

import android.graphics.Bitmap
import com.brightblu.parc.core.data.profiles.LaunchOutcome
import com.brightblu.parc.core.data.profiles.ProfileKind

/** Plain activity snapshot; intentionally free of Android types so indexing stays unit-testable. */
internal data class SourceActivity(
    val component: String,
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val installTime: Long,
    val userSerial: Long,
)

/** Plain shortcut snapshot. */
internal data class SourceShortcut(val id: String, val label: String)

/**
 * One profile as the source sees it (T74). [paused] is the platform's quiet mode for that profile;
 * the personal profile is never paused. Immutable, plain data.
 */
internal data class SourceProfile(val serial: Long, val kind: ProfileKind, val paused: Boolean)

/** Every profile the launcher may list, plus the serial of the calling user. Plain data. */
internal data class SourceProfileSet(val currentSerial: Long, val profiles: List<SourceProfile>)

/** Single notification for every LauncherApps package event; callers debounce it. */
internal fun interface LauncherCallback {
    fun onPackagesChanged()
}

/**
 * Thin LauncherApps boundary. The real implementation touches Android; everything above it is
 * pure Kotlin. renderIcon returns null when the activity or its drawable cannot be resolved.
 *
 * Every method that takes a serial must use that serial: the source resolves it to a UserHandle and
 * never falls back to the calling user. [startMainActivity] answers with an outcome instead of
 * throwing so a paused or removed profile is a normal result, not a crash.
 */
internal interface LauncherSource {
    fun activities(): List<SourceActivity>

    /** Profiles of the calling user, including a managed profile when one exists. */
    fun profiles(): SourceProfileSet

    fun registerCallback(callback: LauncherCallback)
    fun unregisterCallback(callback: LauncherCallback)
    fun startMainActivity(component: String, userSerial: Long): LaunchOutcome
    fun shortcuts(component: String, userSerial: Long): List<SourceShortcut>
    fun hasShortcutHostPermission(): Boolean
    fun renderIcon(component: String, userSerial: Long, sizePx: Int): Bitmap?
}
