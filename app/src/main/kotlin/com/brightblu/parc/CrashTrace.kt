package com.brightblu.parc

import android.content.SharedPreferences
import java.time.Instant

/**
 * T88: turns an uncaught exception into the text kept for the diagnostics export. Redacted the same
 * way as [ParcLog] lines, bounded (40 frames per exception, 3 causes, [MaxChars] in all). Pure.
 */
internal object CrashTrace {
    const val MaxChars = 8_000
    private const val MaxFrames = 40
    private const val MaxCauses = 3

    fun format(threadName: String, error: Throwable, epochMillis: Long): String {
        val out = StringBuilder()
        out.append(Instant.ofEpochMilli(epochMillis)).append(" UTC\n")
        out.append("thread: ").append(LogRedaction.redact(threadName)).append('\n')
        var current: Throwable? = error
        var depth = 0
        val seen = HashSet<Throwable>()
        while (current != null && depth <= MaxCauses && seen.add(current)) {
            if (depth > 0) out.append("caused by: ")
            out.append(current.javaClass.name).append(": ")
                .append(LogRedaction.redact(current.message.orEmpty())).append('\n')
            for (frame in current.stackTrace.take(MaxFrames)) {
                out.append("  at ").append(LogRedaction.redact(frame.toString())).append('\n')
            }
            current = current.cause
            depth++
        }
        return if (out.length > MaxChars) out.substring(0, MaxChars) + "\n[truncated]" else out.toString()
    }
}

/** Last crash trace in its own private preferences file; synchronous, the process is dying. */
internal class CrashTraceStore(private val prefs: SharedPreferences) {

    @Synchronized
    fun save(text: String) {
        prefs.edit().putString(KeyTrace, text.take(CrashTrace.MaxChars + 32)).commit()
    }

    /** The saved trace or null when the app has not crashed since the last [clear]. */
    @Synchronized
    fun read(): String? = prefs.getString(KeyTrace, null)?.takeIf { it.isNotBlank() }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KeyTrace).commit()
    }

    companion object {
        const val FileName = "crash_trace"
        private const val KeyTrace = "last_trace"
    }
}
