package com.brightblu.parc.ring

import androidx.compose.runtime.mutableStateOf

/**
 * T312: lets something outside the ring (the "Add to arc" chooser) turn a Home arc to a settled
 * offset so a newly added app is immediately visible. Mirrors [RingSteer], but carries a target
 * offset rather than an item index, because the chooser already knows the reveal offset.
 */
class RingReveal {
    /** One "turn arc [arcId] to [targetOffset]" request; [seq] makes repeated requests distinct. */
    data class Request(val arcId: String, val targetOffset: Float, val seq: Int)

    internal val request = mutableStateOf<Request?>(null)
    private var seq = 0

    fun turnTo(arcId: String, targetOffset: Float) {
        request.value = Request(arcId, targetOffset, ++seq)
    }
}
