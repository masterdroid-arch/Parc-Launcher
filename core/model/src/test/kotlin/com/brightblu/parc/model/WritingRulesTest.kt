package com.brightblu.parc.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T386: the pure writing-surface rules, checked without a screen. */
class WritingRulesTest {

    @Test fun theSevenToolsMakeAStripThatMustScrollOnThePhone() {
        assertEquals(7, WritingRules.noteToolCount)
        // 7 × 48 dp, six 8 dp gaps and the two 16 dp strip insets.
        assertEquals(416f, WritingRules.noteStripContentWidthDp(), 0.001f)
        // The Pixel 8 test screen is 384 dp wide, so the last tool is reached by scrolling.
        assertTrue(WritingRules.noteStripScrolls(384f))
        // On a hypothetical wider screen the whole strip would fit and would not scroll.
        assertFalse(WritingRules.noteStripScrolls(416f))
        assertFalse(WritingRules.noteStripScrolls(0f))
    }

    @Test fun aStripOfNoToolsIsEmptyAndNeverScrolls() {
        assertEquals(0f, WritingRules.noteStripContentWidthDp(tools = 0), 0.001f)
        assertFalse(WritingRules.noteStripScrolls(100f, tools = 0))
    }

    @Test fun theStripKeepsA48DpTargetAndFiftyTwoDpHeight() {
        assertTrue(Metrics.noteToolTargetDp >= Metrics.minTouchDp)
        assertTrue(Metrics.noteToolStripDp >= Metrics.noteToolTargetDp)
        assertEquals(Metrics.noteToolTargetDp + 4f, Metrics.noteToolStripDp, 0.001f)
        assertTrue(Metrics.noteCaretGapDp >= 8f)
    }

    @Test fun theReplyComposerNeedsASelectionThatCanReply() {
        assertTrue(WritingRules.replyComposerVisible(selected = true, canReply = true))
        assertFalse(WritingRules.replyComposerVisible(selected = true, canReply = false))
        assertFalse(WritingRules.replyComposerVisible(selected = false, canReply = true))
    }

    @Test fun sendIsLiveOnlyForRealTextWhenNothingIsSending() {
        assertTrue(WritingRules.sendEnabled("Thanks, Sam", sending = false))
        assertFalse(WritingRules.sendEnabled("", sending = false))
        assertFalse(WritingRules.sendEnabled("   \n ", sending = false))
        assertFalse(WritingRules.sendEnabled("Thanks, Sam", sending = true))
    }
}
