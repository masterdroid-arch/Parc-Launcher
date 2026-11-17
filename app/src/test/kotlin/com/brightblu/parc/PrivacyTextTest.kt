package com.brightblu.parc

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T216: the public documents and the in-app copy tell the same truth about the Messages history.
 *
 * The history is stored on the phone by default for exactly WhatsApp, Telegram and Instagram, text
 * and links only, never in a backup, and only an explicit action removes it. Any remaining
 * "memory only" wording must be scoped to the live inbox or preview.
 */
class PrivacyTextTest {

    private fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return requireNotNull(dir) { "settings.gradle.kts not found" }
    }

    private fun source(path: String): String = File(root(), path).readText()

    /** Newlines are layout, not wording: compare the prose as one line. */
    private fun flat(text: String): String = text.replace(Regex("\\s+"), " ")

    private val requiredPublicPhrases = listOf(
        "stored on this phone",
        "WhatsApp, Telegram and Instagram",
        "not in backups",
        "clear",
    )

    @Test
    fun `the public documents state the history rules`() {
        listOf("PRIVACY.md", "README.md").forEach { name ->
            val text = flat(source(name))
            requiredPublicPhrases.forEach { phrase ->
                assertTrue("$name must say '$phrase'", text.contains(phrase, ignoreCase = true))
            }
            assertTrue("$name must say images are not stored", text.contains("no images", ignoreCase = true))
            assertTrue(
                "$name must say deletion is manual",
                text.contains("no automatic deletion", ignoreCase = true) ||
                    text.contains("nothing is deleted automatically", ignoreCase = true),
            )
            assertTrue("$name must say History stays readable with access off", text.contains("History", ignoreCase = true))
            assertTrue("$name must say replies use the messaging app", text.contains("messaging app", ignoreCase = true))
        }
        val privacy = flat(source("PRIVACY.md"))
        assertTrue(
            "PRIVACY.md must say replies use the app's notification action",
            privacy.contains("notification action", ignoreCase = true),
        )
    }

    @Test
    fun `no public text makes an unqualified memory-only claim`() {
        listOf("PRIVACY.md", "README.md").forEach { name ->
            val lines = source(name).lines()
            val memoryOnly = Regex("(?i)memory[- ]only")
            lines.forEachIndexed { index, line ->
                if (!memoryOnly.containsMatchIn(line)) return@forEachIndexed
                // A wrapped sentence keeps its scope on the line above; a table row stands alone.
                val context = if (line.trimStart().startsWith("|")) {
                    line.lowercase()
                } else {
                    (lines.getOrNull(index - 1).orEmpty() + " " + line).lowercase()
                }
                val aboutMessages = listOf("message", "notification", "comms", "history").any { it in context }
                if (!aboutMessages) return@forEachIndexed
                assertTrue(
                    "$name has a memory-only claim that is not scoped to the live inbox or preview: " +
                        flat(context),
                    listOf("live", "preview", "peek", "long-press").any { it in context },
                )
            }
        }
    }

    @Test
    fun `permissions, onboarding and the in-app Messages note name the history`() {
        val permission = source("feature/settings/src/main/kotlin/com/brightblu/parc/feature/settings/permissions/PermissionsModel.kt")
        val onboarding = source("feature/settings/src/main/kotlin/com/brightblu/parc/feature/settings/onboarding/OnboardingScript.kt")
        val messages = source("app/src/main/kotlin/com/brightblu/parc/CommsPage.kt")

        listOf("permissions" to permission, "onboarding" to onboarding, "Messages note" to messages).forEach { (what, text) ->
            assertTrue("$what must say where messages are stored", text.contains("stored on this phone"))
            assertTrue("$what must name the three apps", text.contains("WhatsApp, Telegram and Instagram"))
        }
        assertTrue("the Messages note must say what is stored", messages.contains("text and links only, no images"))
        assertTrue("the Messages note must say how to clear", messages.contains("Clear all messages"))
    }

    @Test
    fun `BackupSpec keeps history out of every backup store`() {
        val backup = flat(source("core/data/src/main/kotlin/com/brightblu/parc/core/data/backup/BackupSpec.kt"))
        assertTrue(backup.contains("comms-history.db"))
        assertTrue(Regex("never in a\\s+\\*?\\s*backup", RegexOption.IGNORE_CASE).containsMatchIn(backup))
    }

    @Test
    fun `README points to PRIVACY for the full list`() {
        val readme = flat(source("README.md"))
        assertTrue(readme.contains("PRIVACY.md"))
        assertTrue(
            "README must point to PRIVACY.md for the full list",
            Regex("PRIVACY\\.md.*full list", RegexOption.IGNORE_CASE).containsMatchIn(readme),
        )
    }
}
