package com.brightblu.parc.familyupdate

import com.brightblu.parc.FakeSharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset

/** T396: the engine end to end with a fake network: found, up to date, offline, bad checksum, cancel and the rest. */
class T396UpdateEngineTest {
    private val apkBytes = "pretend this is an apk".toByteArray()
    private val goodHash = UpdateChecksum.hex(apkBytes)

    private class FakeTransport(val apk: ByteArray) : UpdateTransport {
        var latest: TransportResult<String> = TransportResult.Failed(UpdateProblem.Offline)
        var checksumText: String = ""
        var downloadFails: UpdateProblem? = null
        var onProgress: () -> Unit = {}
        var gets = 0
        var downloads = 0
        override fun getText(address: String, maxBytes: Int): TransportResult<String> {
            gets++
            return if (address.endsWith(".sha256")) TransportResult.Ok(checksumText) else latest
        }
        override fun download(address: String, target: File, maxBytes: Long, isCancelled: () -> Boolean,
            progress: (Long, Long) -> Unit): TransportResult<File> {
            downloads++
            downloadFails?.let { return TransportResult.Failed(it) }
            target.parentFile?.mkdirs()
            target.writeBytes(apk.copyOf(apk.size / 2))
            progress(apk.size / 2L, apk.size.toLong())
            onProgress()
            if (isCancelled()) return TransportResult.Cancelled
            target.writeBytes(apk)
            progress(apk.size.toLong(), apk.size.toLong())
            return TransportResult.Ok(target)
        }
    }

    private lateinit var dir: File
    private lateinit var transport: FakeTransport
    private lateinit var prefs: UpdatePrefs
    private var now = 1_000_000_000_000L
    private var canInstall = true
    private var askedPermission = 0
    private var handedOff: File? = null
    private var handOffWorks = true
    private val scope = CoroutineScope(Dispatchers.Default)

    @Before fun setUp() {
        dir = Files.createTempDirectory("t396").toFile()
        transport = FakeTransport(apkBytes)
        prefs = UpdatePrefs(FakeSharedPreferences())
    }

    @After fun tearDown() { dir.deleteRecursively() }

    /** Fixed fake versions: the installed app is always 1.0.0, never the real one. */
    private fun engine(installed: String = "1.0.0") = UpdateEngine(prefs, transport, installed, dir, { "https://api.github.com/x" },
        handOff = { handedOff = it; handOffWorks }, canInstall = { canInstall }, askInstallPermission = { askedPermission++ },
        clock = { now }, zone = { ZoneOffset.UTC }, scope = scope)

    private fun release(tag: String, withFiles: Boolean = true): String {
        val v = tag.removePrefix("v")
        val assets = if (withFiles) """[{"name":"Parc-$v.apk","size":22,"browser_download_url":"https://github.com/o/r/Parc-$v.apk"},
            {"name":"Parc-$v.apk.sha256","size":64,"browser_download_url":"https://github.com/o/r/Parc-$v.apk.sha256"}]""" else "[]"
        return """{"tag_name":"$tag","draft":false,"prerelease":false,"assets":$assets}"""
    }

    private suspend fun waitFor(what: String, test: () -> Boolean) = withTimeout(5_000) { while (!test()) delay(5); what }

    @Test fun anUpdateIsFound() = runBlocking {
        transport.latest = TransportResult.Ok(release("v1.0.1"))
        val e = engine(); e.check()
        assertEquals(UpdateState.Available("1.0.1"), e.ui.value.state)
        assertEquals("1.0.1", prefs.available)
        assertEquals(now, prefs.lastChecked)
        assertEquals("Update available: 1.0.1", e.ui.value.checkMessage)
        assertFalse(e.ui.value.checking)
        assertFalse(e.ui.value.hubHidden)
    }

    @Test fun upToDateShowsNothingAndSaysSo() = runBlocking {
        transport.latest = TransportResult.Ok(release("v1.0.0"))
        val e = engine(); e.check()
        assertEquals(UpdateState.Idle, e.ui.value.state)
        assertNull(prefs.available)
        assertEquals("You have the latest version, 1.0.0.", e.ui.value.checkMessage)
        assertEquals(now, prefs.lastChecked)
    }

    @Test fun anOlderReleaseIsNotAnUpdate() = runBlocking {
        transport.latest = TransportResult.Ok(release("v0.9.0"))
        val e = engine(); e.check()
        assertEquals(UpdateState.Idle, e.ui.value.state)
    }

