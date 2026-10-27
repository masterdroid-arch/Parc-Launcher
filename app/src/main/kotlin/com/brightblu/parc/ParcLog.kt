package com.brightblu.parc

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Severity of one [ParcLog] line, lowest first. */
internal enum class LogLevel(val letter: Char) { Debug('D'), Info('I'), Warn('W'), Error('E') }

/**
 * T88: removes anything that could name an app, a contact or a message before a line is stored.
 * It is a safety net behind the rule "log facts, never content": callers should not pass personal
 * text at all, and what slips through is masked here. Pure and unit-tested.
 *
 * Masked: URLs, e-mail addresses, quoted text, the value after keys such as `title=`, `text:` or
 * `query=`, dotted package or component names that are not Parc's own or the platform's, and long
 * digit runs (phone numbers). Control characters and newlines become spaces; a line is capped.
 */
internal object LogRedaction {
    const val MaxLineChars = 400

    private val url = Regex("""(?i)\b[a-z][a-z0-9+.-]{1,12}://\S+""")
    private val email = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")
    private val doubleQuoted = Regex("\"[^\"\\n]*\"")
    private val singleQuoted = Regex("(?<![A-Za-z0-9])'[^'\\n]*'")
    private val sensitiveValue = Regex(
        """(?i)\b(title|text|body|label|name|contact|sender|query|message|subtitle|ticker|number|phone|address|reply|replies|summary|description)\b\s*[=:]\s*[^,;\n)\]}]*""",
    )
    private val dotted = Regex("""\b[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_$]+){2,}(/[\w.$]+)?""")
    private val digits = Regex("""\+?\d[\d\s().-]{6,}\d""")
    private val control = Regex("""[\u0000-\u001F\u007F]+""")

    /** Prefixes of dotted names that are code, not user data: kept so stack frames stay readable. */
    private val keepPrefixes = listOf(
        "com.brightblu.parc", "java.", "javax.", "kotlin.", "kotlinx.", "android.", "androidx.",
        "com.android.", "dalvik.", "libcore.", "jdk.", "sun.", "org.jetbrains.",
    )

    /** Redacts [text] for storage. Never throws; blank in, blank out. */
    fun redact(text: String): String {
        var s = text
        s = url.replace(s, "<url>")
        s = email.replace(s, "<email>")
        s = doubleQuoted.replace(s, "\"<text>\"")
        s = singleQuoted.replace(s, "'<text>'")
        s = sensitiveValue.replace(s) { it.groupValues[1] + "=<redacted>" }
        s = dotted.replace(s) { m -> if (keepPrefixes.any { m.value.startsWith(it) }) m.value else "<pkg>" }
        s = digits.replace(s, "<number>")
        s = control.replace(s, " ")
        return if (s.length > MaxLineChars) s.take(MaxLineChars) + "..." else s
    }
}

/**
 * Fixed-size ring of the most recent lines (T88). Thread-safe: every method locks. [capacity] lines
 * are kept; older ones are dropped and counted. Storing allocates one string per line, which is fine
 * off the draw path (nothing in a draw loop logs).
 */
internal class LogRing(
    private val capacity: Int = ParcLog.Capacity,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lines = arrayOfNulls<String>(capacity)
    private var start = 0
    private var size = 0
    private var dropped = 0L

    init { require(capacity > 0) }

    /** Appends one already-redacted message. */
    @Synchronized
    fun add(level: LogLevel, tag: String, message: String) {
        val line = format(clock(), level, tag, message)
        if (size < capacity) {
            lines[(start + size) % capacity] = line
            size++
        } else {
            lines[start] = line
            start = (start + 1) % capacity
            dropped++
        }
    }

    /** The kept lines, oldest first. */
    @Synchronized
    fun snapshot(): List<String> = List(size) { lines[(start + it) % capacity]!! }

    /** How many older lines were pushed out since the last [clear]. */
    @Synchronized
    fun droppedCount(): Long = dropped

    @Synchronized
    fun clear() {
        lines.fill(null)
        start = 0
        size = 0
        dropped = 0
    }

    companion object {
        private val time: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

        /** `MM-dd HH:mm:ss.SSS D/Tag: message`, time in UTC. */
        fun format(epochMillis: Long, level: LogLevel, tag: String, message: String): String =
            time.format(Instant.ofEpochMilli(epochMillis)) + " " + level.letter + "/" + tag + ": " + message
    }
}

/**
 * T88: the one logger for Parc's own messages. Lines are redacted and kept in a 500-line in-memory
 * ring that the diagnostics export reads; nothing is written to disk or sent anywhere. Messages also
 * go to logcat when the platform is present (unchanged from the old `Log.*` calls).
 *
 * Rule for callers: log facts (counts, states, ids of Parc's own concepts), never app names,
 * contact names, notification text or search text. [LogRedaction] is the net, not the permission.
 */
internal object ParcLog {
    const val Capacity = 500
    private const val MaxTagChars = 24

    private val ring = LogRing()

    /** Set false in tests that must not touch the platform logger. */
    @Volatile
    var mirrorToLogcat: Boolean = true

    fun d(tag: String, message: String) = log(LogLevel.Debug, tag, message, null)
    fun i(tag: String, message: String) = log(LogLevel.Info, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = log(LogLevel.Warn, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(LogLevel.Error, tag, message, error)

    /** The kept lines, oldest first. */
    fun lines(): List<String> = ring.snapshot()

    /** Lines pushed out of the ring since start. */
    fun dropped(): Long = ring.droppedCount()

    fun clear() = ring.clear()

    private fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        val safeTag = LogRedaction.redact(tag).take(MaxTagChars)
        var text = LogRedaction.redact(message)
        if (error != null) text += " | " + error.javaClass.simpleName + ": " + LogRedaction.redact(error.message.orEmpty())
        ring.add(level, safeTag, text)
        // OPUS6 (audit F11): release builds mirror only the redacted line, never raw text or a trace.
        if (mirrorToLogcat) {
            if (BuildConfig.DEBUG) mirror(level, tag, message, error) else mirror(level, safeTag, text, null)
        }
    }

    private fun mirror(level: LogLevel, tag: String, message: String, error: Throwable?) {
        try {
            when (level) {
                LogLevel.Debug -> android.util.Log.d(tag, message)
                LogLevel.Info -> android.util.Log.i(tag, message)
                LogLevel.Warn -> android.util.Log.w(tag, message, error)
                LogLevel.Error -> android.util.Log.e(tag, message, error)
            }
        } catch (_: Throwable) {
            // JVM unit tests have no Android logger.
        }
    }
}
