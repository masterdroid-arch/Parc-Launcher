package com.brightblu.parc.core.data.stats

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spawns one `dumpsys` process and returns its stdout. Only used when `android.permission.DUMP` is
 * held; a failed or timed-out run returns null. Never called from the main thread.
 */
internal interface DumpsysRunner {
    suspend fun run(service: String): String?
}

/**
 * Production [DumpsysRunner]. The primary command is the absolute path (guaranteed to exist on
 * Android); if that binary is absent the runner falls back to PATH resolution from the *next*
 * run, so one round still spawns at most one process.
 */
internal class ProcessDumpsysRunner(
    private val timeoutMillis: Long = DefaultTimeoutMillis,
) : DumpsysRunner {

    @Volatile
    private var command = AbsoluteCommand

    override suspend fun run(service: String): String? {
        val process = try {
            ProcessBuilder(command, service).redirectErrorStream(true).start()
        } catch (io: IOException) {
            command = PathCommand
            return null
        } catch (unexpected: Throwable) {
            return null
        }
        return try {
            process.outputStream.close()
            val text = withTimeoutOrNull(timeoutMillis) {
                withContext(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
            }
            if (text == null) process.destroyForcibly()
            text
        } catch (cancellation: CancellationException) {
            process.destroyForcibly()
            throw cancellation
        } catch (unexpected: Throwable) {
            process.destroyForcibly()
            null
        } finally {
            process.destroy()
        }
    }

    companion object {
        const val AbsoluteCommand = "/system/bin/dumpsys"
        const val PathCommand = "dumpsys"
        const val DefaultTimeoutMillis = 2_000L
    }
}
