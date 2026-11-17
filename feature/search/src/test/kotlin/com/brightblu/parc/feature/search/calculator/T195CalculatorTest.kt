package com.brightblu.parc.feature.search.calculator

import com.brightblu.parc.feature.search.CalculateRules
import com.brightblu.parc.feature.search.CalculateView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class T195CalculatorTest {
    private fun v(input: String): Double? = Calculator.evaluate(input)?.value

    @Test fun `all four operators in both spellings`() {
        assertEquals(7.0, v("3 + 4")!!, 0.0)
        assertEquals(1.0, v("5 - 4")!!, 0.0)
        assertEquals(12.0, v("3 × 4")!!, 0.0)
        assertEquals(12.0, v("3 * 4")!!, 0.0)
        assertEquals(2.5, v("5 ÷ 2")!!, 0.0)
        assertEquals(2.5, v("5 / 2")!!, 0.0)
    }

    @Test fun `percent of the left side after plus and minus`() {
        assertEquals(230.0, v("200 + 15%")!!, 1e-9)
        assertEquals(180.0, v("200 - 10%")!!, 1e-9)
        assertEquals(230.0, v("200+15 %")!!, 1e-9)
        assertEquals(110.25, v("100 + 5% + 5%")!!, 1e-9) // 105, then 5% of 105
    }

    @Test fun `percent of a number`() {
        assertEquals(12.0, v("15% of 80")!!, 1e-9)
        assertEquals(12.0, v("15%of80")!!, 1e-9)
        assertEquals(12.0, v("15 % OF 80")!!, 1e-9)
        assertEquals(112.0, v("100 + 15% of 80")!!, 1e-9)
    }

    @Test fun `of is only a word after a percent`() {
        assertNull(Calculator.evaluate("3 of 4"))
        assertNull(Calculator.evaluate("15% offer"))
    }

    @Test fun `plain percent and older behaviour stay`() {
        assertEquals(0.05, v("5%")!!, 0.0)
        assertEquals(5.0, v("100*5%")!!, 0.0)
        assertEquals(2.0, v("200 * 1%")!!, 1e-9)
    }

    @Test fun `powers unary minus decimals and whitespace`() {
        assertEquals(8.0, v(" 2 ^ 3 ")!!, 0.0)
        assertEquals(-4.0, v("-(2+2)")!!, 0.0)
        assertEquals(0.25, v("2 ^ -2")!!, 0.0)
        assertEquals(3.75, v("1,5 * 2.5")!!, 0.0)
        assertEquals(-3.0, v("--3 - 6")!!, 0.0)
    }

    @Test fun `division by zero and malformed input never crash`() {
        for (bad in listOf("1/0", "1 ÷ 0", "0/0", "2 +", "(2", "2)", "* 3", "abc", "1..2", "5 % 3", "2^99999", "()", "1 2")) {
            assertNull(bad, Calculator.evaluate(bad))
        }
    }

    @Test fun `mode view is empty then a result then a calm dots state`() {
        assertEquals(CalculateView.Empty, CalculateRules.view("   "))
        assertTrue(CalculateRules.view("15% of 80") is CalculateView.Result)
        assertEquals(CalculateView.Pending, CalculateRules.view("12 +"))
        assertEquals(CalculateView.Pending, CalculateRules.view("1/0"))
        assertEquals("…", CalculateRules.PendingText)
    }

    @Test fun `what Enter copies`() {
        assertEquals("12", CalculateRules.copyText("15% of 80"))
        assertEquals("230", CalculateRules.copyText("200 + 15%"))
        assertNull(CalculateRules.copyText(""))
        assertNull(CalculateRules.copyText("1/0"))
    }
}
