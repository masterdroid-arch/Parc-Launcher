package com.brightblu.parc

/** Pure text edits behind the Edit mode toolbar and the Enter key. T182. */
internal data class NoteEdit(val text: String, val selStart: Int, val selEnd: Int)

internal object NoteEditing {
    /**
     * Toggles a block mark on the current line, or on every line the selection touches. If all those lines
     * already have [kind] the mark is removed, otherwise it is set (replacing any other block mark).
     */
    fun toggleLine(text: String, selStart: Int, selEnd: Int, kind: LineKind): NoteEdit {
        require(kind != LineKind.Plain)
        val lines = NoteMarkup.parse(text)
        val lo = minOf(selStart, selEnd).coerceIn(0, text.length)
        val hi = maxOf(selStart, selEnd).coerceIn(0, text.length)
        val touched = lines.indices.filter { lines[it].end >= lo && lines[it].offset <= hi }
            .let { list -> // a selection that ends right after a line break does not touch the next line
                if (hi > lo && list.size > 1 && lines[list.last()].offset == hi) list.dropLast(1) else list
            }
        val remove = touched.all { lines[it].kind == kind }
        var number = 0
        val delta = IntArray(lines.size)
        val out = StringBuilder()
        lines.forEachIndexed { i, line ->
            var raw = line.raw
            if (i in touched) {
                val body = raw.substring(line.markerLength)
                val marker = if (remove) "" else when (kind) {
                    LineKind.Heading -> "# "
                    LineKind.Bullet -> "- "
                    LineKind.Checkbox -> "- [ ] "
                    LineKind.Numbered -> "${++number}. "
                    LineKind.Plain -> ""
                }
                raw = marker + body
                delta[i] = marker.length - line.markerLength
            }
            if (i > 0) out.append('\n')
            out.append(raw)
        }
        fun map(p: Int): Int {
            var shift = 0
            for (i in lines.indices) {
                val line = lines[i]
                if (p < line.offset) break
                if (p <= line.end) { // inside this line
                    val inLine = (p - line.offset) + delta[i]
                    return line.offset + shift + maxOf(0, inLine)
                }
                shift += delta[i]
            }
            return p + shift
        }
        return NoteEdit(out.toString(), map(selStart), map(selEnd))
    }

    /**
     * Wraps the selection in [mark] (`**`, `*`, `~~`). Wrapped text is unwrapped again. With no selection the pair is inserted
     * and the caret is placed between the two halves.
     */
    fun wrap(text: String, selStart: Int, selEnd: Int, mark: String): NoteEdit {
        val lo = minOf(selStart, selEnd).coerceIn(0, text.length)
        val hi = maxOf(selStart, selEnd).coerceIn(0, text.length)
        if (lo == hi) {
            return NoteEdit(text.substring(0, lo) + mark + mark + text.substring(hi), lo + mark.length, lo + mark.length)
        }
        val before = text.substring(0, lo)
        val after = text.substring(hi)
        val wrapped = before.endsWith(mark) && after.startsWith(mark) &&
            (mark != "*" || (!before.endsWith("**") && !after.startsWith("**")))
        return if (wrapped) {
            NoteEdit(before.dropLast(mark.length) + text.substring(lo, hi) + after.drop(mark.length),
                lo - mark.length, hi - mark.length)
        } else {
            NoteEdit(before + mark + text.substring(lo, hi) + mark + after, lo + mark.length, hi + mark.length)
        }
    }

    /**
     * Called when the field's text changed by exactly one inserted line break at the caret. Returns the text with the list
     * continued (or ended), or null when the change is not an Enter or the line is not a list line.
     */
    fun onEnter(old: String, new: String, caret: Int): NoteEdit? {
        if (caret < 1 || caret > new.length || new[caret - 1] != '\n') return null
        if (new.length != old.length + 1 || new.removeRange(caret - 1, caret) != old) return null
        val at = caret - 1 // where the break went in `old`
        val lineStart = old.lastIndexOf('\n', at - 1).let { if (at == 0) 0 else it + 1 }
        val lineEnd = old.indexOf('\n', at).let { if (it < 0) old.length else it }
        val line = NoteMarkup.parseLine(old.substring(lineStart, lineEnd), lineStart)
        if (line.kind == LineKind.Plain || line.kind == LineKind.Heading) return null
        val inMarker = at - lineStart < line.markerLength
        if (inMarker) return null
        if (line.content.isEmpty() && at == lineEnd) {
            // Enter on an empty item ends the list: remove the mark and do not add a line.
            return NoteEdit(old.substring(0, lineStart) + old.substring(lineEnd), lineStart, lineStart)
        }
        val next = when (line.kind) {
            LineKind.Bullet -> "- "
            LineKind.Checkbox -> "- [ ] "
            LineKind.Numbered -> "${line.number + 1}. "
            else -> ""
        }
        val text = old.substring(0, at) + "\n" + next + old.substring(at)
        return NoteEdit(text, at + 1 + next.length, at + 1 + next.length)
    }
}
