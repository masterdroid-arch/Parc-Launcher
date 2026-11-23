package com.brightblu.parc.familyupdate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.LocalDateTime

/** T396: the pure rules of the family update checker. */
class T396UpdateLogicTest {
    private fun v(text: String) = requireNotNull(UpdateVersion.parse(text))

    @Test fun versionsCompareNumberByNumber() {
        // Fixed fake versions only: the installed app is always 1.0.0 here.
        assertTrue(v("1.0.9") < v("1.0.10"))
        assertTrue(v("1.0.10") < v("1.10.0"))
        assertTrue(v("1.10.0") < v("2.0.0"))
        assertTrue(v("1.0.0") == v("1.0.0"))
        assertTrue(UpdateVersion.isNewer("v1.0.1", "1.0.0"))
        assertTrue(UpdateVersion.isNewer("v1.1.0", "1.0.0"))
        assertTrue(UpdateVersion.isNewer("v2.0.0", "1.0.0"))
        assertFalse("equal is not newer", UpdateVersion.isNewer("v1.0.0", "1.0.0"))
        assertFalse("lower is not newer", UpdateVersion.isNewer("v0.9.0", "1.0.0"))
    }

    @Test fun tagsParseStrictly() {
        assertEquals(UpdateVersion(1, 0, 1), UpdateVersion.parse("v1.0.1"))
        assertEquals(UpdateVersion(1, 0, 1), UpdateVersion.parse("1.0.1"))
        assertEquals("1.0.1", UpdateVersion.parse(" v1.0.1 ").toString())
        listOf("", "v", "v1.0", "v1.0.1.1", "v1.0.x", "v1.0.1-beta", "release-1.0.1", "V1.0.1", "v-1.0.1", "v1.0.1 0")
            .forEach { assertNull("'$it' must not parse", UpdateVersion.parse(it)) }
        assertNull(UpdateVersion.parse(null))
    }

    @Test fun anUnreadableTagOrInstalledVersionNeverOffersAnUpdate() {
        assertFalse(UpdateVersion.isNewer("nightly", "1.0.0"))
        assertFalse(UpdateVersion.isNewer("v1.0.1", "dev"))
        assertFalse(UpdateVersion.isNewer(null, "1.0.0"))
    }

    private fun releaseJson(tag: String, vararg assets: String, draft: Boolean = false, prerelease: Boolean = false): String {
        val list = assets.joinToString(",") { """{"name":"$it","size":1234,"browser_download_url":"https://github.com/o/r/releases/download/$tag/$it"}""" }
        return """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"name":"x","assets":[$list],"extra":{"a":1}}"""
    }

    @Test fun aReleaseParsesAndTheAssetsAreChosenByExactName() {
        val release = requireNotNull(UpdateReleaseJson.parse(releaseJson("v1.0.1", "Parc-1.0.1-play.apk", "Parc-1.0.1.apk", "Parc-1.0.1.apk.sha256", "notes.txt")))
        assertEquals("v1.0.1", release.tag)
        val files = requireNotNull(UpdateAssets.choose(release))
        assertEquals("Parc-1.0.1.apk", files.apk.name)
        assertEquals("Parc-1.0.1.apk.sha256", files.checksum.name)
        assertTrue(files.apk.url.endsWith("/v1.0.1/Parc-1.0.1.apk"))
        assertEquals(UpdateVersion(1, 0, 1), files.version)
    }

    @Test fun noApkOrNoChecksumMeansNothingIsOffered() {
        assertNull(UpdateAssets.choose(requireNotNull(UpdateReleaseJson.parse(releaseJson("v1.0.1", "Parc-1.0.1.apk")))))
        assertNull(UpdateAssets.choose(requireNotNull(UpdateReleaseJson.parse(releaseJson("v1.0.1", "Parc-1.0.1.apk.sha256")))))
        // Files for another version do not count.
        assertNull(UpdateAssets.choose(requireNotNull(UpdateReleaseJson.parse(releaseJson("v1.0.1", "Parc-1.0.0.apk", "Parc-1.0.0.apk.sha256")))))
    }

    @Test fun draftsPrereleasesAndOddAnswersAreIgnored() {
        assertNull(UpdateReleaseJson.parse(releaseJson("v1.0.1", draft = true)))
        assertNull(UpdateReleaseJson.parse(releaseJson("v1.0.1", prerelease = true)))
        assertNull(UpdateReleaseJson.parse(releaseJson("nightly")))
        assertNull(UpdateReleaseJson.parse("not json"))
        assertNull(UpdateReleaseJson.parse("[]"))
        assertNull(UpdateReleaseJson.parse("""{"message":"Not Found"}"""))
        assertNotNull(UpdateReleaseJson.parse("""{"tag_name":"v1.0.1"}"""))
    }

    @Test fun theThrottleAllowsOneSuccessfulCheckPerDay() {
        val hour = 60L * 60 * 1000
        val now = 1_000_000_000_000L
        assertTrue("never checked", UpdateThrottle.due(now, 0, 0))
        assertFalse("checked 23 h ago", UpdateThrottle.due(now, now - 23 * hour, now - 23 * hour))
        assertTrue("checked 24 h ago", UpdateThrottle.due(now, now - 24 * hour, now - 24 * hour))
        assertFalse("checked a minute ago", UpdateThrottle.due(now, now - 60_000, now - 60_000))
    }

