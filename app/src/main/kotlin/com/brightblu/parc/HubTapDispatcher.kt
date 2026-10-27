package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Released hub taps only; pointer slop and long presses remain owned by T43's HubGesture. */
internal class HubTapDispatcher(
    private val scope: CoroutineScope,
    private var enabled: Boolean = true,
    private val clock: () -> Long,
    private val singleTap: () -> Unit,
    private val doubleTap: () -> Unit,
) {
    private var pending: Job? = null
    private var firstAt = 0L

    fun tap() {
        if (!enabled) { singleTap(); return }
        val now = clock()
        if (pending != null) {
            val withinWindow = now - firstAt in 0..Metrics.hubDoubleTapMs
            cancel()
            if (withinWindow) { doubleTap(); return }
            singleTap()
        }
        firstAt = now
        pending = scope.launch {
            delay(Metrics.hubDoubleTapMs)
            pending = null
            singleTap()
        }
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        val wasPending = pending != null
        cancel()
        enabled = value
        if (wasPending) singleTap()
    }

    fun cancel() { pending?.cancel(); pending = null }
}
