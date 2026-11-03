package com.brightblu.parc

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T157: every touch-driven animation is a spring (DESIGN 2.6). In the product sources of `app` and `feature:ring` there is
 * no `tween(`, no `Easing`, no `CubicBezier` use and no `animate*AsState` without a spring spec. The time-driven motion
 * nobody touches (wake sweep, breathe, pulse) is hand-rolled on the frame clock and does not use these APIs, so nothing
 * is allowed here: the allow-list is empty.
 */
class TouchMotionSourcesTest {
    private fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return requireNotNull(dir) { "settings.gradle.kts not found" }
    }

    private val sources = listOf("app/src/main/kotlin", "app/src/personalWeather/kotlin", "app/src/noWeather/kotlin", "feature/ring/src/main/kotlin")
    private val banned = Regex("""\btween\s*\(|\bEasing\b|\bCubicBezier\b|\bFastOutSlowInEasing\b|\bLinearEasing\b""")
    private val animateAsState = Regex("""\banimate[A-Za-z]*AsState\s*\(""")

    /** The text of a call from its opening parenthesis to the matching one. */
    private fun callText(text: String, open: Int): String {
        var depth = 0; var i = open
        while (i < text.length) {
            if (text[i] == '(') depth++ else if (text[i] == ')') { depth--; if (depth == 0) break }
            i++
        }
        return text.substring(open, minOf(i + 1, text.length))
    }

    internal fun offenders(text: String, name: String): List<String> {
        val found = ArrayList<String>()
        for (m in banned.findAll(text)) found += "$name:${text.substring(0, m.range.first).count { it == '\n' } + 1}: ${m.value}"
        for (m in animateAsState.findAll(text)) {
            val call = callText(text, m.range.last)
            if (!call.contains("spring")) found += "$name:${text.substring(0, m.range.first).count { it == '\n' } + 1}: ${m.value} without a spring"
        }
        return found
    }

    @Test fun noTouchDrivenAnimationIsATween() {
        val bad = ArrayList<String>()
        var files = 0
        sources.forEach { rel ->
            File(root(), rel).walkTopDown().filter { it.isFile && it.extension == "kt" }.sorted().forEach { file ->
                files++
                bad += offenders(file.readText(), file.relativeTo(root()).path)
            }
        }
        assertTrue("files scanned: $files", files > 100)
        assertTrue("tweens left:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun theScannerSeesTheBannedCalls() {
        val sample = "animate(0f, 1f, animationSpec = tween(160)) {}\nval a = animateFloatAsState(1f)\nval b = animateFloatAsState(1f, spring(0.8f, 900f))\nEasing.x"
        assertTrue(offenders(sample, "x").size == 3)
    }
}
