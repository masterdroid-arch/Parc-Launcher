package com.brightblu.parc.core.data.profiles

import com.brightblu.parc.core.data.SourceProfile
import com.brightblu.parc.core.data.SourceProfileSet

/**
 * Classifies one profile serial as seen from [currentSerial] (T74).
 *
 * [managedSerials] is the set of profile serials the calling user can access (the platform's
 * `UserManager.getUserProfiles()` minus the calling user). A serial outside that set is another
 * user, not a work profile. Pure; the Android boundary supplies the serials.
 */
internal fun classifyProfile(
    serial: Long,
    currentSerial: Long,
    managedSerials: Set<Long>,
): ProfileKind = when {
    serial == currentSerial -> ProfileKind.Personal
    serial in managedSerials -> ProfileKind.Work
    else -> ProfileKind.Other
}

/**
 * Maps the plain source snapshot to the published profile set. Duplicate serials keep the first
 * occurrence so a broken boundary cannot make `isWork` depend on list order twice. Pure.
 */
internal fun SourceProfileSet.toProfileSet(): ProfileSet {
    val seen = HashSet<Long>(profiles.size)
    val unique = ArrayList<ProfileInfo>(profiles.size)
    for (profile in profiles) {
        if (seen.add(profile.serial)) {
            unique += ProfileInfo(profile.serial, profile.kind, profile.paused)
        }
    }
    return ProfileSet(currentSerial = currentSerial, profiles = unique)
}

/** A one-profile snapshot: the fallback when the profile list cannot be read. Pure. */
internal fun personalOnlyProfileSet(currentSerial: Long): SourceProfileSet =
    SourceProfileSet(
        currentSerial = currentSerial,
        profiles = listOf(SourceProfile(currentSerial, ProfileKind.Personal, paused = false)),
    )
