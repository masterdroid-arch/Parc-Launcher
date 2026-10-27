package com.brightblu.parc

import android.content.Context

/** Synchronous commit is intentional: the crashing process is about to die. */
internal class CrashGuard(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("crash_guard", Context.MODE_PRIVATE)
    private val trace = CrashTraceStore(context.applicationContext.getSharedPreferences(CrashTraceStore.FileName, Context.MODE_PRIVATE))

    /**
     * T88: keeps the redacted trace of the last uncaught exception in private storage for the
     * diagnostics export. Survives [reset]: leaving safe mode must not hide what caused it.
     */
    fun saveTrace(thread: Thread, error: Throwable, now: Long = System.currentTimeMillis()) {
        trace.save(CrashTrace.format(thread.name, error, now))
    }

    /** The last saved crash trace, or null. */
    fun lastTrace(): String? = trace.read()

    private fun timestamps(): List<Long> = preferences.getString("timestamps", "")!!
        .split(',').mapNotNull(String::toLongOrNull)

    @Synchronized
    fun record(now: Long = System.currentTimeMillis()): Boolean {
        val crashes = CrashWindow.record(timestamps(), now)
        preferences.edit().putString("timestamps", crashes.joinToString(","))
            .putBoolean("safe_mode", crashes.size >= CrashWindow.threshold).commit()
        return crashes.size >= CrashWindow.threshold
    }

    // Latch until the user asks for recovery, even if the crash window expires while reading the app list.
    fun isSafeMode(): Boolean = preferences.getBoolean("safe_mode", false)

    fun reset(): Boolean = preferences.edit().clear().commit()
}
