package com.brightblu.parc

import com.brightblu.parc.core.data.profiles.LaunchOutcome

/** T75: the exact toast text the card names. */
internal const val WorkProfilePausedNotice = "Work profile paused"

/** T75: a removed profile is explained with its own neutral line, never a stack trace or a name. */
internal const val WorkProfileRemovedNotice = "Work profile unavailable"

/**
 * T75: the message for a refused launch, or null when the failure needs no explanation (an
 * unavailable app keeps the old silent behaviour). Pure, so the mapping is unit-tested.
 */
internal fun workProfileNotice(outcome: LaunchOutcome): String? = when (outcome) {
    LaunchOutcome.ProfilePaused -> WorkProfilePausedNotice
    LaunchOutcome.ProfileRemoved -> WorkProfileRemovedNotice
    LaunchOutcome.Started, LaunchOutcome.Unavailable -> null
}
