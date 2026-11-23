package com.brightblu.parc

import androidx.compose.runtime.mutableStateMapOf

/**
 * T411: first-run tips queue instead of stacking.
 *
 * Three surfaces can teach something on Home: the "Getting around" card, one gesture chip, and the hub's
 * page-name chip. Each used to decide for itself, so two could be on screen at once. Now ONE rule picks the
 * single tip this launch may show, most important first, and every surface asks it before it draws:
 *  1. Getting around (until its "Got it" is tapped, on every launch),
 *  2. the next Home gesture chip,
 *  3. the page-name chip.
 * Whatever is not picked waits for the next launch. The new-app bar waits for a tip to finish, and tips
 * wait for the what's-new dialog.
 */
internal enum class TipKind { GettingAround, HomeGesture, PageName }

/** Pure choice: which single tip is next, given what the person has already seen. */
internal object TipQueue {
    fun pick(gettingAroundSeen: Boolean, seenHints: Set<String>): TipKind? = when {
        !gettingAroundSeen -> TipKind.GettingAround
        GestureHintRules.nextOnHome(seenHints) != null -> TipKind.HomeGesture
        GestureHint.PageName.id !in seenHints -> TipKind.PageName
        else -> null
    }
}

/**
 * The state of one start of Parc. The pick is made once, from what was saved when the first surface asked,
 * and is then fixed: a tip that is dismissed never lets the next one in during the same start.
 */
internal class TipLatch {
    private var decided = false
    private var picked: TipKind? = null
    private var claimed = false
    private val showing = mutableStateMapOf<TipKind, Boolean>()
    private val whatsNew = mutableStateMapOf<Unit, Boolean>()

    /** True while any tip is drawn. Compose-observable, so a waiting notice can react. */
    val tipOnScreen: Boolean get() = showing.values.any { it }

    /** True while the what's-new dialog is open: tips wait for it. Compose-observable. */
    val whatsNewOpen: Boolean get() = whatsNew.getOrDefault(Unit, false)

    private fun pickOnce(gettingAroundSeen: Boolean, seenHints: Set<String>): TipKind? {
        if (!decided) { picked = TipQueue.pick(gettingAroundSeen, seenHints); decided = true }
        return picked
    }

    /** May [kind] be on screen now? Used by the Getting around card, which stays until it is tapped. */
    fun allowed(kind: TipKind, gettingAroundSeen: Boolean, seenHints: Set<String>): Boolean =
        pickOnce(gettingAroundSeen, seenHints) == kind && !whatsNewOpen

    /** A chip asks once: the first yes is the only yes of this start. */
    fun claim(kind: TipKind, gettingAroundSeen: Boolean, seenHints: Set<String>): Boolean {
        if (claimed || !allowed(kind, gettingAroundSeen, seenHints)) return false
        claimed = true
        return true
    }

    fun setShowing(kind: TipKind, on: Boolean) { if (showing[kind] != on) showing[kind] = on }

    fun setWhatsNewOpen(open: Boolean) { if (whatsNew[Unit] != open) whatsNew[Unit] = open }

    /** "Show hints again": the next ask picks afresh. */
    fun reset() { decided = false; picked = null; claimed = false }
}

internal object TipSession {
    val latch = TipLatch()
}
