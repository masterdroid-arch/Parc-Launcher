package com.brightblu.parc.core.data.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommsReplyPlannerTest {

    @Test
    fun `the first free form action wins`() {
        val actions = listOf(RawAction(false), RawAction(true), RawAction(true))
        assertEquals(1, CommsReplyPlanner.replyAction(actions))
        assertTrue(CommsReplyPlanner.canReply(actions))
    }

    @Test
    fun `no free form action means no reply`() {
        assertNull(CommsReplyPlanner.replyAction(listOf(RawAction(false), RawAction(false))))
        assertNull(CommsReplyPlanner.replyAction(emptyList()))
        assertFalse(CommsReplyPlanner.canReply(emptyList()))
    }
}
