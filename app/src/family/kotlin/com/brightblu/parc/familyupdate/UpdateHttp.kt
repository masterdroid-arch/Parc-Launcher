package com.brightblu.parc.familyupdate

import com.brightblu.parc.ParcLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** What the engine needs from the network, so tests can answer without one. */
internal interface UpdateTransport {
    /** One small GET. */
    fun getText(address: String, maxBytes: Int): TransportResult<String>

    /** Streams [address] into [target]. [isCancelled] is polled between chunks. */
    fun download(address: String, target: File, maxBytes: Long, isCancelled: () -> Boolean,
        progress: (downloaded: Long, total: Long) -> Unit): TransportResult<File>
}

internal sealed interface TransportResult<out T> {
    data class Ok<T>(val value: T) : TransportResult<T>
    data class Failed(val problem: UpdateProblem) : TransportResult<Nothing>
    /** The caller cancelled; not an error. */
    data object Cancelled : TransportResult<Nothing>
}

/**
 * T396: the only network code of the update checker, compiled only into the family build. A plain GET with the
 * User-Agent `Parc` and nothing else: no account, no token, no cookie, no identifier. Redirects are followed by hand
 * and every hop must be https on GitHub or its file host. Logs carry status codes and class names, never an address.
 */
internal class UpdateHttp(private val allows: (URL) -> Boolean = { UpdateHosts.allowed(it.protocol, it.host) || UpdateOverride.allows(it) }) : UpdateTransport {
    private companion object {
        const val Tag = "Update"
        const val TimeoutMs = 15_000
        const val Buffer = 8 * 1024
        const val MaxRedirects = 5
    }

    private fun problemFor(code: Int): UpdateProblem = when (code) {
        in 500..599 -> UpdateProblem.Server
        else -> UpdateProblem.BadAnswer
    }

    private fun open(address: String): HttpURLConnection {
        var url = URL(address)
        repeat(MaxRedirects + 1) {
            if (!allows(url)) {
                ParcLog.w(Tag, "refused a host outside GitHub")
                throw IOException("untrusted host")
            }
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = TimeoutMs
            connection.readTimeout = TimeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "Parc")
            val code = connection.responseCode
            if (code in setOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw IOException("redirect without a place")
                url = URL(url, location)
            } else return connection
        }
        throw IOException("too many redirects")
    }

    override fun getText(address: String, maxBytes: Int): TransportResult<String> {
        var connection: HttpURLConnection? = null
        return try {
            connection = open(address)
            val code = connection.responseCode
            if (code != 200) return TransportResult.Failed(problemFor(code)).also { ParcLog.w(Tag, "status $code") }
            val out = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(Buffer)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > maxBytes) return TransportResult.Failed(UpdateProblem.BadAnswer)
                }
            }
            TransportResult.Ok(out.toString(Charsets.UTF_8.name()))
        } catch (e: SocketTimeoutException) {
            ParcLog.w(Tag, "timed out (${e.javaClass.simpleName})")
            TransportResult.Failed(UpdateProblem.Timeout)
        } catch (e: IOException) {
            ParcLog.w(Tag, "no connection (${e.javaClass.simpleName})")
            TransportResult.Failed(UpdateProblem.Offline)
        } catch (e: RuntimeException) {
            ParcLog.w(Tag, "request failed (${e.javaClass.simpleName})")
            TransportResult.Failed(UpdateProblem.Offline)
        } finally {
            connection?.disconnect()
        }
    }

    override fun download(address: String, target: File, maxBytes: Long, isCancelled: () -> Boolean,
        progress: (downloaded: Long, total: Long) -> Unit): TransportResult<File> {
        var connection: HttpURLConnection? = null
        return try {
            target.parentFile?.mkdirs()
            connection = open(address)
            val code = connection.responseCode
            if (code != 200) return TransportResult.Failed(problemFor(code)).also { ParcLog.w(Tag, "download status $code") }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: 0L
            if (total > maxBytes) return TransportResult.Failed(UpdateProblem.BadAnswer)
            var downloaded = 0L
            FileOutputStream(target).use { stream ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(Buffer)
                    while (true) {
                        if (isCancelled()) return TransportResult.Cancelled
                        val read = input.read(buffer)
                        if (read < 0) break
                        stream.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded > maxBytes) return TransportResult.Failed(UpdateProblem.BadAnswer)
                        progress(downloaded, total)
                    }
                }
            }
            if (total > 0 && downloaded < total) TransportResult.Failed(UpdateProblem.Offline) else TransportResult.Ok(target)
        } catch (e: SocketTimeoutException) {
            ParcLog.w(Tag, "download timed out (${e.javaClass.simpleName})")
            TransportResult.Failed(UpdateProblem.Timeout)
        } catch (e: IOException) {
            ParcLog.w(Tag, "download failed (${e.javaClass.simpleName})")
            TransportResult.Failed(if (e.message?.contains("ENOSPC") == true) UpdateProblem.NoSpace else UpdateProblem.Offline)
        } catch (e: RuntimeException) {
            ParcLog.w(Tag, "download failed (${e.javaClass.simpleName})")
            TransportResult.Failed(UpdateProblem.Offline)
        } finally {
            connection?.disconnect()
        }
    }
}
