package com.brightblu.parc

import com.brightblu.parc.core.data.profiles.ProfileInfo
import com.brightblu.parc.core.data.profiles.ProfileKind
import com.brightblu.parc.core.data.profiles.ProfileSet

/**
 * T75 review fixture: the shape a device reports when one managed profile exists. The ids built
 * from it end in `@10`, so the badge, the paused dim and the launch routing can be reviewed without
 * creating a real work profile on the emulator (`pm create-user` needs a profile owner for pause).
 */
internal object WorkFixture {
    const val WorkSerial = 10L

    val profiles = ProfileSet(
        currentSerial = 0L,
        profiles = listOf(
            ProfileInfo(0L, ProfileKind.Personal, paused = false),
            ProfileInfo(WorkSerial, ProfileKind.Work, paused = false),
        ),
    )

    val pausedProfiles = profiles.copy(
        profiles = profiles.profiles.map { if (it.serial == WorkSerial) it.copy(paused = true) else it },
    )
}
