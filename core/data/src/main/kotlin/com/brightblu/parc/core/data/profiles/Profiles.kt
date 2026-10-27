package com.brightblu.parc.core.data.profiles

import com.brightblu.parc.core.data.userSerial
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId

/**
 * How a profile relates to the calling user (T74).
 *
 * [Personal] is the user the launcher runs as, [Work] is a managed profile of that user, and
 * [Other] is any other user the system exposes (for example a second full user on a shared device).
 * The kind never changes while the profile exists; only the serial identifies it.
 */
enum class ProfileKind { Personal, Work, Other }

/** One profile as the launcher sees it. Immutable, non-null. [paused] is the work-profile pause flag. */
data class ProfileInfo(val serial: Long, val kind: ProfileKind, val paused: Boolean)

/**
 * The profile set behind the app list (T74). Immutable, non-null.
 *
 * [profiles] is empty until the first scan arrives; [loaded] tells the two states apart. A serial
 * that is missing from [profiles] is a profile that has been removed, but an unknown set must not
 * block launches (the source re-checks the handle at launch time).
 */
data class ProfileSet(
    val currentSerial: Long = 0L,
    val profiles: List<ProfileInfo> = emptyList(),
) {
    val loaded: Boolean get() = profiles.isNotEmpty()

    val hasWorkProfile: Boolean get() = profiles.any { it.kind == ProfileKind.Work }

    val workSerials: Set<Long>
        get() = profiles.asSequence()
            .filter { it.kind == ProfileKind.Work }
            .map { it.serial }
            .toSet()

    /** Info for [serial], or null when that profile is gone or was never seen. */
    fun info(serial: Long): ProfileInfo? = profiles.firstOrNull { it.serial == serial }

    fun kindOf(serial: Long): ProfileKind? = info(serial)?.kind

    fun kindOf(id: AppId): ProfileKind? = kindOf(id.userSerial())

    /** True only for a managed profile; other users and the personal user are not work. */
    fun isWork(serial: Long): Boolean = kindOf(serial) == ProfileKind.Work

    fun isWork(id: AppId): Boolean = isWork(id.userSerial())

    /** True when [serial] is a work profile that is currently paused (quiet mode). */
    fun isPaused(serial: Long): Boolean = info(serial)?.paused == true

    fun isPaused(id: AppId): Boolean = isPaused(id.userSerial())

    /** True for an app that belongs to a profile other than the personal one. */
    fun isProfileApp(id: AppId): Boolean = loaded && id.userSerial() != currentSerial

    /** True when [id] belongs to a profile that is no longer present. Unknown sets never match. */
    fun isRemoved(id: AppId): Boolean = loaded && info(id.userSerial()) == null
}

/** What happened to a launch request. Logged generically; never carries app text. */
enum class LaunchOutcome {
    Started,

    /** The profile is paused (quiet mode); the app cannot start until it is resumed. */
    ProfilePaused,

    /** The profile that owned the app is gone. */
    ProfileRemoved,

    /** The activity or the target user could not be started for any other reason. */
    Unavailable,
}

/** Work marker for the badge rule (T75): true only for a managed profile of the personal user. */
fun AppEntry.isWork(profiles: ProfileSet): Boolean = profiles.isWork(id)

/** Paused marker: the entry is dimmed and a launch is refused while its profile is paused. */
fun AppEntry.isProfilePaused(profiles: ProfileSet): Boolean = profiles.isPaused(id)
