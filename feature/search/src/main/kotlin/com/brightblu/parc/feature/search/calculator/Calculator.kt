package com.brightblu.parc.feature.search.calculator

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor

/** A successfully evaluated expression. Immutable, non-null. [display] is what the row copies. */
data class Calculation(
    val expression: String,
    val value: Double,
    val display: String,
)

/**
 * Pure recursive-descent calculator for the search sheet (T67). No `eval`, no JavaScript, no
 * platform calls, so it runs on the JVM in tests and on the main thread in a few microseconds.
 *
 * Grammar (whitespace ignored anywhere):
 * ```
 * expression := term (('+' | '-') term)*
 * term       := unary (('*' | '/') unary)*
 * unary      := ('+' | '-') unary | power
 * power      := postfix ('^' unary)?          // right associative, so 2^3^2 = 512
 * postfix    := primary '%'*                  // 5% = 0.05
 * primary    := number | '(' expression ')' | 'sqrt' '(' expression ')'
 * ```
 *
 * The card is ambiguous about `5%`: as a binary modulo it has no right operand, while as a
 * postfix percent it is a complete expression. This implements the postfix reading (5% shows 0.05)
 * because PROJECT.md §7 F-03 asks for a calculator that answers what was typed. There is no binary
 * `%` operator, so `10 % 3` is rejected rather than silently treated as 10 percent followed by 3.
 *
 * Numbers accept `.` or `,` as the decimal separator (one per number; `12,5*2` = 25), which covers
 * locales that use a decimal comma. Output always uses `.` so a displayed value can be typed back.
 *
 * Results that are not finite (division by zero, `sqrt(-1)`, `2^1024`) produce null, so the sheet
 * shows no calculator row instead of `Infinity` or `NaN`.
 */
object Calculator {

    private const val MaxInputLength = 256
    private const val MaxDepth = 32

    /** Evaluation result, or null when [input] is blank, invalid or not finite. */
    fun evaluate(input: String): Calculation? {
        val source = input.trim()
        if (source.isEmpty() || source.length > MaxInputLength) return null
        val value = try {
            Parser(source).parse()
        } catch (_: InvalidExpression) {
            return null
        }
        if (!value.isFinite()) return null
        return Calculation(source, value, ResultFormat.format(value))
    }

    private class InvalidExpression : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class Parser(private val source: String) {
        private var position = 0
        private var depth = 0

        fun parse(): Double {
            val value = expression()
            skipSpaces()
            if (position != source.length) throw InvalidExpression()
            return value
        }

        private fun expression(): Double {
            var value = term()
            while (true) {
                skipSpaces()
                value = when {
                    take('+') -> value + term()
                    take('-') -> value - term()
                    else -> return value
                }
            }
        }

        private fun term(): Double {
            var value = unary()
            while (true) {
                skipSpaces()
                value = when {
                    take('*') -> value * unary()
                    take('/') -> {
                        val divisor = unary()
                        if (divisor == 0.0) throw InvalidExpression()
                        value / divisor
                    }
                    else -> return value
                }
            }
        }

        private fun unary(): Double {
            skipSpaces()
            enter()
            try {
                return when {
                    take('+') -> unary()
                    take('-') -> -unary()
                    else -> power()
                }
            } finally {
                depth--
            }
        }

        private fun power(): Double {
            val base = postfix()
            skipSpaces()
            if (!take('^')) return base
            enter()
            try {
                return Math.pow(base, unary())
            } finally {
                depth--
            }
        }

        private fun postfix(): Double {
            var value = primary()
            while (true) {
                skipSpaces()
                if (!take('%')) return value
                value /= 100.0
            }
        }

        private fun primary(): Double {
            skipSpaces()
            enter()
            try {
                if (take('(')) {
                    val value = expression()
                    skipSpaces()
                    if (!take(')')) throw InvalidExpression()
                    return value
                }
                if (position < source.length && source[position].isLetter()) return function()
                return number()
            } finally {
                depth--
            }
        }

        private fun function(): Double {
            val start = position
            while (position < source.length && source[position].isLetter()) position++
            val name = source.substring(start, position).lowercase()
            skipSpaces()
            if (!take('(')) throw InvalidExpression()
            val argument = expression()
            skipSpaces()
            if (!take(')')) throw InvalidExpression()
            return when (name) {
                "sqrt" -> Math.sqrt(argument)
                else -> throw InvalidExpression()
            }
        }

        private fun number(): Double {
            val start = position
            var separator = false
            while (position < source.length) {
                val char = source[position]
                when {
                    char.isDigit() -> position++
                    (char == '.' || char == ',') && !separator -> {
                        separator = true
                        position++
                    }
                    else -> break
                }
            }
            if (position == start) throw InvalidExpression()
            val text = source.substring(start, position).replace(',', '.')
            return text.toDoubleOrNull() ?: throw InvalidExpression()
        }

        private fun enter() {
            depth++
            if (depth > MaxDepth) throw InvalidExpression()
        }

        private fun skipSpaces() {
            while (position < source.length && source[position].isWhitespace()) position++
        }

        private fun take(char: Char): Boolean {
            if (position < source.length && source[position] == char) {
                position++
                return true
            }
            return false
        }
    }

    /** Display rounding: exact-looking decimals, no `Infinity`, no `-0`. */
    private object ResultFormat {
        fun format(value: Double): String {
            if (value == 0.0) return "0"
            val magnitude = abs(value)
            return when {
                magnitude >= 1e15 || magnitude < 1e-6 ->
                    BigDecimal.valueOf(value).round(MathContext(12)).stripTrailingZeros().toString()
                value == floor(value) -> value.toLong().toString()
                else -> BigDecimal.valueOf(value)
                    .setScale(10, RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString()
            }
        }
    }
}
