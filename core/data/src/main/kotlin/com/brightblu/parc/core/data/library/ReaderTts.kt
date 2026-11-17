package com.brightblu.parc.core.data.library

import kotlin.math.abs
import java.util.Locale

/** One piece of a chapter handed to the system text-to-speech engine. Offsets are in [chapter]'s text. */
data class ReaderTtsChunk(
    val chapter: Int,
    val start: Int,
    val end: Int,
    val text: String,
) {
    init {
        require(chapter >= 0 && start >= 0 && end >= start)
        require(text == text.trim() && text.isNotEmpty())
    }
}

/**
 * T257: pure rules for reading a book aloud. The system engine speaks one short utterance at a
 * time, so a chapter is cut at sentence (or word) boundaries. The offsets stay in the same
 * character space as the reader's position and highlights. No platform code here.
 */
object ReaderTtsRules {
    /** The speed choices shown in the reading sheet. Values are TTS speech-rate multipliers. */
    val Speeds: List<Float> = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

    const val MinSpeed = 0.5f
    const val MaxSpeed = 2f
    const val DefaultSpeed = 1f

    /**
     * Android's TTS engines reject very long strings (TextToSpeech.getMaxSpeechInputLength() is
     * 4000). Keep well below it so a long paragraph still fits.
     */
    const val MaxChunkChars = 3000

    fun clampSpeed(value: Float): Float =
        if (!value.isFinite()) DefaultSpeed else value.coerceIn(MinSpeed, MaxSpeed)

    /** The closest speed chip, so a stored old or hand-edited value always lands on a real choice. */
    fun nearestSpeed(value: Float): Float {
        val safe = clampSpeed(value)
        return Speeds.minByOrNull { abs(it - safe) } ?: DefaultSpeed
    }

    fun nextSpeed(value: Float): Float {
        val index = Speeds.indexOf(nearestSpeed(value))
        return Speeds[(index + 1) % Speeds.size]
    }

    fun speedLabel(value: Float): String {
        val safe = nearestSpeed(value)
        return when {
            safe == safe.toInt().toFloat() -> "${safe.toInt()}x"
            else -> String.format(Locale.US, "%.2fx", safe).replace(Regex("0x$"), "x")
        }
    }

    /**
     * The reading order from [chapter]/[offset] to the end of the book. Blank chapters are skipped;
     * a chapter whose text is shorter than [offset] contributes nothing but the next chapter does.
     */
    fun plan(doc: ReaderBookDoc, chapter: Int, offset: Int,
        maxChars: Int = MaxChunkChars): List<ReaderTtsChunk> {
        if (doc.chapters.isEmpty()) return emptyList()
        val first = chapter.coerceIn(0, doc.chapters.lastIndex)
        val safeMax = maxChars.coerceIn(200, MaxChunkChars)
        val chunks = mutableListOf<ReaderTtsChunk>()
        for (index in first..doc.chapters.lastIndex) {
            val text = doc.chapters[index].text
            val from = if (index == first) offset.coerceIn(0, text.length) else 0
            chunks += chunkText(text, index, from, safeMax)
        }
        return chunks
    }

    /** Cut one chapter from [fromOffset]. The returned text is trimmed; offsets still match the chapter. */
    fun chunkText(text: String, chapter: Int, fromOffset: Int = 0,
        maxChars: Int = MaxChunkChars): List<ReaderTtsChunk> {
        if (text.isEmpty() || chapter < 0) return emptyList()
        val limit = maxChars.coerceIn(200, MaxChunkChars)
        val result = mutableListOf<ReaderTtsChunk>()
        var at = fromOffset.coerceIn(0, text.length)
        while (at < text.length) {
            while (at < text.length && text[at].isWhitespace()) at++
            if (at >= text.length) break
            val hardEnd = (at + limit).coerceAtMost(text.length)
            var cut = hardEnd
            if (hardEnd < text.length) {
                val earliest = at + limit / 2
                val sentence = lastSentenceBreak(text, earliest, hardEnd)
                cut = sentence ?: lastWhitespace(text, earliest, hardEnd) ?: hardEnd
            }
            var end = cut.coerceAtMost(text.length)
            while (end > at && text[end - 1].isWhitespace()) end--
            if (end > at) result += ReaderTtsChunk(chapter, at, end, text.substring(at, end))
            // Always move forward, even when a chunk was only whitespace or a hard cut.
            at = cut.coerceAtLeast(at + 1)
        }
        return result
    }

    private fun lastSentenceBreak(text: String, earliest: Int, endExclusive: Int): Int? {
        for (index in endExclusive - 1 downTo earliest.coerceAtLeast(0)) {
            val c = text[index]
            if (c == '\n') {
                if (index + 1 > earliest) return index + 1
                continue
            }
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                var after = index + 1
                while (after < text.length && text[after] in "\"'\u201d\u2019\u00bb)]}") after++
                if (after < text.length && text[after].isWhitespace()) return after + 1
                if (after > index + 1) return after
                return index + 1
            }
        }
        return null
    }

    private fun lastWhitespace(text: String, earliest: Int, endExclusive: Int): Int? {
        for (index in endExclusive - 1 downTo earliest.coerceAtLeast(0)) {
            if (text[index].isWhitespace()) return index
        }
        return null
    }
}
