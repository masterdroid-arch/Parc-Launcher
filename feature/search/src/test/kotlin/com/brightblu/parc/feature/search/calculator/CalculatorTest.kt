package com.brightblu.parc.feature.search.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CalculatorTest {

    private fun value(input: String): Double? = Calculator.evaluate(input)?.value

    private fun display(input: String): String? = Calculator.evaluate(input)?.display

    @Test
    fun `multiplication binds before addition`() {
        assertEquals(14.0, value("2+3*4")!!, 0.0)
        assertEquals(20.0, value("(2+3)*4")!!, 0.0)
    }

    @Test
    fun `subtraction and division are left associative`() {
        assertEquals(5.0, value("10-2-3")!!, 0.0)
        assertEquals(1.0, value("8/4/2")!!, 0.0)
    }

    @Test
    fun `power is right associative`() {
        assertEquals(512.0, value("2^3^2")!!, 0.0)
        assertEquals(256.0, value("2^8")!!, 0.0)
    }

    @Test
    fun `a unary minus applies to the whole power`() {
        assertEquals(-4.0, value("-2^2")!!, 0.0)
        assertEquals(0.25, value("2^-2")!!, 0.0)
        assertEquals(5.0, value("--5")!!, 0.0)
    }

    @Test
    fun `percent is a postfix divide by one hundred`() {
        assertEquals(0.05, value("5%")!!, 0.0)
        assertEquals(5.0, value("100*5%")!!, 0.0)
        assertEquals(100.0, value("50%*200")!!, 0.0)
        assertEquals(0.0005, value("5%%")!!, 0.0)
    }

    @Test
    fun `there is no binary modulo`() {
        assertNull(Calculator.evaluate("10 % 3"))
    }

    @Test
    fun `square root accepts a nested expression`() {
        assertEquals(3.0, value("sqrt(9)")!!, 0.0)
        assertEquals(5.0, value("sqrt(9+16)")!!, 0.0)
        assertEquals(3.0, value("SQRT ( 9 )")!!, 0.0)
    }

    @Test
    fun `division by zero has no result`() {
        assertNull(Calculator.evaluate("1/0"))
        assertNull(Calculator.evaluate("0/0"))
        assertNull(Calculator.evaluate("5/(3-3)"))
    }

    @Test
    fun `a square root of a negative number has no result`() {
        assertNull(Calculator.evaluate("sqrt(-1)"))
        assertNull(Calculator.evaluate("sqrt(0-4)"))
    }

    @Test
    fun `overflow is rejected rather than shown as infinity`() {
        assertNull(Calculator.evaluate("2^1024"))
        assertNull(Calculator.evaluate("10^309"))
        assertNull(Calculator.evaluate("9^999"))
    }

    @Test
    fun `a finite huge number still displays`() {
        val result = Calculator.evaluate("10^308")
        assertNotNull(result)
        assertEquals(true, result!!.value.isFinite())
        assertEquals(true, result.display.contains("E"))
    }

    @Test
    fun `a decimal comma is accepted`() {
        assertEquals(25.0, value("12,5*2")!!, 0.0)
        assertEquals(0.5, value(",5")!!, 0.0)
        assertEquals(1.0, value("1,")!!, 0.0)
    }

    @Test
    fun `two separators in one number are rejected`() {
        assertNull(Calculator.evaluate("1.5,2"))
        assertNull(Calculator.evaluate("1,5.2"))
        assertNull(Calculator.evaluate("1.2.3"))
    }

    @Test
    fun `a plain number is a valid expression`() {
        assertEquals(42.0, value("42")!!, 0.0)
        assertEquals("42", display("42"))
    }

    @Test
    fun `ordinary words are not expressions`() {
        assertNull(Calculator.evaluate("maps"))
        assertNull(Calculator.evaluate("instagram"))
        assertNull(Calculator.evaluate("parc launcher"))
    }

    @Test
    fun `malformed input has no result`() {
        assertNull(Calculator.evaluate(""))
        assertNull(Calculator.evaluate("   "))
        assertNull(Calculator.evaluate("(3+4"))
        assertNull(Calculator.evaluate("3+4)"))
        assertNull(Calculator.evaluate("2**3"))
        assertNull(Calculator.evaluate("2+"))
        assertNull(Calculator.evaluate("sqrt 9"))
        assertNull(Calculator.evaluate("2(3)"))
        assertNull(Calculator.evaluate("1e3"))
        assertNull(Calculator.evaluate("sqrt(9"))
        assertNull(Calculator.evaluate("abs(3)"))
    }

    @Test
    fun `input length and nesting are bounded`() {
        assertNull(Calculator.evaluate("1+".repeat(200) + "1"))
        assertNull(Calculator.evaluate("(".repeat(100) + "1" + ")".repeat(100)))
        assertNotNull(Calculator.evaluate("(".repeat(4) + "1" + ")".repeat(4)))
    }

    @Test
    fun `whitespace anywhere is ignored`() {
        assertEquals(15.0, value(" 12 * ( 3 + 4 ) / 7 + 3 ")!!, 0.0)
    }

    @Test
    fun `display trims floating point noise`() {
        assertEquals("0.3", display("0.1+0.2"))
        assertEquals("0.3333333333", display("1/3"))
        assertEquals("0.6666666667", display("2/3"))
        assertEquals("256", display("2^8"))
        assertEquals("14", display("2*(3+4)"))
    }

    @Test
    fun `negative zero displays as zero`() {
        assertEquals("0", display("-0"))
        assertEquals("0", display("0-0"))
        assertEquals("0", display("-0.0"))
    }

    @Test
    fun `very small results use scientific notation`() {
        assertEquals("1E-7", display("0.0000001"))
        assertEquals("0.000001", display("0.000001"))
    }

    @Test
    fun `the expression is returned trimmed`() {
        val result = Calculator.evaluate("  2+2  ")
        assertEquals("2+2", result!!.expression)
        assertEquals("4", result.display)
    }
}
