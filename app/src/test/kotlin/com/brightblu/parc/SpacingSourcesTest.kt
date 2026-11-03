package com.brightblu.parc

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T166: no screen carries its own padding numbers. Every `padding(...)`, `PaddingValues(...)` and `contentPadding` in
 * the product sources of the screens (app, feature:settings, feature:search) takes a named token from Metrics or a value
 * from ParcSpacing: no `16.dp`, no raw grid unit, no `token.dp * 3`.
 */
class SpacingSourcesTest {
    private fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return requireNotNull(dir) { "settings.gradle.kts not found" }
    }

    private val screenSources = listOf("app/src/main/kotlin", "app/src/personalWeather/kotlin", "app/src/noWeather/kotlin",
        "feature/settings/src/main/kotlin", "feature/search/src/main/kotlin")

    /** A number with `.dp`, the bare grid unit, or a token scaled by an unnamed factor. */
    private val forbidden = Regex("""[0-9]+(\.[0-9]+)?f?\s*\.dp|\bgridDp\b|\.dp\s*[*/]""")

    /** The text inside the parentheses of every padding call, with nesting. */
    internal fun paddingArguments(text: String): List<Pair<Int, String>> {
        val found = ArrayList<Pair<Int, String>>()
        for (m in Regex("""(padding|PaddingValues)\s*\(|contentPadding\s*=""").findAll(text)) {
            var i = m.range.last + 1
            if (m.value.endsWith("=")) {
                var depth = 0
                val start = i
                while (i < text.length && !(depth == 0 && text[i] in ",\n)")) {
                    if (text[i] == '(' || text[i] == '[') depth++
                    if (text[i] == ')' || text[i] == ']') depth--
                    i++
                }
                found += m.range.first to text.substring(start, i)
            } else {
                var depth = 1
                val start = i
                while (i < text.length && depth > 0) {
                    if (text[i] == '(') depth++ else if (text[i] == ')') depth--
                    i++
                }
                found += m.range.first to text.substring(start, i - 1)
            }
        }
        return found
    }

    @Test fun noScreenHasAPaddingLiteral() {
        val offenders = ArrayList<String>()
        var calls = 0
        screenSources.forEach { rel ->
            File(root(), rel).walkTopDown().filter { it.isFile && it.extension == "kt" }.sorted().forEach { file ->
                val text = file.readText()
                paddingArguments(text).forEach { (position, arguments) ->
                    calls++
                    if (forbidden.containsMatchIn(arguments)) {
                        val line = text.substring(0, position).count { it == '\n' } + 1
                        offenders += "${file.relativeTo(root())}:$line: ${arguments.trim().take(90)}"
                    }
                }
            }
        }
        assertTrue("padding calls scanned: $calls", calls > 100)
        assertTrue("paddings that do not use a token:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test fun theScannerSeesALiteralAndAGridMultiple() {
        val sample = "Modifier.padding(horizontal = 16.dp).padding(top = Metrics.gridDp.dp * 2).padding(Metrics.cardInsetDp.dp)"
        val hits = paddingArguments(sample).count { forbidden.containsMatchIn(it.second) }
        assertTrue(hits == 2)
    }

    @Test fun theBlocksAreUsedByTheScreens() {
        val usage = mapOf(
            "ParcPage(" to 20, "ParcSettingsRow(" to 2, // T175: the Feel page with its own rows is now generated from the registry "ParcCardContent" to 4, "ParcSectionHeader(" to 1,
            "parcSettingsPadding()" to 8, "parcRowPadding()" to 10, "parcMinTouch()" to 4)
        val text = screenSources.flatMap { File(root(), it).walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }
            .joinToString("\n") { it.readText() }
        usage.forEach { (name, atLeast) ->
            assertTrue("$name used ${Regex(Regex.escape(name)).findAll(text).count()} times, wanted $atLeast",
                Regex(Regex.escape(name)).findAll(text).count() >= atLeast)
        }
    }
}
