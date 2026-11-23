package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.WritingRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T386: the two writing surfaces keep their targets and never hide the action under the keyboard. */
class T386Test {
    private val root = sequenceOf(File("."), File(".."))
        .first { File(it, "app/src/main/kotlin/com/brightblu/parc/NoteEditorUi.kt").isFile }
    private fun source(relative: String) = File(root, relative).readText()

    @Test fun notesUsesOneFiftyTwoDpStripWithSevenFortyEightDpTools() {
        val editor = source("app/src/main/kotlin/com/brightblu/parc/NoteEditorUi.kt")
        assertTrue(editor.contains("height(Metrics.noteToolStripDp.dp)"))
        assertTrue(editor.contains("horizontalScroll(rememberScrollState())"))
        assertTrue(editor.contains("size(Metrics.noteToolTargetDp.dp)"))
        assertTrue(editor.contains("bottom = Metrics.noteCaretGapDp.dp"))
        // The field is the only weight(1f) writing surface, so it owns the remaining height.
        val body = editor.substringAfter("private fun NoteEditBody")
        assertTrue(body.contains("Modifier.weight(1f).fillMaxWidth()"))
        assertTrue(body.contains("Modifier.fillMaxSize()"))
        val tools = body.split("ToolButton(\"").size - 1
        assertEquals("all seven formatting tools stay", 7, tools)
        assertEquals(52f, Metrics.noteToolStripDp, 0.001f)
        assertTrue(Metrics.noteToolTargetDp >= 48f)
    }

    @Test fun theReplySheetKeepsDraftCancelAndSendTogetherAboveTheIme() {
        val page = source("app/src/main/kotlin/com/brightblu/parc/CommsPage.kt")
        val sheet = page.substringAfter("private fun CommsReplySheet").substringBefore("private fun CommsSendAction")
        assertTrue(sheet.contains("Dialog("))
        assertTrue(sheet.contains("WindowInsets.ime.union(WindowInsets.navigationBars)"))
        assertTrue(sheet.contains("Reply to "))
        assertTrue(sheet.contains("ConsoleAction(\"Cancel\""))
        assertTrue(sheet.contains("BasicTextField("))
        assertTrue(sheet.contains("maxLines = 3"))
        assertTrue(sheet.contains("CommsSendAction("))
        assertTrue(sheet.contains("WritingRules.sendEnabled"))
        // The old inline field and its in-card Cancel are gone from the scrolling card.
        val card = page.substringAfter("private fun LiveConversationCard").substringBefore("private fun CommsReplySheet")
        assertFalse(card.contains("BasicTextField("))
        assertFalse(card.contains("ConsoleAction(\"Cancel\""))
    }

    @Test fun theSheetIsShownOnlyForASelectedConversationThatCanReply() {
        val page = source("app/src/main/kotlin/com/brightblu/parc/CommsPage.kt")
        assertTrue(page.contains("WritingRules.replyComposerVisible(selected != null, replying.canReply)"))
        assertTrue(WritingRules.replyComposerVisible(true, true))
        assertFalse(WritingRules.replyComposerVisible(true, false))
        assertFalse(WritingRules.replyComposerVisible(false, true))
    }

    @Test fun aSentReplyClosesTheComposerAndSendNeedsText() {
        val page = source("app/src/main/kotlin/com/brightblu/parc/CommsPage.kt")
        assertTrue(page.contains("if (result == ReplyResult.Sent)"))
        assertTrue(page.contains("selected = null"))
        assertTrue(WritingRules.sendEnabled("Thanks, Sam", sending = false))
        assertFalse(WritingRules.sendEnabled(" ", sending = false))
        assertFalse(WritingRules.sendEnabled("Thanks, Sam", sending = true))
    }
}
