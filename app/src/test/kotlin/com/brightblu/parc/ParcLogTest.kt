package com.brightblu.parc

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T88: redaction, ring behaviour, header and file formatting, crash trace storage. */
class ParcLogTest {

    // Redaction

    @Test fun `urls emails and numbers are masked`() {
        // Generate synthetic contact data; no literal address or dialable fixture is stored.
        val email = listOf("sample", "example.invalid").joinToString("@")
        val number = "+" + listOf("000", "000", "000", "000").joinToString(" ")
        val out = LogRedaction.redact("open https://example.org/a?b=1 mail $email call $number done")
        assertEquals("open <url> mail <email> call <number> done", out)
    }

    @Test fun `quoted text and sensitive keys are masked`() {
        assertEquals("got \"<text>\" from user", LogRedaction.redact("got \"Hello Anna\" from user"))
        assertEquals("posted title=<redacted>", LogRedaction.redact("posted title=Dinner tonight?"))
        assertEquals("query=<redacted>", LogRedaction.redact("query: where is the key"))
        assertEquals("x '<text>' y", LogRedaction.redact("x 'it' y"))
        assertEquals("it doesn't matter, isn't it", LogRedaction.redact("it doesn't matter, isn't it"))
    }

    @Test fun `third party package and component names are masked but Parc and platform names stay`() {
        assertEquals("icon failed for <pkg>", LogRedaction.redact("icon failed for com.example.chat/.MainActivity"))
        val frame = "at com.brightblu.parc.ring.Foo.bar(Foo.kt:1) android.os.Handler"
        assertEquals(frame, LogRedaction.redact(frame))
        assertEquals("kotlinx.coroutines.Job", LogRedaction.redact("kotlinx.coroutines.Job"))
    }

    @Test fun `plain facts are untouched`() {
        val facts = "Scene built: layer Home, 24 slots, capacities 6,8,10"
        assertEquals(facts, LogRedaction.redact(facts))
    }

    @Test fun `control characters collapse and long lines are capped`() {
        assertEquals("a b", LogRedaction.redact("a\nb"))
        val out = LogRedaction.redact("x".repeat(1000))
        assertEquals(LogRedaction.MaxLineChars + 3, out.length)
        assertTrue(out.endsWith("..."))
        assertEquals("", LogRedaction.redact(""))
    }

    // Ring

    @Test fun `ring keeps the newest lines and counts the dropped ones`() {
        val ring = LogRing(capacity = 3, clock = { 0L })
        for (i in 1..5) ring.add(LogLevel.Info, "T", "m$i")
        val lines = ring.snapshot()
        assertEquals(3, lines.size)
        assertTrue(lines[0].endsWith("m3"))
        assertTrue(lines[2].endsWith("m5"))
        assertEquals(2L, ring.droppedCount())
        ring.clear()
        assertTrue(ring.snapshot().isEmpty())
        assertEquals(0L, ring.droppedCount())
    }

    @Test fun `ring under capacity keeps insertion order`() {
        val ring = LogRing(capacity = 10, clock = { 0L })
        ring.add(LogLevel.Debug, "A", "one")
        ring.add(LogLevel.Error, "B", "two")
        val lines = ring.snapshot()
        assertEquals(listOf("D/A: one", "E/B: two"), lines.map { it.substringAfter(' ').substringAfter(' ') })
    }

    @Test fun `line format is UTC time level tag message`() {
        val millis = java.time.ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 6_000_000, java.time.ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        assertEquals("01-02 03:04:05.006 W/Tag: msg", LogRing.format(millis, LogLevel.Warn, "Tag", "msg"))
    }

    @Test fun `ParcLog redacts before storing and keeps 500 lines`() {
        ParcLog.mirrorToLogcat = false
        ParcLog.clear()
        ParcLog.i("Test", "contact name=Anna Smith opened com.example.chat")
        val line = ParcLog.lines().single()
        assertFalse(line.contains("Anna"))
        assertFalse(line.contains("example"))
        ParcLog.w("Test", "failed", IOException("path \"secret note\""))
        assertFalse(ParcLog.lines().last().contains("secret"))
        assertTrue(ParcLog.lines().last().contains("IOException"))
        repeat(600) { ParcLog.d("Test", "n$it") }
        assertEquals(500, ParcLog.lines().size)
        assertTrue(ParcLog.dropped() > 0)
        ParcLog.clear()
        ParcLog.mirrorToLogcat = true
    }

