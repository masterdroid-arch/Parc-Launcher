package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.core.data.backup.BackupFileStore
import com.brightblu.parc.feature.settings.backup.BackupOutcome
import com.brightblu.parc.feature.settings.backup.BackupFailure
import com.brightblu.parc.feature.settings.backup.LayoutBackupService
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T196: Translate mode rules, request building, parsing, flow timing and the key store. Fake transport, obviously fake key. */
@OptIn(ExperimentalCoroutinesApi::class)
class T196TranslateTest {
    private val fakeKey = "fake-test-key-not-real:fx"
    private val proFakeKey = "fake-test-key-not-real"

    private fun answer(text: String, detected: String) = """{"translations":[{"detected_source_language":"$detected","text":"$text"}]}"""

    private class FakeTransport(val replies: MutableList<TransportResult>) : DeepLTransport {
        val sent = mutableListOf<DeepLRequest>()
        override suspend fun send(request: DeepLRequest): TransportResult { sent += request; return replies.removeAt(0) }
    }
    private fun ok(body: String, status: Int = 200) = TransportResult.Reply(DeepLReply(status, body))

    // Target rule
    @Test fun portugueseGivesEnglishEverythingElseGivesPortugueseOfPortugal() {
        assertEquals(TranslateTarget.English, DeepLRules.targetFor("PT"))
        assertEquals(TranslateTarget.English, DeepLRules.targetFor("pt-BR"))
        assertEquals(TranslateTarget.Portuguese, DeepLRules.targetFor("EN"))
        assertEquals(TranslateTarget.Portuguese, DeepLRules.targetFor("RU"))
        assertEquals(TranslateTarget.Portuguese, DeepLRules.targetFor(null))
        assertEquals("EN-GB", TranslateTarget.English.code)
        assertEquals("PT-PT", TranslateTarget.Portuguese.code)
        assertEquals(TranslateTarget.Portuguese, TranslateTarget.English.flipped)
    }

    @Test fun guessFavoursPortugueseOnlyForPortugueseLookingText() {
        assertEquals(TranslateTarget.English, DeepLRules.guessTarget("Obrigado, até amanhã"))
        assertEquals(TranslateTarget.Portuguese, DeepLRules.guessTarget("Where is the station"))
        assertEquals(TranslateTarget.Portuguese, DeepLRules.guessTarget("   "))
    }

    @Test fun retargetOnlyWhenTheDetectedLanguageDisagrees() {
        assertEquals(TranslateTarget.English, DeepLRules.retarget("PT", TranslateTarget.Portuguese))
        assertNull(DeepLRules.retarget("EN", TranslateTarget.Portuguese))
        assertEquals(TranslateTarget.Portuguese, DeepLRules.retarget("EN", TranslateTarget.English))
    }

    // Request building: nothing but the text, target_lang and the auth header
    @Test fun freeKeyGoesToTheFreeHostAndProKeyToThePro() {
        assertEquals("https://api-free.deepl.com/v2/translate", DeepLRequests.translate(fakeKey, "hello", TranslateTarget.Portuguese).url)
        assertEquals("https://api.deepl.com/v2/translate", DeepLRequests.translate(proFakeKey, "hello", TranslateTarget.Portuguese).url)
        assertEquals("https://api-free.deepl.com/v2/usage", DeepLRequests.usage(fakeKey).url)
        assertTrue(DeepLRequests.usage(proFakeKey).url in listOf("https://api.deepl.com/v2/usage"))
        assertTrue(DeepLRules.hostFor(fakeKey) in DeepLRules.Hosts && DeepLRules.hostFor(proFakeKey) in DeepLRules.Hosts)
    }

