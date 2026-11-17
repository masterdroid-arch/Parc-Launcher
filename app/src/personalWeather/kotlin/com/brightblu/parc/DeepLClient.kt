package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** T196: sends one request and returns the status and body, or a problem. Tests use a fake; the real one is [DeepLHttp]. */
internal fun interface DeepLTransport {
    suspend fun send(request: DeepLRequest): TransportResult
}

internal sealed interface TransportResult {
    data class Reply(val reply: DeepLReply) : TransportResult
    data class Failed(val problem: TranslateProblem) : TransportResult
}

/**
 * T196: the only code that talks to DeepL, beside the weather code in the same variant. https only,
 * the two DeepL hosts only, a short timeout, no redirects, no cookies, no cache, a size limit. It
 * sends the headers of the request and a fixed `User-Agent: Parc`, so nothing about the phone goes
 * out. It never logs the address, the key, the text or the answer.
 */
internal object DeepLHttp : DeepLTransport {
    private const val Tag = "Translate"
    private const val TimeoutMs = 8_000
    private const val MaxBytes = 256 * 1024

    override suspend fun send(request: DeepLRequest): TransportResult {
        val url = try { URL(request.url) } catch (e: IOException) { return TransportResult.Failed(TranslateProblem.BadResponse) }
        if (url.protocol != "https" || url.host !in DeepLRules.Hosts) {
            ParcLog.w(Tag, "refused a host that is not DeepL")
            return TransportResult.Failed(TranslateProblem.BadResponse)
        }
        val open = AtomicReference<HttpsURLConnection?>()
        return coroutineScope {
            val call = async(Dispatchers.IO) { blocking(url, request, open) }
            try { call.await() } catch (e: CancellationException) { open.get()?.disconnect(); throw e }
        }
    }

    private fun blocking(url: URL, request: DeepLRequest, open: AtomicReference<HttpsURLConnection?>): TransportResult {
        var connection: HttpsURLConnection? = null
        return try {
            connection = url.openConnection() as HttpsURLConnection
            open.set(connection)
            connection.requestMethod = request.method
            connection.connectTimeout = TimeoutMs
            connection.readTimeout = TimeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "Parc")
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            request.body?.let { body ->
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val out = ByteArrayOutputStream()
            stream?.use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MaxBytes) return TransportResult.Failed(TranslateProblem.BadResponse)
                }
            }
            TransportResult.Reply(DeepLReply(code, out.toString(Charsets.UTF_8.name())))
        } catch (e: SocketTimeoutException) {
            TransportResult.Failed(TranslateProblem.Timeout)
        } catch (e: IOException) {
            TransportResult.Failed(TranslateProblem.Offline)
        } catch (e: RuntimeException) {
            TransportResult.Failed(TranslateProblem.Offline)
        } finally {
            connection?.disconnect()
        }
    }
}

/** What a translation attempt ended with. */
internal sealed interface TranslateOutcome {
    data class Done(val text: String, val target: TranslateTarget, val detectedSource: String?) : TranslateOutcome
    data class Failed(val problem: TranslateProblem) : TranslateOutcome
}

/**
 * T196: translate and usage calls over a [DeepLTransport]. A 429 is retried once after
 * [Metrics.translateRetryDelayMs]; 456, 403 and the rest are not retried.
 */
internal class DeepLClient(
    private val transport: DeepLTransport,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private suspend fun call(request: DeepLRequest): TransportResult {
        var result = transport.send(request)
        if (result is TransportResult.Reply && result.reply.status in setOf(429, 529)) {
            pause(Metrics.translateRetryDelayMs)
            result = transport.send(request)
        }
        return result
    }

    /** Translates [text] to [target]. When the detected language says the target is wrong (see [DeepLRules.retarget]) and the user did not choose it, asks once more. */
    suspend fun translate(key: String, text: String, target: TranslateTarget, userChose: Boolean): TranslateOutcome {
        var asked = target
        repeat(2) {
            when (val r = call(DeepLRequests.translate(key, text, asked))) {
                is TransportResult.Failed -> return TranslateOutcome.Failed(r.problem)
                is TransportResult.Reply -> {
                    if (r.reply.status != 200) return TranslateOutcome.Failed(DeepLParser.problem(r.reply.status))
                    val parsed = DeepLParser.translation(r.reply.body) ?: return TranslateOutcome.Failed(TranslateProblem.BadResponse)
                    val better = if (userChose) null else DeepLRules.retarget(parsed.detectedSource, asked)
                    if (better == null || it == 1) return TranslateOutcome.Done(parsed.text, asked, parsed.detectedSource)
                    asked = better
                }
            }
        }
        return TranslateOutcome.Failed(TranslateProblem.BadResponse)
    }

    sealed interface UsageOutcome {
        data class Ok(val usage: DeepLUsage) : UsageOutcome
        data class Failed(val problem: TranslateProblem) : UsageOutcome
    }

    suspend fun usage(key: String): UsageOutcome = when (val r = call(DeepLRequests.usage(key))) {
        is TransportResult.Failed -> UsageOutcome.Failed(r.problem)
        is TransportResult.Reply -> when {
            r.reply.status != 200 -> UsageOutcome.Failed(DeepLParser.problem(r.reply.status))
            else -> DeepLParser.usage(r.reply.body)?.let { UsageOutcome.Ok(it) } ?: UsageOutcome.Failed(TranslateProblem.BadResponse)
        }
    }
}

/** The state Translate mode shows. */
internal sealed interface TranslateState {
    data object Idle : TranslateState
    data object Working : TranslateState
    data class Done(val source: String, val outcome: TranslateOutcome.Done) : TranslateState
    data class Failed(val problem: TranslateProblem) : TranslateState
}

/**
 * T196: when to send. Only text of at least [Metrics.translateMinChars] characters, after a
 * [Metrics.translateDebounceMs] pause (or at once on Enter), never over [Metrics.translateMaxChars].
 * It runs inside the Body's LaunchedEffect, so a changed text cancels it: during the pause nothing
 * is sent, and an in-flight request is cancelled (the connection is closed).
 */
internal class TranslateFlow(
    private val client: DeepLClient,
    private val keyProvider: () -> String?,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun run(text: String, immediate: Boolean, forced: TranslateTarget?, onState: (TranslateState) -> Unit) {
        if (!DeepLRules.worthSending(text)) { onState(TranslateState.Idle); return }
        if (DeepLRules.tooLong(text)) { onState(TranslateState.Failed(TranslateProblem.TooLong)); return }
        val key = keyProvider()
        if (key == null) { onState(TranslateState.Failed(TranslateProblem.NoKey)); return }
        if (!immediate) pause(Metrics.translateDebounceMs)
        onState(TranslateState.Working)
        val clean = text.trim()
        val target = forced ?: DeepLRules.guessTarget(clean)
        when (val outcome = client.translate(key, clean, target, userChose = forced != null)) {
            is TranslateOutcome.Done -> onState(TranslateState.Done(clean, outcome))
            is TranslateOutcome.Failed -> onState(TranslateState.Failed(outcome.problem))
        }
    }
}
