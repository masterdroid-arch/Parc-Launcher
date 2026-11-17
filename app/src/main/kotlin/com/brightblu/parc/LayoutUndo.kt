package com.brightblu.parc

import com.brightblu.parc.model.ParcLayout

/**
 * T245: a bounded, main-confined history of the layout before each accepted change. Each step keeps
 * the short words the Undo chip shows, so undoing the newest step leaves the previous words visible.
 * Pure: no Android types and no time; the host decides when the history expires.
 */
internal class LayoutUndoHistory(private val maxSteps: Int = 24) {
    private data class Step(val layout: ParcLayout, val label: String)

    private val steps = ArrayDeque<Step>()

    val available: Boolean get() = steps.isNotEmpty()
    val label: String get() = steps.lastOrNull()?.label.orEmpty()
    val depth: Int get() = steps.size

    fun record(before: ParcLayout, label: String) {
        steps.addLast(Step(before, label))
        while (steps.size > maxSteps) steps.removeFirst()
    }

    /** The newest step, already removed. Null when there is nothing to undo. */
    fun undo(): ParcLayout? = steps.removeLastOrNull()?.layout

    fun clear() = steps.clear()
}