    // Header and file

    private val header = DiagnosticsHeader(
        appVersionName = "0.1", appVersionCode = 7, androidRelease = "16", sdkInt = 36,
        deviceModel = "Acme Phone 1", screenWidthDp = 384, screenHeightDp = 853, densityDpi = 440,
        features = listOf("comms" to true, "sound theme" to false),
        permissions = listOf("usage access" to "granted", "contacts" to "not granted"),
    )

    @Test fun `header lists version device screen features and permissions`() {
        val text = ExportDiagnostics.header(header, 0L)
        assertEquals(
            "Parc diagnostics\n" +
                "generated: 1970-01-01T00:00:00Z UTC\n" +
                "app: 0.1 (7)\n" +
                "android: 16 (API 36)\n" +
                "device: Acme Phone 1\n" +
                "screen: 384 x 853 dp, 440 dpi\n" +
                "features:\n" +
                "  comms: on\n" +
                "  sound theme: off\n" +
                "permissions:\n" +
                "  usage access: granted\n" +
                "  contacts: not granted\n",
            text,
        )
    }

    @Test fun `file has log crash and scene sections in order`() {
        val scene = SceneDiagnostic("Home", 24, "6,8", "", 1_000L)
        val text = ExportDiagnostics.build(header, 0L, listOf("line one", "line two"), 3L, "boom trace", scene)
        assertTrue(text.contains("== log (2 lines, 3 older dropped, times in UTC) =="))
        assertTrue(text.contains("line two\n"))
        assertTrue(text.contains("== last crash ==\nboom trace\n"))
        assertTrue(text.contains("== scene diagnostics ==\nLayer: Home\nScene slots: 24\n"))
        assertTrue(text.indexOf("== log") < text.indexOf("== last crash"))
        assertTrue(text.indexOf("== last crash") < text.indexOf("== scene"))
    }

    @Test fun `empty parts say so`() {
        val text = ExportDiagnostics.build(header, 0L, emptyList(), 0L, null, null)
        assertTrue(text.contains("(empty)"))
        assertTrue(text.contains("== last crash ==\nnone recorded"))
        assertTrue(text.contains("== scene diagnostics ==\nnone recorded"))
        assertEquals("parc-log.txt", ExportDiagnostics.FileName)
    }

    @Test fun `entry descriptor is stable`() {
        assertEquals("diagnostics_export", DiagnosticsEntry.Default.id)
        assertEquals("Export diagnostics log", DiagnosticsEntry.Default.title)
    }

    // Crash trace

    @Test fun `crash trace is redacted bounded and carries causes`() {
        val cause = IllegalStateException("inner for com.example.chat")
        val error = RuntimeException("failed \"private words\"", cause)
        val text = CrashTrace.format("main", error, 0L)
        assertTrue(text.startsWith("1970-01-01T00:00:00Z UTC\nthread: main\njava.lang.RuntimeException: failed \"<text>\""))
        assertTrue(text.contains("caused by: java.lang.IllegalStateException: inner for <pkg>"))
        assertFalse(text.contains("private words"))
        assertTrue(text.length <= CrashTrace.MaxChars + 20)
    }

    @Test fun `crash trace survives a cause cycle and a huge message`() {
        val a = RuntimeException("a" + "x".repeat(20_000))
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertTrue(CrashTrace.format("t", b, 0L).length <= CrashTrace.MaxChars + 20)
    }

    @Test fun `trace store saves reads and clears`() {
        val store = CrashTraceStore(FakeSharedPreferences())
        assertNull(store.read())
        store.save("trace text")
        assertEquals("trace text", store.read())
        store.clear()
        assertNull(store.read())
    }
}