    @Test fun translateRequestHasExactlyTheTextTheTargetAndTheAuthHeader() {
        val r = DeepLRequests.translate(fakeKey, "Good \"morning\"", TranslateTarget.Portuguese)
        assertEquals("POST", r.method)
        assertEquals("""{"text":["Good \"morning\""],"target_lang":"PT-PT"}""", r.body)
        assertEquals(setOf("Authorization", "Accept", "Content-Type"), r.headers.keys)
        assertEquals("DeepL-Auth-Key $fakeKey", r.headers["Authorization"])
        assertEquals("application/json", r.headers["Content-Type"])
    }

    @Test fun usageRequestIsAGetWithNoBody() {
        val r = DeepLRequests.usage(fakeKey)
        assertEquals("GET", r.method); assertNull(r.body)
        assertEquals(setOf("Authorization", "Accept"), r.headers.keys)
    }

    // Parsing
    @Test fun parsesTheTranslationAndTheDetectedLanguage() {
        val t = DeepLParser.translation(answer("Bom dia", "EN"))!!
        assertEquals("Bom dia", t.text); assertEquals("EN", t.detectedSource)
        assertNull(DeepLParser.translation("""{"translations":[]}"""))
        assertNull(DeepLParser.translation("not json"))
        assertNull(DeepLParser.translation("""{"translations":[{"detected_source_language":"EN"}]}"""))
        assertEquals(null, DeepLParser.translation("""{"translations":[{"text":"x"}]}""")!!.detectedSource)
    }

    @Test fun parsesUsageAndTreatsTheNoLimitNumberAsNone() {
        assertEquals(DeepLUsage(1234, 500000), DeepLParser.usage("""{"character_count":1234,"character_limit":500000}"""))
        assertNull(DeepLParser.usage("""{"character_count":5,"character_limit":1000000000000}""")!!.limit)
        assertNull(DeepLParser.usage("""{"character_limit":5}"""))
    }

    @Test fun statusCodesBecomePlainProblems() {
        assertEquals(TranslateProblem.KeyRefused, DeepLParser.problem(403))
        assertEquals(TranslateProblem.LimitReached, DeepLParser.problem(456))
        assertEquals(TranslateProblem.TooMany, DeepLParser.problem(429))
        assertEquals(TranslateProblem.TooLong, DeepLParser.problem(413))
        assertEquals(TranslateProblem.Server, DeepLParser.problem(503))
        assertEquals(TranslateProblem.BadResponse, DeepLParser.problem(400))
    }

    // Client
    @Test fun aWrongGuessAsksOnceMoreWithTheDetectedLanguageRule() = runTest {
        val t = FakeTransport(mutableListOf(ok(answer("Eu estou aqui", "EN")), ok(answer("x", "EN"))))
        val r = DeepLClient(t).translate(fakeKey, "I am here", TranslateTarget.English, userChose = false)
        assertEquals(2, t.sent.size)
        assertTrue(t.sent[1].body!!.contains("PT-PT"))
        assertEquals(TranslateOutcome.Done("x", TranslateTarget.Portuguese, "EN"), r)
    }

    @Test fun aRightGuessAsksOnlyOnceAndAChosenTargetIsNeverChanged() = runTest {
        val t = FakeTransport(mutableListOf(ok(answer("Bom dia", "EN")), ok(answer("Good morning", "PT"))))
        assertEquals(1, run { DeepLClient(t).translate(fakeKey, "Good morning", TranslateTarget.Portuguese, false); t.sent.size })
        val r = DeepLClient(t).translate(fakeKey, "Bom dia", TranslateTarget.English, userChose = true)
        assertEquals(2, t.sent.size)
        assertEquals(TranslateOutcome.Done("Good morning", TranslateTarget.English, "PT"), r)
    }

    @Test fun tooManyRequestsIsRetriedOnceAfterAMoment() = runTest {
        val waits = mutableListOf<Long>()
        val t = FakeTransport(mutableListOf(ok("{}", 429), ok(answer("Olá", "EN"))))
        val r = DeepLClient(t) { waits += it }.translate(fakeKey, "Hello", TranslateTarget.Portuguese, false)
        assertEquals(listOf(1_500L), waits)
        assertTrue(r is TranslateOutcome.Done)
        val t2 = FakeTransport(mutableListOf(ok("{}", 429), ok("{}", 429)))
        assertEquals(TranslateOutcome.Failed(TranslateProblem.TooMany), DeepLClient(t2) { }.translate(fakeKey, "Hello", TranslateTarget.Portuguese, false))
        assertEquals(2, t2.sent.size)
    }