    @Test fun aFailedCheckMayBeTriedAgainAfterAnHourButASuccessHoldsForADay() {
        val hour = 60L * 60 * 1000
        val now = 1_000_000_000_000L
        val lastSuccess = now - 30 * hour
        assertFalse("failed 10 minutes ago", UpdateThrottle.due(now, lastSuccess, now - 10 * 60_000))
        assertTrue("failed 61 minutes ago", UpdateThrottle.due(now, lastSuccess, now - 61 * 60_000))
        assertFalse("success 2 h ago, failed since", UpdateThrottle.due(now, now - 2 * hour, now - 90 * 60_000))
    }

    @Test fun aClockSetBackNeverBlocksChecksForever() {
        val now = 1_000_000_000_000L
        assertTrue(UpdateThrottle.due(now, now + 5 * 24 * 60 * 60 * 1000L, now + 5 * 24 * 60 * 60 * 1000L))
    }

    @Test fun laterHidesTheNoticeUntilTomorrowOrANewerVersion() {
        assertTrue(UpdateNotice.visible("1.0.1", null, -1, 100))
        assertFalse(UpdateNotice.visible("1.0.1", "1.0.1", 100, 100))
        assertTrue("next day", UpdateNotice.visible("1.0.1", "1.0.1", 100, 101))
        assertTrue("newer version", UpdateNotice.visible("1.1.0", "1.0.1", 100, 100))
        assertFalse("nothing to show", UpdateNotice.visible(null, null, -1, 100))
    }

    @Test fun checksumsMatchTheKnownValue() {
        // SHA-256 of "abc".
        val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(abc, UpdateChecksum.hex("abc".toByteArray()))
        assertEquals(abc, UpdateChecksum.hex("abc".byteInputStream()))
        assertTrue(UpdateChecksum.matches(abc, abc.uppercase()))
        assertTrue(UpdateChecksum.matches("  $abc\n", abc))
        assertFalse(UpdateChecksum.matches(abc, abc.replaceRange(0, 1, "0")))
        assertFalse(UpdateChecksum.matches("", ""))
        assertFalse(UpdateChecksum.matches("abc", "abc"))
    }

    @Test fun theChecksumFileIsReadInEveryCommonShape() {
        val h1 = "a".repeat(64); val h2 = "b".repeat(64)
        assertEquals(h1, UpdateChecksum.expected(h1, "Parc-1.0.1.apk"))
        assertEquals(h1, UpdateChecksum.expected("$h1\n", "Parc-1.0.1.apk"))
        assertEquals(h1, UpdateChecksum.expected("$h1  Parc-1.0.1.apk\n", "Parc-1.0.1.apk"))
        assertEquals(h1, UpdateChecksum.expected("$h1 *Parc-1.0.1.apk", "Parc-1.0.1.apk"))
        assertEquals(h2, UpdateChecksum.expected("$h1  other.apk\n$h2  Parc-1.0.1.apk\n", "Parc-1.0.1.apk"))
        assertEquals(h1.lowercase(), UpdateChecksum.expected(h1.uppercase(), "Parc-1.0.1.apk"))
    }

    @Test fun anUnclearChecksumFileIsNeverTrusted() {
        val h1 = "a".repeat(64); val h2 = "b".repeat(64)
        assertNull(UpdateChecksum.expected("", "Parc-1.0.1.apk"))
        assertNull(UpdateChecksum.expected("not a hash", "Parc-1.0.1.apk"))
        assertNull(UpdateChecksum.expected("${"a".repeat(63)}", "Parc-1.0.1.apk"))
        assertNull("two hashes, none for this file", UpdateChecksum.expected("$h1  x.apk\n$h2  y.apk", "Parc-1.0.1.apk"))
        assertNull("one hash for another file", UpdateChecksum.expected("$h1  x.apk", "Parc-1.0.1.apk"))
    }

    @Test fun onlyHttpsGithubHostsAreAllowed() {
        assertTrue(UpdateHosts.allowed("https", "api.github.com"))
        assertTrue(UpdateHosts.allowed("https", "github.com"))
        assertTrue(UpdateHosts.allowed("https", "objects.githubusercontent.com"))
        assertTrue(UpdateHosts.allowed("https", "release-assets.githubusercontent.com"))
        assertFalse(UpdateHosts.allowed("http", "api.github.com"))
        assertFalse(UpdateHosts.allowed("https", "evil.com"))
        assertFalse(UpdateHosts.allowed("https", "github.com.evil.com"))
        assertFalse(UpdateHosts.allowed("https", "notgithub.com"))
        assertFalse(UpdateHosts.allowed("https", "evilgithubusercontent.com"))
    }

    @Test fun theLayoutStacksWhenTextIsEnlarged() {
        assertFalse(UpdateLayout.stacked(1.0f))
        assertFalse(UpdateLayout.stacked(1.15f))
        assertTrue(UpdateLayout.stacked(1.3f))
        assertTrue(UpdateLayout.stacked(2.0f))
    }

    @Test fun theWordsAreShortAndPlain() {
        assertEquals("Update available: 1.0.1", UpdateText.available("1.0.1"))
        assertEquals("Last checked: never", UpdateText.lastChecked(0, ZoneOffset.UTC))
        val at = LocalDateTime.of(2026, 11, 23, 14, 5).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("Last checked: 23 Nov, 14:05", UpdateText.lastChecked(at, ZoneId.of("UTC")))
        assertEquals("Downloading…", UpdateWords.percent(null))
        assertEquals("Downloading 42%", UpdateWords.percent(0.42f))
        assertEquals("Downloading 100%", UpdateWords.percent(1.7f))
        UpdateProblem.entries.forEach { assertTrue(it.message.length in 10..120) }
    }
}
