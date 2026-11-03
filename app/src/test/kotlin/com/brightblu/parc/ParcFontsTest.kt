package com.brightblu.parc

import com.brightblu.parc.model.TypographyTokens
import org.junit.Assert.*
import org.junit.Test

class ParcFontsTest {
    @Test fun digitsAndPunctuationAreDotSafe() {
        assertTrue(isDotSafe("01234" + "56789"))
        assertTrue(isDotSafe("12:48"))
        assertTrue(isDotSafe("87%"))
        assertTrue(isDotSafe("3.5"))
        assertTrue(isDotSafe(""))
    }

    @Test fun everythingElseIsNotDotSafe() {
        for (s in listOf("a", " ", "12:48 87%", "h", "-1", "1,5", "+", "١", "½", "12m")) assertFalse(s, isDotSafe(s))
    }

    @Test fun splitKeepsTextAndAlternatesRuns() {
        val runs = splitDotRuns("12:48 87%")
        assertEquals(listOf(DotRun("12:48", true), DotRun(" ", false), DotRun("87%", true)), runs)
        assertEquals("12:48 87%", runs.joinToString("") { it.text })
    }

    @Test fun splitHandlesEdgesAndMixedText() {
        assertEquals(emptyList<DotRun>(), splitDotRuns(""))
        assertEquals(listOf(DotRun("42", true)), splitDotRuns("42"))
        assertEquals(listOf(DotRun("ab", false)), splitDotRuns("ab"))
        assertEquals(listOf(DotRun("-", false), DotRun("5", true), DotRun("km", false)), splitDotRuns("-5km"))
        for (s in listOf("9", "x", "9x9", "xx99xx", " : ")) {
            val runs = splitDotRuns(s)
            assertEquals(s, runs.joinToString("") { it.text })
            runs.zipWithNext().forEach { (a, b) -> assertNotEquals(a.dot, b.dot) }
            runs.forEach { assertEquals(it.dot, isDotSafe(it.text)) }
        }
    }

    @Test fun rolesMapToTheT141Tokens() {
        val t = TypographyTokens()
        assertSame(t.clockDot, DotRole.clockDot.token(t))
        assertSame(t.countDot, DotRole.countDot.token(t))
        assertSame(t.bigDot, DotRole.bigDot.token(t))
    }
}
