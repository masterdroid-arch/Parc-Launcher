package com.brightblu.parc

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T395: the report text carries only the allowed details, and the link is the project's new-issue page. */
class T395ProblemReportTest {
    private val details = ProblemReport.details("1.6.0", 15018, "14", 34, "Google", "Pixel 8")

    @Test fun detailsHaveOnlyTheAllowedFields() {
        assertEquals(ProblemReport.DetailLabels, details.map { it.substringBefore(":") })
        assertEquals(listOf("Parc version: 1.6.0", "Version code: 15018", "Android: 14 (API 34)", "Phone: Google Pixel 8"), details)
    }

    @Test fun bodyHasTheTemplateThenTheDetailsAndNothingElse() {
        val body = ProblemReport.body(details)
        assertTrue(body.startsWith("What happened:"))
        assertTrue(body.contains("What I expected:"))
        assertTrue(body.contains("How to repeat it:"))
        val afterLine = body.substringAfter("---\n").lines().drop(1)
        assertEquals(details, afterLine)
        assertFalse(body.contains(ProblemReport.CrashHeader))
        listOf("commit", "package", "installed", "usage").forEach { assertFalse(it, body.contains(it, ignoreCase = true)) }
    }

    @Test fun phoneKeepsAModelThatAlreadyNamesItsMaker() {
        assertEquals("Samsung SM-S918B", ProblemReport.phone("samsung", "Samsung SM-S918B"))
        assertEquals("Google Pixel 8", ProblemReport.phone("Google", "Pixel 8"))
        assertEquals("Pixel 8", ProblemReport.phone("", "Pixel 8"))
        assertEquals("unknown", ProblemReport.phone("", ""))
    }

    @Test fun anAwkwardValueCannotAddALine() {
        val lines = ProblemReport.details("1.0\nExtra: x", 1, "14\n", 34, "A\r\nB", "C")
        assertEquals(4, lines.size)
        assertTrue(lines.none { it.contains('\n') || it.contains('\r') })
    }

    @Test fun urlIsTheIssuesPageWithEncodedTitleAndBody() {
        val title = ProblemReport.title("1.6.0")
        val body = ProblemReport.body(details)
        val url = ProblemReport.issueUrl(title, body)
        assertTrue(url.startsWith("https://github.com/masterdroid-arch/Parc-Launcher/issues/new?title="))
        val query = url.substringAfter("?")
        val parts = query.split("&")
        assertEquals(2, parts.size)
        assertEquals(title, URLDecoder.decode(parts[0].removePrefix("title="), "UTF-8"))
        assertEquals(body, URLDecoder.decode(parts[1].removePrefix("body="), "UTF-8"))
        assertFalse("spaces are %20", query.contains(' ') || query.contains('+'))
        assertFalse(query.contains('\n'))
        assertTrue(query.contains("Problem%20with%20Parc%201.6.0"))
    }

    @Test fun aLongBodyIsCutWithAVisibleNote() {
        val long = ProblemReport.body(details) + "\n" + "x".repeat(20_000)
        val url = ProblemReport.issueUrl("t", long)
        val body = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
        assertTrue(url.substringAfter("&body=").length <= ProblemReport.MaxEncodedBody)
        assertTrue(body.endsWith(ProblemReport.CutNote))
        assertTrue(body.startsWith("What happened:"))
    }

    @Test fun aShortBodyIsNeverCut() {
        val body = ProblemReport.body(details)
        assertFalse(URLDecoder.decode(ProblemReport.issueUrl("t", body).substringAfter("&body="), "UTF-8").contains(ProblemReport.CutNote))
    }

    @Test fun crashNoteIsShortAndEmptyWithoutATrace() {
        assertEquals("", ProblemReport.crashNote(null))
        assertEquals("", ProblemReport.crashNote("   "))
        val trace = (1..400).joinToString("\n") { "  at com.example.Frame$it(File.kt:$it)" }
        val note = ProblemReport.crashNote(trace)
        assertTrue(note.length <= ProblemReport.MaxCrashChars)
        assertTrue(note.lines().all { it.startsWith("  at ") })
        assertNotNull(note.lines().firstOrNull())
    }

    @Test fun crashNoteIsOffByDefaultAndTogglesCleanly() {
        val plain = ProblemReport.body(details)
        val note = "java.lang.IllegalStateException: boom"
        assertFalse(plain.contains(note))
        val on = ProblemReport.withCrashNote(plain, note, true)
        assertTrue(on.contains(ProblemReport.CrashHeader) && on.contains(note))
        assertEquals("switching on twice adds it once", on, ProblemReport.withCrashNote(on, note, true))
        assertEquals(plain, ProblemReport.withCrashNote(on, note, false))
        val edited = on.replace(note, "my own words")
        assertEquals(plain, ProblemReport.withCrashNote(edited, note, false))
        assertEquals(plain, ProblemReport.withCrashNote(plain, "", true))
    }

    @Test fun theSettingsRowIsSearchableByTheCardTerms() {
        listOf("bug", "report", "problem", "issue", "feedback").forEach { word ->
            assertTrue(word, SettingsRegistry.search(word).any { it.id == "ReportProblem" })
        }
        assertEquals(SettingsPage.ReportProblem, SettingsRegistry.byId.getValue("ReportProblem").opens)
    }

    @Test fun noPublicEmailIsOffered() {
        // The README and PRIVACY name only the GitHub issues page as contact, so there is no "Email instead" route.
        val source = java.io.File("src/main/kotlin/com/brightblu/parc/ReportProblemPage.kt").takeIf { it.exists() }
            ?: java.io.File("app/src/main/kotlin/com/brightblu/parc/ReportProblemPage.kt")
        assertFalse(source.readText().contains("mailto:"))
    }
}
