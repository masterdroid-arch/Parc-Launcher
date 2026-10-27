package com.brightblu.parc

import android.view.WindowInsets
import com.brightblu.parc.ring.RingInsets
import com.brightblu.parc.ring.widestInsets

/**
 * T65: keep-outs follow whichever edge is widest on this device. System bars cover the
 * status bar, the nav bar (3-button) and the cutout region on some OEM builds; the gesture
 * types cover gesture navigation and the mandatory back/home strips on phones that expose
 * them. Types added in API 30 are safe at minSdk 33, and a device that reports 0 for the
 * gesture types falls back to the Metrics keep-out constants inside RingLayout.
 */
internal fun WindowInsets.parcInsets(density: Float): RingInsets {
    fun edges(type: Int): RingInsets {
        val inset = getInsetsIgnoringVisibility(type)
        return RingInsets(inset.left / density, inset.top / density,
            inset.right / density, inset.bottom / density)
    }
    return widestInsets(
        edges(WindowInsets.Type.systemBars()),
        edges(WindowInsets.Type.displayCutout()),
        edges(WindowInsets.Type.systemGestures()),
        edges(WindowInsets.Type.mandatorySystemGestures()),
    )
}
