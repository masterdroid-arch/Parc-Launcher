package com.brightblu.parc

import com.brightblu.parc.core.data.library.ReaderBookDoc
import com.brightblu.parc.core.data.library.ReaderChapter
import com.brightblu.parc.core.data.library.ReaderSettings
import com.brightblu.parc.core.data.library.ReaderSettingsJson
import com.brightblu.parc.core.data.library.ReaderTtsRules
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T257: read aloud uses short, exact chunks and the reader's existing one-set settings. */
class T257ReaderTest {
    private fun doc(vararg texts: String): ReaderBookDoc = ReaderBookDoc(
        id = "book",
        title = "A book",
        author = "",
        chapters = texts.mapIndexed { index, text ->
            ReaderChapter(index, "Chapter ${index + 1}", "<p>${text}</p>", text)
        },
        toc = emptyList(),
    )

    @Test
    fun planStartsAtTheSavedOffsetAndContinuesIntoTheNextChapter() {
        val book = doc("Alpha beta. Gamma delta.", "Second chapter text.")
        val chunks = ReaderTtsRules.plan(book, chapter = 0, offset = 12)
        assertTrue(chunks.isNotEmpty())
        assertEquals(0, chunks.first().chapter)
        assertTrue(chunks.first().start >= 12)
        assertEquals("Gamma delta.", chunks.first().text)
        val last = chunks.last()
        assertEquals(1, last.chapter)
        assertEquals(0, last.start)
        assertEquals("Second chapter text.", last.text)
        chunks.forEach { chunk ->
            val text = book.chapters[chunk.chapter].text
            assertEquals(text.substring(chunk.start, chunk.end), chunk.text)
            assertTrue(chunk.text.length <= ReaderTtsRules.MaxChunkChars)
        }
    }

    @Test
    fun longTextIsCutAtSentencesThenWordsThenOnlyWhenThereIsNoSpace() {
        val sentences = ("A short sentence with a few words. ").repeat(40)
        val sentenceChunks = ReaderTtsRules.chunkText(sentences, 0, maxChars = 200)
        assertTrue(sentenceChunks.size > 1)
        assertTrue(sentenceChunks.dropLast(1).all { it.text.endsWith(".") })
        assertTrue(sentenceChunks.all { it.text.length <= 200 })

        val words = ("word ").repeat(100)
        val wordChunks = ReaderTtsRules.chunkText(words, 0, maxChars = 200)
        assertTrue(wordChunks.size > 1)
        assertTrue(wordChunks.all { it.text.length <= 200 && it.text == it.text.trim() })

        val noSpace = "x".repeat(650)
        val hard = ReaderTtsRules.chunkText(noSpace, 0, maxChars = 200)
        assertEquals(listOf(200, 200, 200, 50), hard.map { it.text.length })
        assertEquals(noSpace, hard.joinToString("") { it.text })
        assertEquals(hard.last().end, noSpace.length)
    }

    @Test
    fun blankAndShortChaptersDoNotInventText() {
        val book = doc("", "   ", "Only this chapter has words.")
        val chunks = ReaderTtsRules.plan(book, chapter = 0, offset = 0)
        assertEquals(1, chunks.size)
        assertEquals(2, chunks.single().chapter)
        assertTrue(ReaderTtsRules.plan(book, chapter = 2, offset = 1000).isEmpty())
    }

    @Test
    fun speedChoicesAreClampedLabelledAndCycle() {
        assertEquals(2f, ReaderTtsRules.clampSpeed(8f), 0f)
        assertEquals(0.5f, ReaderTtsRules.clampSpeed(-2f), 0f)
        assertEquals(1f, ReaderTtsRules.nearestSpeed(Float.NaN), 0f)
        assertEquals(1f, ReaderTtsRules.nearestSpeed(0.9f), 0f)
        assertEquals(0.75f, ReaderTtsRules.nearestSpeed(0.5f), 0f)
        assertEquals("0.75x", ReaderTtsRules.speedLabel(0.75f))
        assertEquals("1.25x", ReaderTtsRules.speedLabel(1.25f))
        assertEquals("1.5x", ReaderTtsRules.speedLabel(1.5f))
        assertEquals("2x", ReaderTtsRules.speedLabel(2f))
        assertEquals(0.75f, ReaderTtsRules.nextSpeed(2f), 0f)
    }

    @Test
    fun readAloudChoicesRoundTripAndOldDocumentsReadAsDefaults() {
        val custom = ReaderSettings().copy(ttsSpeed = 1.5f, ttsStopAtChapterEnd = true)
        assertEquals(custom, ReaderSettingsJson.decode(ReaderSettingsJson.encode(custom)))
        assertTrue(ReaderSettingsJson.encode(custom).length <= ReaderSettingsJson.MaxChars)

        val old = ReaderSettingsJson.decode("""{"fontSp":20,"family":"Sans"}""")
        assertEquals(1f, old.ttsSpeed, 0f)
        assertFalse(old.ttsStopAtChapterEnd)
        assertEquals(2f, ReaderSettingsJson.decode("""{"ttsSpeed":99}""").ttsSpeed, 0f)
        assertEquals(0.75f, ReaderSettingsJson.decode("""{"ttsSpeed":-3}""").ttsSpeed, 0f)
        assertTrue(ReaderSettingsJson.decode("""{"ttsStopAtChapterEnd":true}""").ttsStopAtChapterEnd)
    }

    @Test
    fun readerOffersListenAndTheMediaServiceIsDeclared() {
        val reader = File("src/main/kotlin/com/brightblu/parc/reader")
        val screen = File(reader, "ReaderScreen.kt").readText()
        val ui = File(reader, "ReaderTtsUi.kt").readText()
        val service = File(reader, "ReaderTtsService.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText().replace(Regex("\\s+"), " ")

        assertTrue(screen.contains("ReaderSheet.Listen"))
        assertTrue(screen.contains("ReaderTtsService.start("))
        assertTrue(screen.contains("setSpeaking("))
        assertTrue(ui.contains("ReaderChoiceRow(") && ui.contains("Speed"))
        assertTrue(ui.contains("\"Stop at chapter end\""))
        assertTrue(service.contains("TextToSpeech"))
        assertTrue(service.contains("setSpeechRate"))
        assertTrue(service.contains("onRangeStart"))
        assertTrue(service.contains("Notification.MediaStyle"))
        assertTrue(manifest.contains("android:name=\".reader.ReaderTtsService\""))
        assertTrue(manifest.contains("android:foregroundServiceType=\"mediaPlayback\""))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"))
    }
}
