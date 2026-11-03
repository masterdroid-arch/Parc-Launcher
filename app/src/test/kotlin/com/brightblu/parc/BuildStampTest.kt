package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T179: the build stamp is filled by Gradle and has a fixed shape. */
class BuildStampTest {
    @Test fun gradleFillsEveryValue() {
        assertTrue(BuildConfig.BUILD_TIME, BuildStamp.TimeFormat.matches(BuildConfig.BUILD_TIME))
        assertTrue(BuildConfig.BUILD_STAMP, BuildStamp.StampFormat.matches(BuildConfig.BUILD_STAMP))
        assertTrue(BuildConfig.GIT_COMMIT, BuildStamp.CommitFormat.matches(BuildConfig.GIT_COMMIT))
        // The stamp is the build time without separators (yyMMddHHmm).
        assertEquals(BuildConfig.BUILD_TIME.filter(Char::isDigit).drop(2), BuildConfig.BUILD_STAMP)
    }

    @Test fun linesAndShortForm() {
        val lines = BuildStamp.lines("1.1.1", 10101, "2026-11-01 20:15", "abc1234")
        assertEquals(listOf("Version", "Version code", "Built", "Code commit"), lines.map { it.first })
        assertEquals(listOf("1.1.1", "10101", "2026-11-01 20:15", "abc1234"), lines.map { it.second })
        assertEquals("1.1.1 (10101) · 2026-11-01 20:15", BuildStamp.short("1.1.1", 10101, "2026-11-01 20:15"))
        assertTrue(BuildStamp.CommitFormat.matches("abc1234+changes"))
    }

    @Test fun versionCodeFollowsTheVersionName() {
        val (major, minor, patch) = BuildConfig.VERSION_NAME.split('.').map(String::toInt)
        assertEquals(major * 10000 + minor * 100 + patch, BuildConfig.VERSION_CODE)
    }
}