    @Test fun limitAndRefusedKeysAreNotRetried() = runTest {
        val t = FakeTransport(mutableListOf(ok("{}", 456)))
        assertEquals(TranslateOutcome.Failed(TranslateProblem.LimitReached), DeepLClient(t).translate(fakeKey, "Hello", TranslateTarget.Portuguese, false))
        val t2 = FakeTransport(mutableListOf(ok("{}", 403)))
        assertEquals(TranslateOutcome.Failed(TranslateProblem.KeyRefused), DeepLClient(t2).translate(fakeKey, "Hello", TranslateTarget.Portuguese, false))
        assertEquals(1, t.sent.size + t2.sent.size - 1)
        val t3 = FakeTransport(mutableListOf(TransportResult.Failed(TranslateProblem.Offline)))
        assertEquals(TranslateOutcome.Failed(TranslateProblem.Offline), DeepLClient(t3).translate(fakeKey, "Hello", TranslateTarget.Portuguese, false))
    }

    @Test fun usageCallReportsTheCount() = runTest {
        val t = FakeTransport(mutableListOf(ok("""{"character_count":42,"character_limit":500000}""")))
        assertEquals(DeepLClient.UsageOutcome.Ok(DeepLUsage(42, 500000)), DeepLClient(t).usage(fakeKey))
        val t2 = FakeTransport(mutableListOf(ok("{}", 403)))
        assertEquals(DeepLClient.UsageOutcome.Failed(TranslateProblem.KeyRefused), DeepLClient(t2).usage(fakeKey))
    }

    // When it sends
    private fun flow(t: FakeTransport, key: String? = fakeKey) = TranslateFlow(DeepLClient(t), { key })

    @Test fun nothingIsSentForFewerThanTwoCharactersOrBeforeThePause() = runTest {
        val t = FakeTransport(mutableListOf(ok(answer("Olá", "EN"))))
        val states = mutableListOf<TranslateState>()
        flow(t).run("a", false, null) { states += it }
        flow(t).run("  b ", false, null) { states += it }
        assertEquals(listOf(TranslateState.Idle, TranslateState.Idle), states)
        assertEquals(0, t.sent.size)
        val job = launch { flow(t).run("hello", false, null) { states += it } }
        advanceTimeBy(599); runCurrent()
        assertEquals(0, t.sent.size)
        advanceTimeBy(2); runCurrent()
        job.join()
        assertEquals(1, t.sent.size)
        assertTrue(states.last() is TranslateState.Done)
    }

    @Test fun enterSendsAtOnce() = runTest {
        val t = FakeTransport(mutableListOf(ok(answer("Olá", "EN"))))
        flow(t).run("hello", true, null) { }
        assertEquals(1, t.sent.size)
    }

    @Test fun aChangedTextCancelsThePauseSoOnlyTheLastTextIsSent() = runTest {
        val t = FakeTransport(mutableListOf(ok(answer("Olá mundo", "EN"))))
        val f = flow(t)
        val first = launch { f.run("hel", false, null) { } }
        advanceTimeBy(300); runCurrent()
        first.cancel()                       // the text changed: the effect restarts
        val second = launch { f.run("hello", false, null) { } }
        advanceTimeBy(700); runCurrent()
        second.join()
        assertEquals(1, t.sent.size)
        assertTrue(t.sent[0].body!!.contains("hello"))
    }

