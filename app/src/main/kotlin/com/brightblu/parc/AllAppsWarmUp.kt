package com.brightblu.parc

import com.brightblu.parc.core.data.profiles.ProfileSet
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.ring.RingLayout

/**
 * OPUS3: when to warm the All-apps ring in the background. Only while Home is up, the launcher is
 * resumed and live data is ready; never while search, a folder or the ring itself is shown. A
 * change of any input (apps, layout, geometry, turned rings, a newly loaded icon) re-warms after
 * [QuietMs] without changes, so a burst of icon loads causes one warm-up, not one per icon.
 */
internal object AllAppsWarmUp {
    const val QuietMs = 700L

    /** Home gets this long to finish its own first frames before any warm-up work starts. */
    const val StartDelayMs = 1_500L

    /** Icons are requested this many at a time, with a short pause, so warming never hogs the CPU. */
    const val ChunkSize = 16
    const val ChunkPauseMs = 80L

    /** Total pause a full warm-up adds on top of the icon loads themselves, for [appCount] apps. */
    fun pacingMs(appCount: Int): Long =
        if (appCount <= 0) 0L else ((appCount + ChunkSize - 1) / ChunkSize) * ChunkPauseMs

    data class Inputs(
        val wanted: Boolean,
        val appCount: Int,
        val layout: ParcLayout,
        val geometry: RingLayout,
        val rotations: Map<String, Float>,
        val iconRevision: Long,
        val profiles: ProfileSet,
        val markWorkApps: Boolean,
    )
}