    @Test fun offlineFailsQuietlyAndKeepsTheLastGoodResult() = runBlocking {
        prefs.available = "1.0.1"; prefs.lastChecked = 5L
        transport.latest = TransportResult.Failed(UpdateProblem.Offline)
        val e = engine(); e.check()
        assertEquals(UpdateState.Available("1.0.1"), e.ui.value.state)
        assertEquals(UpdateProblem.Offline.message, e.ui.value.checkMessage)
        assertEquals("a failed check is not a check", 5L, prefs.lastChecked)
        assertEquals("but it is an attempt", now, prefs.lastAttempt)
        assertFalse(e.ui.value.checking)
    }

    @Test fun aReleaseWithoutItsFilesOffersNothing() = runBlocking {
        transport.latest = TransportResult.Ok(release("v1.0.1", withFiles = false))
        val e = engine(); e.check()
        assertEquals(UpdateState.Idle, e.ui.value.state)
        assertNull(prefs.available)
        assertEquals(UpdateProblem.NoFiles.message, e.ui.value.checkMessage)
    }

    @Test fun garbageFromTheServerIsAPlainError() = runBlocking {
        transport.latest = TransportResult.Ok("<html>rate limited</html>")
        val e = engine(); e.check()
        assertEquals(UpdateProblem.BadAnswer.message, e.ui.value.checkMessage)
        assertEquals(UpdateState.Idle, e.ui.value.state)
    }

    @Test fun theBackgroundCheckObeysTheSwitchAndTheDailyThrottle() = runBlocking {
        transport.latest = TransportResult.Ok(release("v1.0.1"))
        val e = engine()
        e.setAutoCheck(false)
        e.backgroundCheck()
        assertEquals("switch off: no request", 0, transport.gets)
        e.setAutoCheck(true)
        e.backgroundCheck()
        assertEquals(1, transport.gets)
        now += 60_000
        e.backgroundCheck()
        assertEquals("a minute later: still one", 1, transport.gets)
        now += 24L * 60 * 60 * 1000
        e.backgroundCheck()
        assertEquals("a day later: a second request", 2, transport.gets)
    }

    @Test fun theSwitchDefaultsToOnAndIsSaved() {
        assertTrue(prefs.autoCheck)
        val e = engine(); e.setAutoCheck(false)
        assertFalse(prefs.autoCheck)
        assertFalse(e.ui.value.autoCheck)
        assertFalse(engine().ui.value.autoCheck)
    }

    @Test fun anAvailableVersionSurvivesARestartUntilTheAppIsUpdated() {
        prefs.available = "1.0.1"
        assertEquals(UpdateState.Available("1.0.1"), engine("1.0.0").ui.value.state)
        assertEquals(UpdateState.Idle, engine("1.0.1").ui.value.state)
        assertNull("cleared once installed", prefs.available)
    }

    @Test fun laterHidesTheHubNoticeTodayAndReturnsTomorrowOrForANewerVersion() = runBlocking {
        transport.latest = TransportResult.Ok(release("v1.0.1"))
        val e = engine(); e.check(); e.later()
        assertTrue(e.ui.value.hubHidden)
        assertEquals("settings still has it", UpdateState.Available("1.0.1"), e.ui.value.state)
        assertTrue("same day after a restart", engine().ui.value.hubHidden)
        now += 24L * 60 * 60 * 1000
        assertFalse("next day", engine().ui.value.hubHidden)
        now -= 24L * 60 * 60 * 1000
        transport.latest = TransportResult.Ok(release("v1.1.0"))
        e.check()
        assertEquals(UpdateState.Available("1.1.0"), e.ui.value.state)
        assertFalse("a newer version shows again", e.ui.value.hubHidden)
    }

    @Test fun theUpdateDownloadsVerifiesAndHandsOffToTheInstaller() = runBlocking {
        transport.latest = TransportResult.Ok(release("v1.0.1"))
        transport.checksumText = "$goodHash  Parc-1.0.1.apk\n"
        val e = engine(); e.check()
        e.downloadAndInstall()
        assertEquals(UpdateState.Available("1.0.1", installerOpened = true), e.ui.value.state)
        val file = handedOff!!
        assertEquals("Parc-1.0.1.apk", file.name)
        assertEquals(goodHash, UpdateChecksum.hex(file.readBytes()))
        assertTrue(File(dir, "updates").listFiles()!!.none { it.name.endsWith(".part") })
        assertEquals(1, transport.downloads)
    }

