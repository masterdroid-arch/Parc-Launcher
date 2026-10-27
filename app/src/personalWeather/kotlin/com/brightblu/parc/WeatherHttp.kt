package com.brightblu.parc

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * T137: the only network code in Parc, compiled only into the weather variant. One GET to an
 * Open-Meteo host over https, a short timeout, no redirects, no cookies, no cache, a size limit.
 * Logs never contain the URL (it holds the coordinates or the typed city).
 */
internal sealed interface FetchResult {
    data class Ok(val body: String) : FetchResult
    data class Failed(val problem: WeatherProblem) : FetchResult
}

internal object WeatherHttp {
    private const val Tag = "Weather"
    private const val TimeoutMs = 8_000
    private const val MaxBytes = 512 * 1024

    fun get(address: String): FetchResult {
        val url = try { URL(address) } catch (e: IOException) {
            ParcLog.w(Tag, "bad address (${e.javaClass.simpleName})"); return FetchResult.Failed(WeatherProblem.BadResponse)
        }
        if (url.protocol != "https" || url.host !in OpenMeteo.Hosts) {
            ParcLog.w(Tag, "refused a host that is not Open-Meteo")
            return FetchResult.Failed(WeatherProblem.BadResponse)
        }
        var connection: HttpsURLConnection? = null
        return try {
            connection = url.openConnection() as HttpsURLConnection
            connection.connectTimeout = TimeoutMs
            connection.readTimeout = TimeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            when {
                code == 429 -> FetchResult.Failed(WeatherProblem.RateLimited).also { ParcLog.w(Tag, "rate limited") }
                code in 500..599 -> FetchResult.Failed(WeatherProblem.Server).also { ParcLog.w(Tag, "server error $code") }
                code != 200 -> FetchResult.Failed(WeatherProblem.BadResponse).also { ParcLog.w(Tag, "unexpected status $code") }
                else -> {
                    val out = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            if (out.size() > MaxBytes) {
                                ParcLog.w(Tag, "answer too large")
                                return FetchResult.Failed(WeatherProblem.BadResponse)
                            }
                        }
                    }
                    FetchResult.Ok(out.toString(Charsets.UTF_8.name()))
                }
            }
        } catch (e: SocketTimeoutException) {
            ParcLog.w(Tag, "timed out (${e.javaClass.simpleName})")
            FetchResult.Failed(WeatherProblem.Timeout)
        } catch (e: IOException) {
            ParcLog.w(Tag, "no connection (${e.javaClass.simpleName})")
            FetchResult.Failed(WeatherProblem.Offline)
        } catch (e: RuntimeException) {
            ParcLog.w(Tag, "request failed (${e.javaClass.simpleName})")
            FetchResult.Failed(WeatherProblem.Offline)
        } finally {
            connection?.disconnect()
        }
    }
}
