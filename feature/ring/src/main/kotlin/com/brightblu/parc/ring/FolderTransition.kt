package com.brightblu.parc.ring

/** The folder travel is the open spring's progress; it may overshoot, the blend of scenes may not. */
internal object FolderTransition {
    fun emphasis(progress: Float): Float = progress.coerceIn(0f, 1f)
}

/** Shared by the ring and the host overlay for the existing All-apps list. */
fun joystickBack(dxDp: Float, dyDp: Float): Boolean = RingAim.isBack(dxDp, dyDp)
