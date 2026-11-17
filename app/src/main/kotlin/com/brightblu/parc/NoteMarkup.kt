package com.brightblu.parc

/*
 * T182: notes stay plain text with portable marks. Everything here is pure (no Android, no Compose)
 * so it is unit tested on the JVM.
 *   "# " heading, "- " bullet, "1. " numbered, "- [ ] " / "- [x] " checkbox,
 *   **bold**, *italic*, ***both***, ~~strike~~.
 * A mark that is not closed (or is followed or preceded by a space) is shown exactly as typed.
 */

internal enum class LineKind { Plain, Heading, Bullet, Numbered, Checkbox }

/** One styled piece of a line. [start] and [end] are offsets from the start of the line (marker included). */
internal data class StyledRun(
    val start: Int, val end: Int,
    val bold: Boolean = false, val italic: Boolean = false, val strike: Boolean = false,
    /** True for the mark characters themselves (`**`, `# `, `- [ ] `). They are hidden in View mode. */
    val mark: Boolean = false,
)

internal data class ParsedLine(
    val kind: LineKind,
    /** Offset of the line's first character in the whole note. */
    val offset: Int,
    /** Length of the block marker (`# `, `- `, `12. `, `- [x] `); 0 for plain lines. */
    val markerLength: Int,
    /** The number of a numbered line, otherwise 0. */
    val number: Int,
    val checked: Boolean,
    val raw: String,
    val runs: List<StyledRun>,
) {
    val content: String get() = raw.substring(markerLength)
    val end: Int get() = offset + raw.length
}

internal object NoteMarkup {
    fun parse(text: String): List<ParsedLine> {
        var offset = 0
        return text.split('\n').map { raw -> parseLine(raw, offset).also { offset += raw.length + 1 } }
    }

    fun parseLine(raw: String, offset: Int = 0): ParsedLine {
        var kind = LineKind.Plain
        var marker = 0
        var number = 0
        var checked = false
        val numberDigits = raw.takeWhile { it in '0'..'9' }.length
        when {
            raw.startsWith("- [ ] ") -> { kind = LineKind.Checkbox; marker = 6 }
            raw.startsWith("- [x] ") || raw.startsWith("- [X] ") -> { kind = LineKind.Checkbox; marker = 6; checked = true }
            raw.startsWith("- ") -> { kind = LineKind.Bullet; marker = 2 }
            raw.startsWith("# ") -> { kind = LineKind.Heading; marker = 2 }
            numberDigits in 1..9 && raw.startsWith(". ", numberDigits) -> {
                kind = LineKind.Numbered; marker = numberDigits + 2; number = raw.substring(0, numberDigits).toInt()
            }
        }
        val runs = ArrayList<StyledRun>()
        if (marker > 0) runs += StyledRun(0, marker, bold = kind == LineKind.Heading, mark = true)
        scan(raw, marker, raw.length, Triple(kind == LineKind.Heading, false, false), runs)
        return ParsedLine(kind, offset, marker, number, checked, raw, runs)
    }

    private fun scan(s: String, lo: Int, hi: Int, style: Triple<Boolean, Boolean, Boolean>, out: MutableList<StyledRun>) {
        fun plain(from: Int, to: Int) {
            if (to > from) out += StyledRun(from, to, style.first, style.second, style.third)
        }
        var i = lo
        var plainStart = lo
        while (i < hi) {
            val token = when {
                s.startsWith("***", i) -> "***"
                s.startsWith("**", i) -> "**"
                s.startsWith("~~", i) -> "~~"
                s[i] == '*' -> "*"
                else -> null
            }
            if (token == null) { i++; continue }
            val close = findClose(s, token, i + token.length, hi)
            if (close < 0) { i += token.length; continue }
            plain(plainStart, i)
            val inner = when (token) {
                "***" -> Triple(true, true, style.third)
                "**" -> Triple(true, style.second, style.third)
                "*" -> Triple(style.first, true, style.third)
                else -> Triple(style.first, style.second, true)
            }
            out += StyledRun(i, i + token.length, style.first, style.second, style.third, mark = true)
            scan(s, i + token.length, close, inner, out)
            out += StyledRun(close, close + token.length, style.first, style.second, style.third, mark = true)
            i = close + token.length
            plainStart = i
        }
        plain(plainStart, hi)
    }

    /** Index of the closing [token], or -1. The marked text must be non-empty and not start or end with a space. */
    private fun findClose(s: String, token: String, from: Int, hi: Int): Int {
        if (from >= hi || s[from] == ' ') return -1
        var j = from
        while (j < hi) {
            if (token == "*" && s.startsWith("**", j)) { j += 2; continue }
            if (s.startsWith(token, j) && j > from && s[j - 1] != ' ') return j
            j++
        }
        return -1
    }

    /** Plain text with the marks removed, for notification-free previews and search-free summaries. */
    fun stripped(line: ParsedLine): String =
        line.runs.filter { !it.mark }.joinToString("") { line.raw.substring(it.start, it.end) }
}

/** Flip a checkbox line between `- [ ] ` and `- [x] `. Other lines and unknown indexes are left alone. */
internal fun toggleNoteLine(text: String, index: Int): String = text.split('\n').mapIndexed { i, raw ->
    if (i != index) raw
    else when {
        raw.startsWith("- [ ] ") -> "- [x] " + raw.drop(6)
        raw.startsWith("- [x] ") || raw.startsWith("- [X] ") -> "- [ ] " + raw.drop(6)
        else -> raw
    }
}.joinToString("\n")

/**
 * Before 1.2.0 every `- ` line was a checklist item. In 1.2.0 `- ` is a plain bullet, so old notes
 * have their plain `- ` lines rewritten to `- [ ] ` once. Lines that already carry a checkbox stay.
 */
internal fun migrateLegacyChecklist(text: String): String = text.split('\n').joinToString("\n") { raw ->
    if (raw.startsWith("- ") && !raw.startsWith("- [ ] ") && !raw.startsWith("- [x] ") && !raw.startsWith("- [X] "))
        "- [ ] " + raw.drop(2) else raw
}
