package com.brightblu.parc.feature.search

import com.brightblu.parc.model.MatchRange
import java.text.Normalizer

/**
 * Folded form of a source string plus a map from every folded character back to the half-open UTF-16
 * range of the source that produced it. Immutable, thread-safe.
 *
 * The map is what lets [MatchRange] offsets stay valid against the label the user sees while matching
 * runs on the folded text: one source character can fold to several characters (a decomposed letter
 * plus its combining mark, or an explicit fold such as ß to ss), and several source characters can
 * never fold to fewer than one.
 */
class NormalizedText internal constructor(
    val text: String,
    private val sourceStarts: IntArray,
    private val sourceEnds: IntArray,
) {
    val length: Int get() = text.length
    val isEmpty: Boolean get() = text.isEmpty()

    operator fun get(index: Int): Char = text[index]

    /** Source range that produced folded character [index]. */
    fun sourceStartAt(index: Int): Int = sourceStarts[index]

    /** Source range end (exclusive) that produced folded character [index]. */
    fun sourceEndAt(index: Int): Int = sourceEnds[index]

    /**
     * Maps a non-empty folded half-open range back to one source range. Used to report match offsets
     * on the original label after a fold that changed the character count.
     */
    fun sourceRange(start: Int, endExclusive: Int): MatchRange {
        require(start in 0 until length && endExclusive in (start + 1)..length) {
            "Folded range out of bounds: [$start, $endExclusive) of $length"
        }
        return MatchRange(sourceStarts[start], sourceEnds[endExclusive - 1])
    }
}

/**
 * Label folding for search (PROJECT.md §6.3): lowercase, then strip diacritics. Decomposition also
 * folds Cyrillic ё to е and й to и, the cheap Cyrillic normalisation used for names like
 * "Яндекс". Pure and stateless; safe from any thread.
 */
object TextNormalizer {

    /** Letters that have no canonical decomposition, so NFD cannot reach their plain form. */
    private val ExplicitFolds: Map<Int, String> = mapOf(
        'ß'.code to "ss",
        'ø'.code to "o",
        'ł'.code to "l",
        'đ'.code to "d",
        'æ'.code to "ae",
        'œ'.code to "oe",
        'ı'.code to "i",
        'þ'.code to "th",
        'ð'.code to "d",
        'ŋ'.code to "n",
        'ħ'.code to "h",
    )

    fun fold(source: String): String = normalize(source).text

    fun normalize(source: String): NormalizedText {
        if (source.isEmpty()) return NormalizedText("", IntArray(0), IntArray(0))
        val out = StringBuilder(source.length)
        val starts = ArrayList<Int>(source.length)
        val ends = ArrayList<Int>(source.length)
        var index = 0
        while (index < source.length) {
            val codePoint = source.codePointAt(index)
            val width = Character.charCount(codePoint)
            val folded = foldCodePoint(codePoint)
            for (char in folded) {
                out.append(char)
                starts.add(index)
                ends.add(index + width)
            }
            index += width
        }
        return NormalizedText(out.toString(), starts.toIntArray(), ends.toIntArray())
    }

    private fun foldCodePoint(codePoint: Int): String {
        val lower = String(Character.toChars(codePoint)).lowercase()
        if (lower.length == Character.charCount(lower.codePointAt(0))) {
            ExplicitFolds[lower.codePointAt(0)]?.let { return it }
        }
        if (lower.all { it.code < AsciiLimit }) return lower
        return stripMarks(Normalizer.normalize(lower, Normalizer.Form.NFD))
    }

    private fun stripMarks(text: String): String {
        if (text.none { isMark(it) }) return text
        val out = StringBuilder(text.length)
        for (char in text) if (!isMark(char)) out.append(char)
        return out.toString()
    }

    private fun isMark(char: Char): Boolean =
        Character.getType(char) == Character.NON_SPACING_MARK.toInt()

    private const val AsciiLimit = 0x80
}