    @Test fun aBadChecksumIsRefusedDeletedAndNeverHandedOff() = runBlocking {
        transport.latest = TransportResult.Ok(release("v1.0.1"))
        transport.checksumText = "${"0".repeat(64)}  Parc-1.0.1.apk"
        val e = engine(); e.check()
        e.downloadAndInstall()
        assertEquals(UpdateState.Failed("1.0.1", UpdateProblem.BadChecksum), e.ui.value.state)
        assertNull(handedOff)
        assertTrue(File(dir, "updates").listFiles().orEmpty().isEmpty())
    }

    @Test fun anUnclearChecksumFileStopsBeforeTheInstaller() = runBlocking {
        transport.latest = TransportResult.Ok(release("v1.0.1"))
        transport.checksumText = "no hash here"
        val e = engine(); e.check()
        e.downloadAndInstall()
        assertEquals(UpdateState.Failed("1.0.1", UpdateProblem.BadAnswer), e.ui.value.state)
        assertNull(handedOff)
        assertEquals("no APK was even downloaded", 0, transport.downloads)
    }

    @Test fun aFailedDownloadCanBeRetried() = runBlocking {
        transport.latest = TransportResult.Ok(release("v1.0.1"))
        transport.checksumText = goodHash
        val e = engine(); e.check()
        transport.downloadFails = UpdateProblem.Timeout
        e.downloadAndInstall()
        assertEquals(UpdateState.Failed("1.0.1", UpdateProblem.Timeout), e.ui.value.state)
        transport.downloadFails = null
        e.downloadAndInstall()
        assertEquals(UpdateState.Available("1.0.1", installerOpened = true), e.ui.value.state)
        assertTrue(handedOff != null)
    }

    @Test fun cancelStopsTheDownloadAndInstallsNothing() = runBlocking {
        transport.latest = TransportResult.Ok(release("v1.0.1"))
        transport.checksumText = goodHash
        val e = engine(); e.check()
        lateinit var job: Job
        transport.onProgress = { job.cancel() }
        job = launch(Dispatchers.Default) { e.downloadAndInstall() }
        job.join()
        assertEquals(UpdateState.Available("1.0.1"), e.ui.value.state)
        assertNull(handedOff)
        assertTrue(File(dir, "updates").listFiles().orEmpty().isEmpty())
    }

    @Test fun cancelThroughTheEngineButtonWorks() = runBlocking {
        transport.latest = TransportResult.Ok(release("v1.0.1"))
        transport.checksumText = goodHash
        val e = engine(); e.check()
        transport.onProgress = { e.cancel() }
        e.update()
        waitFor("settled") { e.ui.value.state !is UpdateState.Downloading && transport.downloads == 1 }
        assertEquals(UpdateState.Available("1.0.1"), e.ui.value.state)
        assertNull(handedOff)
    }

    @Test fun withoutTheInstallPermissionAndroidsScreenOpensAndNothingIsDownloaded() = runBlocking {
        transport.latest = TransportResult.Ok(release("v1.0.1"))
        transport.checksumText = goodHash
        canInstall = false
        val e = engine(); e.check()
        e.downloadAndInstall()
        assertEquals(1, askedPermission)
        assertEquals(UpdateState.Failed("1.0.1", UpdateProblem.NeedInstallPermission), e.ui.value.state)
        assertEquals(0, transport.downloads)
        canInstall = true
        e.downloadAndInstall()
        assertEquals(UpdateState.Available("1.0.1", installerOpened = true), e.ui.value.state)
    }

    @Test fun anInstallerThatWillNotOpenIsAPlainError() = runBlocking {
        transport.latest = TransportResult.Ok(release("v1.0.1"))
        transport.checksumText = goodHash
        handOffWorks = false
        val e = engine(); e.check()
        e.downloadAndInstall()
        assertEquals(UpdateState.Failed("1.0.1", UpdateProblem.CannotOpenInstaller), e.ui.value.state)
    }

    @Test fun ifTheReleaseIsGoneByTheTapNothingIsInstalled() = runBlocking {
        transport.latest = TransportResult.Ok(release("v1.0.1"))
        transport.checksumText = goodHash
        val e = engine(); e.check()
        transport.latest = TransportResult.Ok(release("v1.0.1", withFiles = false))
        e.downloadAndInstall()
        assertEquals(UpdateState.Failed("1.0.1", UpdateProblem.NoFiles), e.ui.value.state)
        assertNull(handedOff)
    }
}
