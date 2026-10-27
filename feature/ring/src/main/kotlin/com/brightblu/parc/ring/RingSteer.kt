package com.brightblu.parc.ring

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * OPUS4: lets something outside the ring (the All-apps letter strip) turn the All-apps ring to an
 * item, and read which item is at the front while the ring is turned by hand. Main thread only.
 */
class RingSteer {
    /** One "bring item [itemIndex] to the front" request; [seq] makes repeated requests distinct. */
    data class Request(val itemIndex: Int, val animate: Boolean, val seq: Int)

    internal val request = mutableStateOf<Request?>(null)
    private var seq = 0
    private val front = mutableFloatStateOf(Float.NaN)

    /** Settled-slot offset of the All-apps ring (rounded), NaN until the ring has been seen. */
    val frontOffset: Float get() = front.floatValue

    /** Turns the ring so [itemIndex] is at the front: instantly while dragging, sprung on a tap. */
    fun turnTo(itemIndex: Int, animate: Boolean) {
        request.value = Request(itemIndex, animate, ++seq)
    }

    /** Renderer side: called with the live offset; only a change of whole slot is published. */
    internal fun report(offset: Float) {
        if (!offset.isFinite()) return
        val rounded = kotlin.math.round(offset)
        if (rounded != front.floatValue) front.floatValue = rounded
    }
}
