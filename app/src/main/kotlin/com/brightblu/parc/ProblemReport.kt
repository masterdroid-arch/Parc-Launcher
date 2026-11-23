package com.brightblu.parc

import java.net.URLEncoder

/**
 * T395 (the parked T360): the text and the link behind Settings > This build > Report a problem. Pure, so the rules
 * are tested without a device.
 *
 * What is added to a report is a short fixed list: the Parc version and version code, the Android version and the
 * phone model. No names, no app lists, no usage data, no build commit. The optional crash note is the redacted
 * trace Parc already keeps for the diagnostics export, cut short, and only when the person turns it on.
 * Parc itself makes no network call: the link is opened by the browser, and the text is shown (and editable) first.
 */
internal object ProblemReport {
    const val IssuesUrl = "https://github.com/masterdroid-arch/Parc-Launcher/issues/new"

    /** The crash note is cut to about this many characters, on a whole line. */
    const val MaxCrashChars = 1_200

    /** The longest encoded body put into the link; longer text is cut and the person is told to use Copy details. */
    const val MaxEncodedBody = 6_000

    const val CrashHeader = "Last crash note:"
    const val CutNote = "[Cut to fit a web link. Use Copy details for the full text.]"

    /** The exact set of labels a report may carry under the line. Tests pin it. */
    val DetailLabels: List<String> = listOf("Parc version", "Version code", "Android", "Phone")

    fun title(versionName: String): String = "Problem with Parc ${oneLine(versionName)}"

    /** The four detail lines, in order. */
    fun details(versionName: String, versionCode: Int, androidRelease: String, sdkInt: Int,
        manufacturer: String, model: String): List<String> = listOf(
        "Parc version: ${oneLine(versionName)}",
        "Version code: $versionCode",
        "Android: ${oneLine(androidRelease)} (API $sdkInt)",
        "Phone: ${phone(manufacturer, model)}",
    )

    /** "Pixel 8" from Google gives "Google Pixel 8"; a model that already starts with its maker is kept as is. */
    fun phone(manufacturer: String, model: String): String {
        val maker = oneLine(manufacturer)
        val name = oneLine(model)
        return when {
            name.isEmpty() -> maker.ifEmpty { "unknown" }
            maker.isEmpty() || name.startsWith(maker, ignoreCase = true) -> name
            else -> "$maker $name"
        }
    }

    /** The text the person sees and edits: a short template, then the details under a line. No crash note. */
    fun body(details: List<String>): String = buildString {
        append("What happened:\n\n\nWhat I expected:\n\n\nHow to repeat it:\n\n\n")
        append("---\nAdded by Parc (change or delete anything you like):\n")
        append(details.joinToString("\n"))
    }

    /** The crash block that the optional switch adds under the details. */
    fun crashBlock(note: String): String = "$CrashHeader\n$note"

    /** A short, whole-line piece of the saved trace. Empty when there is nothing to say. */
    fun crashNote(trace: String?): String {
        if (trace.isNullOrBlank()) return ""
        val out = StringBuilder()
        for (line in trace.trimEnd().lines().dropWhile { it.isBlank() }) {
            val next = line.trimEnd()
            if (out.isNotEmpty() && out.length + next.length + 1 > MaxCrashChars) break
            if (out.isNotEmpty()) out.append('\n')
            out.append(next.take(MaxCrashChars))
            if (out.length >= MaxCrashChars) break
        }
        return out.toString()
    }

    /**
     * Adds or removes the crash block in the text the person may already have edited. Switching on twice adds it once.
     * Switching off removes the block; if they edited it, everything from its header to the end goes.
     */
    fun withCrashNote(text: String, note: String, on: Boolean): String {
        if (note.isEmpty()) return text
        val block = crashBlock(note)
        return if (on) {
            if (text.contains(CrashHeader)) text else text.trimEnd() + "\n\n" + block
        } else when {
            text.contains(block) -> text.replace("\n\n$block", "").replace(block, "").trimEnd()
            text.contains(CrashHeader) -> text.substringBefore(CrashHeader).trimEnd()
            else -> text
        }
    }

    /** The new-issue link with the title and body percent-encoded. A very long body is cut, with a visible note. */
    fun issueUrl(title: String, body: String): String {
        var text = body
        if (encode(text).length > MaxEncodedBody) {
            // Leave room for the note that says the text was cut.
            val room = MaxEncodedBody - encode("\n$CutNote").length
            while (encode(text).length > room) {
                text = text.dropLast(((encode(text).length - room) / 3).coerceAtLeast(1))
            }
            if (text.isNotEmpty() && Character.isHighSurrogate(text.last())) text = text.dropLast(1)
            text = text.trimEnd() + "\n" + CutNote
        }
        return "$IssuesUrl?title=${encode(title)}&body=${encode(text)}"
    }

    /** Percent-encoding with %20 for spaces (not +), so every reader of the link agrees. */
    fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun oneLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()
}
