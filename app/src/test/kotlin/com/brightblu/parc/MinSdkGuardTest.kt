package com.brightblu.parc

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parc must install on Android 13 (API 33). T95 once raised every module to 34 by accident, which
 * made Android 13 phones refuse the APK with "problem parsing the package". This guard fails the
 * build if any module asks for more than 33, or if the app manifest overrides it.
 */
class MinSdkGuardTest {
    private fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return requireNotNull(dir) { "settings.gradle.kts not found" }
    }

    @Test fun everyModuleSupportsAndroid13() {
        val modules = listOf("app", "core/data", "core/model", "feature/ring", "feature/search", "feature/settings")
        for (module in modules) {
            val text = File(root(), "$module/build.gradle.kts").readText()
            val values = Regex("""minSdk\s*=\s*(\d+)""").findAll(text).map { it.groupValues[1].toInt() }.toList()
            assertTrue("$module declares no minSdk", values.isNotEmpty())
            assertTrue("$module minSdk ${values.max()} is above 33", values.all { it <= 33 })
        }
    }

    @Test fun appMinSdkIsExactly33() {
        val text = File(root(), "app/build.gradle.kts").readText()
        assertEquals(33, Regex("""minSdk\s*=\s*(\d+)""").find(text)!!.groupValues[1].toInt())
    }

    @Test fun manifestDoesNotRaiseMinSdk() {
        val manifest = File(root(), "app/src/main/AndroidManifest.xml").readText()
        assertTrue("manifest must not set minSdkVersion", !manifest.contains("minSdkVersion"))
    }
}
