package com.brightblu.parc.model

import android.view.MotionEvent
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.pointerInput

/**
 * T403: the pure rule behind "overlay protection". Android marks a touch with FLAG_WINDOW_IS_OBSCURED when
 * another app's window is drawn over Parc at the touched point. This is the same single test the framework
 * applies for `View.filterTouchesWhenObscured`. A partly covered window (FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
 * is allowed, exactly as in the framework, so a normal status-bar or keyboard overlap never blocks a tap.
 */
object ObscuredTouch {
    /** Same value as [MotionEvent.FLAG_WINDOW_IS_OBSCURED]; a test pins that they match. */
    const val FLAG_WINDOW_IS_OBSCURED: Int = 0x1

    /** True when a touch carrying [motionEventFlags] must be ignored by a protected confirm button. */
    fun shouldDrop(motionEventFlags: Int): Boolean = motionEventFlags and FLAG_WINDOW_IS_OBSCURED != 0
}

/** The flags of the latest MotionEvent a protected button saw. One small holder per button, never per touch. */
private class ObscuredFlags { @JvmField var flags: Int = 0 }

/**
 * Compose has no `filterTouchesWhenObscured`, so this is its equivalent: put it BEFORE `clickable` on a
 * confirm button (or around a row that holds one). A touch that Android marked as obscured is consumed on the
 * way down, so the button never sees it and nothing happens. Normal touches pass through untouched. It does
 * not change the window, never blocks screenshots or screen recording, and allocates nothing per touch.
 * The spy reads the raw MotionEvent flags (Compose does not expose them) and the second step, in the same
 * Initial pass further down the same chain, consumes the touch when the rule says drop.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.parcIgnoreObscuredTouches(): Modifier = composed {
    val holder = remember { ObscuredFlags() }
    Modifier
        .motionEventSpy { holder.flags = it.flags }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (ObscuredTouch.shouldDrop(holder.flags)) {
                        val changes = event.changes
                        for (i in changes.indices) changes[i].consume()
                    }
                }
            }
        }
}
