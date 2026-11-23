package com.brightblu.parc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T401: the family update tests must use fixed fake versions. The release script rewrites the current
 * version number in source files, so a test that spells out the real version breaks on the next bump.
 * This fails when any test file under a familyupdate folder contains the current PARC_VERSION_NAME.
 */
class T401FamilyUpdateFixturesTest {
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "gradle.properties").isFile && File(dir, "app/src").isDirectory) return dir
            dir = dir.parentFile
        }
        error("repository root not found from ${File("").absoluteFile}")
    }

    private fun currentVersion(root: File): String =
        File(root, "gradle.properties").readLines().first { it.startsWith("PARC_VERSION_NAME=") }
            .substringAfter('=').trim()

    /** True when [version] appears in [text] as its own number, not as part of a longer one such as 11.6.00. */
    private fun mentions(text: String, version: String): Boolean {
        var from = 0
        while (true) {
            val at = text.indexOf(version, from)
            if (at < 0) return false
            val before = text.getOrNull(at - 1)
            val after = text.getOrNull(at + version.length)
            val partOfLonger = (before != null && (before.isDigit() || before == '.')) ||
                (after != null && (after.isDigit() || (after == '.' && text.getOrNull(at + version.length + 1)?.isDigit() == true)))
            if (!partOfLonger) return true
            from = at + 1
        }
    }

    @Test fun theCheckerSeesTheVersionAndOnlyAWholeNumber() {
        assertTrue(mentions("\"1.6.0\"", "1.6.0"))
        assertTrue(mentions("Parc-1.6.0.apk", "1.6.0"))
        assertTrue(!mentions("11.6.0", "1.6.0"))
        assertTrue(!mentions("1.6.00", "1.6.0"))
        assertTrue(!mentions("1.6.0.1", "1.6.0"))
        assertTrue(!mentions("1.0.0", "1.6.0"))
    }

    @Test fun noFamilyUpdateTestSpellsOutTheCurrentVersion() {
        val root = repoRoot()
        val version = currentVersion(root)
        assertTrue("PARC_VERSION_NAME looks odd: '$version'", Regex("""\d+\.\d+\.\d+""").matches(version))
        val offenders = File(root, "app/src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "familyupdate" in it.path && "/test" in it.path }
            .filter { mentions(it.readText(), version) }
            .map { it.relativeTo(root).path }
            .toList()
        assertTrue("These tests use the current version $version; use fixed fake versions (installed 1.0.0): $offenders",
            offenders.isEmpty())
    }
}
