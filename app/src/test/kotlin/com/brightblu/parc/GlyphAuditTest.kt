package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T221: guard the touched text surfaces, including dynamic Focus glyph interpolation. */
class GlyphAuditTest {
    private val knownAbsent = "◐▣◉↟▥⌕↥◇☾○●□△☀☆♡✿≡✎⤢✓☐☑"
    private val root = sequenceOf(File("."), File(".."))
        .first { File(it, "app/src/main/kotlin/com/brightblu/parc/ParcSettings.kt").isFile }
    private val touched = listOf(
        "app/src/main/kotlin/com/brightblu/parc/ParcSettings.kt",
        "app/src/main/kotlin/com/brightblu/parc/SettingsRegistry.kt",
        "app/src/main/kotlin/com/brightblu/parc/ReleaseNotesUi.kt",
        "app/src/main/kotlin/com/brightblu/parc/CommsPage.kt",
        "app/src/main/kotlin/com/brightblu/parc/HubPages.kt",
        "app/src/main/kotlin/com/brightblu/parc/FocusSettingsPage.kt",
        "app/src/main/kotlin/com/brightblu/parc/FocusModes.kt",
        "app/src/main/kotlin/com/brightblu/parc/FocusChip.kt",
        "app/src/personalWeather/kotlin/com/brightblu/parc/TranslateSearchMode.kt",
        "feature/settings/src/main/kotlin/com/brightblu/parc/feature/settings/ui/SettingsControls.kt",
    )

    // Scan string literals rather than comments. FocusGlyphs/FocusPolish are data/drawing mappings,
    // deliberately excluded: their persisted symbols must stay unchanged.
    private fun violations(source: String): List<String> {
        val literal = Regex("\"(?:\\\\.|[^\"\\\\])*\"")
        val unicodeEscape = Regex("\\\\u([0-9a-fA-F]{4})")
        return literal.findAll(source).map { it.value }.filter { raw ->
            val decoded = unicodeEscape.replace(raw) { it.groupValues[1].toInt(16).toChar().toString() }
            decoded.any { it in knownAbsent } || decoded.contains(".glyph}")
        }.toList()
    }

    @Test fun touchedScreensRenderNoFallbackGlyph() {
        for (relative in touched) {
            val file = File(root, relative)
            assertTrue("Missing audit source: $relative", file.isFile)
            assertEquals(relative, emptyList<String>(), violations(file.readText()))
        }
    }

    @Test fun auditRejectsTheOldRootAndFocusRendering() {
        assertEquals(1, violations("BasicText(\"◐\")").size)
        assertEquals(1, violations("BasicText(\"${'$'}{mode.glyph} ${'$'}{mode.name}\")").size)
        assertEquals(1, violations("BasicText(\"\\u261e ☾\")").size)
        assertEquals(1, violations("BasicText(\"\\u263e\")").size)
        assertEquals(emptyList<String>(), violations("BasicText(\"› Back\")"))
    }
}