    @Test fun aRequestInFlightIsCancelledWhenTheTextChanges() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<TransportResult>()
        var cancelled = false
        val hanging = DeepLTransport { try { gate.await() } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e } }
        val states = mutableListOf<TranslateState>()
        val job = launch { TranslateFlow(DeepLClient(hanging), { fakeKey }).run("hello", true, null) { states += it } }
        runCurrent()
        assertEquals(TranslateState.Working, states.last())
        job.cancel(); runCurrent()
        assertTrue(cancelled)
        assertFalse(states.any { it is TranslateState.Done })
    }

    @Test fun noKeyTooLongAndFlippedTarget() = runTest {
        val t = FakeTransport(mutableListOf(ok(answer("Hello", "PT"))))
        val states = mutableListOf<TranslateState>()
        flow(t, key = null).run("hello", true, null) { states += it }
        assertEquals(TranslateState.Failed(TranslateProblem.NoKey), states.last())
        flow(t).run("x".repeat(5_001), true, null) { states += it }
        assertEquals(TranslateState.Failed(TranslateProblem.TooLong), states.last())
        assertEquals(0, t.sent.size)
        flow(t).run("bom dia", true, TranslateTarget.English) { states += it }
        assertTrue(t.sent.single().body!!.contains("EN-GB"))
        assertEquals("Português → English", TranslateLabels.line("PT", TranslateTarget.English))
        assertEquals("English → Português (PT)", TranslateLabels.line("EN", TranslateTarget.Portuguese))
    }

    // The key
    private class FakeCipher(private val marker: Byte = 9) : KeyCipher {
        override fun encrypt(plain: ByteArray) = byteArrayOf(marker) + plain.reversedArray()
        override fun decrypt(blob: ByteArray): ByteArray? = if (blob.isNotEmpty() && blob[0] == marker) blob.copyOfRange(1, blob.size).reversedArray() else null
    }
    private class MemoryBlob : KeyBlobStorage {
        var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
        override fun clear() { value = null }
    }

    @Test fun theKeyRoundTripsEncryptedAndIsNeverStoredAsPlainText() {
        val blob = MemoryBlob()
        val store = DeepLKeyStore(FakeCipher(), blob)
        assertNull(store.load())
        assertTrue(store.save("  $fakeKey \n"))
        assertEquals(fakeKey, store.load())
        assertFalse(blob.value!!.contains("fake-test-key"))
        assertTrue(store.has())
        store.clear()
        assertNull(store.load())
    }

    @Test fun translationCredentialTravelsOnlyInProtectedBackups() = runTest {
        val files = object : BackupFileStore {
            var document = ""
            override suspend fun read(uri: String) = document
            override suspend fun write(uri: String, text: String) { document = text }
        }
        fun service(key: DeepLKeyStore) = LayoutBackupService(FakeLayoutRepository(), files,
            stores = { listOf(key.backupStore()) })
        val sourceBlob = MemoryBlob()
        val source = DeepLKeyStore(FakeCipher(1), sourceBlob)
        val exporter = service(source)
        for (passphrase in listOf(null, "backup-test".toCharArray())) {
            val destinationBlob = MemoryBlob()
            val destination = DeepLKeyStore(FakeCipher(2), destinationBlob)
            val importer = service(destination)
            destination.save(proFakeKey)
            source.clear()
            exporter.exportTo("old")
            assertFalse(files.document.contains("\"deepl\""))
            assertTrue(importer.restoreFrom("old") is BackupOutcome.Restored)
            assertEquals(proFakeKey, destination.load())

            source.save(fakeKey)
            assertTrue(exporter.exportTo("new", passphrase) is BackupOutcome.Exported)
            // T402: a plain-text export never carries the key; only the encrypted one does.
            assertFalse(files.document.contains(fakeKey))
            if (passphrase == null) assertFalse(files.document.contains("\"deepl\""))
            if (passphrase != null) {
                val before = destinationBlob.value
                assertEquals(BackupOutcome.NeedsPassphrase, importer.prepareRestore("new"))
                assertEquals(BackupOutcome.Failed(BackupFailure.UnlockFailed),
                    importer.prepareRestore("new", "incorrect".toCharArray()))
                assertEquals(before, destinationBlob.value)
            }
            destination.clear()
            if (passphrase == null) {
                // T402: the plain file has no key, so restoring it leaves this phone without one.
                assertTrue(importer.restoreFrom("new") is BackupOutcome.Restored)
                assertFalse(destination.has())
                continue
            }
            val ready = importer.prepareRestore("new", passphrase) as BackupOutcome.Ready
            assertFalse(destination.has())
            assertTrue(importer.confirm(ready) is BackupOutcome.Restored)
            val reopened = DeepLKeyStore(FakeCipher(2), destinationBlob)
            assertTrue(reopened.has())
            assertEquals(fakeKey, reopened.load())
            assertFalse(sourceBlob.value == destinationBlob.value)
            assertFalse(ParcLog.lines().any { fakeKey in it })
            val header = DiagnosticsHeader("test", 1L, "14", 34, "Pixel", 384, 853, 450, emptyList(), emptyList())
            assertFalse(fakeKey in ExportDiagnostics.build(header, 0, ParcLog.lines(), ParcLog.dropped(), null, null))
        }
    }

    @Test fun badPastesAreRefusedAndNothingIsStored() {
        val blob = MemoryBlob()
        val store = DeepLKeyStore(FakeCipher(), blob)
        listOf("", "short", "has space in the middle of it", "https://example.com/x", "a".repeat(101)).forEach { assertFalse(it, store.save(it)) }
        assertNull(blob.value)
    }

    @Test fun anUndecryptableOrGarbageBlobReadsAsNoKey() {
        val blob = MemoryBlob().apply { value = "!!!not base64!!!" }
        assertNull(DeepLKeyStore(FakeCipher(), blob).load())
        blob.value = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        assertNull(DeepLKeyStore(FakeCipher(), blob).load())
    }

    // History and backup
    @Test fun historyKeepsTheLastTwentyNewestFirstWithoutRepeats() {
        var list = emptyList<TranslationEntry>()
        for (i in 1..25) list = TranslationHistory.add(list, TranslationEntry("s$i", "r$i", TranslateTarget.Portuguese, i.toLong()))
        assertEquals(20, list.size)
        assertEquals("s25", list.first().source)
        list = TranslationHistory.add(list, TranslationEntry("s10", "again", TranslateTarget.Portuguese, 99))
        assertEquals(20, list.size)
        assertEquals(listOf("s10", "s25"), list.take(2).map { it.source })
        assertEquals(list, TranslationHistory.decode(TranslationHistory.encode(list)))
    }

    @Test fun oldEmptyAndDamagedHistoryDecodeToNothingOrWhatIsValid() {
        assertEquals(emptyList<TranslationEntry>(), TranslationHistory.decode(null))
        assertEquals(emptyList<TranslationEntry>(), TranslationHistory.decode(""))
        assertEquals(emptyList<TranslationEntry>(), TranslationHistory.decode("not json"))
        val mixed = """[{"s":"a","r":"b","t":"PT-PT","at":1},{"s":"x"},{"s":"c","r":"d","t":"ZZ"}]"""
        assertEquals(listOf(TranslationEntry("a", "b", TranslateTarget.Portuguese, 1)), TranslationHistory.decode(mixed))
    }

    @Test fun historyAndDeviceEncryptedBlobStayOutOfCoreRegistry() {
        // T204 intentionally replaces T196's history-in-backups policy.
        assertNull(BackupSpecs.byId("translate"))
        assertFalse(BackupSpecs.all.any { it.id == "translate" || it.id == "parc_translate_key" })
    }

    @Test fun theInternetBuildListsTranslateWithItsPrefix() {
        val mode = WeatherVariant.extraSearchModes().single()
        assertEquals("translate", mode.id)
        assertEquals("tr ", mode.prefix)
        assertNotNull(mode)
    }
}
