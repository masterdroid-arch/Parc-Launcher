package com.brightblu.parc.model

/**
 * T386: the pure rules behind the two writing surfaces, kept out of the Composables so they can be
 * tested without a screen.
 *
 * Notes has one horizontally scrolling formatting strip: every tool is a full 48 dp square, so at
 * the 384 dp screen width the seven tools (plus their gaps and insets) are wider than the strip and
 * the last one is reached by scrolling. The reply composer is shown only for a conversation that can
 * reply, and Send is live only for real text when nothing is already being sent.
 */
object WritingRules {
    /** The seven tools Notes offers: checkbox, bullet, numbered, heading, bold, italic, strike. */
    const val noteToolCount = 7

    /** The width of the seven 48 dp tools, their gaps and the strip's leading and trailing insets. */
    fun noteStripContentWidthDp(
        tools: Int = noteToolCount,
        targetDp: Float = Metrics.noteToolTargetDp,
        gapDp: Float = Metrics.noteToolGapDp,
        insetDp: Float = Metrics.noteToolStripInsetDp,
    ): Float {
        if (tools <= 0) return 0f
        return tools * targetDp + (tools - 1) * gapDp + 2 * insetDp
    }

    /** True when a strip this wide must scroll to reach its last tool; never when it fits whole. */
    fun noteStripScrolls(availableWidthDp: Float, tools: Int = noteToolCount): Boolean =
        availableWidthDp > 0f && noteStripContentWidthDp(tools) > availableWidthDp

    /** The reply sheet is for the selected conversation, and only when the app allows a reply. */
    fun replyComposerVisible(selected: Boolean, canReply: Boolean): Boolean = selected && canReply

    /** Send is live for text that is not only whitespace, and never while a send is in flight. */
    fun sendEnabled(draft: String, sending: Boolean): Boolean = !sending && draft.isNotBlank()
}
