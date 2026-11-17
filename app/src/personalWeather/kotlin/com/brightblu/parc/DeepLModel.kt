package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * T196: the pure rules of Translate mode (DeepL API v2). No Android, no network, no logging.
 * Facts checked against developers.deepl.com on 2026-11-10: see reports/T196.md.
 */

/** The two target languages. [code] is the DeepL `target_lang`. */
internal enum class TranslateTarget(val code: String, val label: String) {
    English("EN-GB", "English"),
    Portuguese("PT-PT", "Português (PT)");

    val flipped: TranslateTarget get() = if (this == English) Portuguese else English
}

/** Plain-words problems. */
internal enum class TranslateProblem(val message: String) {
    NoKey("Add your DeepL key to translate."),
    Offline("No connection. Try again when you are online."),
    Timeout("DeepL took too long. Try again."),
    KeyRefused("DeepL refused the key. Check it in the key screen."),
    LimitReached("This month's DeepL limit is used up."),
    TooMany("DeepL is busy. Try again in a moment."),
    TooLong("That text is too long to translate here."),
    Server("DeepL had a problem. Try again later."),
    BadResponse("DeepL sent an answer Parc could not read."),
}

internal object DeepLRules {
    val Hosts = setOf("api-free.deepl.com", "api.deepl.com")

    /** A key ending in ":fx" is a Free plan key and goes to the Free host; every other key goes to the Pro host. */
    fun hostFor(key: String): String = if (key.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"

    /** Detected Portuguese (any variant, "PT", "PT-BR") gives English; anything else gives Portuguese (PT). */
    fun targetFor(detectedSource: String?): TranslateTarget =
        if (detectedSource != null && detectedSource.uppercase().startsWith("PT")) TranslateTarget.English else TranslateTarget.Portuguese

    private val portugueseWords = setOf(
        "não", "nao", "uma", "você", "voce", "está", "esta", "são", "obrigado", "obrigada", "olá", "ola", "bom", "boa", "dia", "com", "para",
        "por", "que", "mais", "muito", "isso", "como", "tem", "fazer", "onde", "quando", "hoje", "amanhã", "casa", "eu", "ele", "ela", "nós",
    )

    /**
     * Only a first guess of the target before the answer is known (the request needs `target_lang`);
     * DeepL's detected language decides. A wrong guess costs one repeat request, see [retarget].
     */
    fun guessTarget(text: String): TranslateTarget {
        val words = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return TranslateTarget.Portuguese
        val hits = words.count { it in portugueseWords || it.any { c -> c in "ãõçâêô" } }
        return if (hits * 3 >= words.size) TranslateTarget.English else TranslateTarget.Portuguese
    }

    /** After an answer: the target to ask again with, or null when the target already fits the detected language. */
    fun retarget(detectedSource: String?, used: TranslateTarget): TranslateTarget? =
        targetFor(detectedSource).takeIf { it != used }

    fun length(text: String): Int = text.trim().let { it.codePointCount(0, it.length) }
    fun worthSending(text: String): Boolean = length(text) >= Metrics.translateMinChars
    fun tooLong(text: String): Boolean = length(text) > Metrics.translateMaxChars

    /** Characters allowed in a pasted key: letters, digits, "-" and a final ":fx". */
    fun cleanKey(pasted: String): String? {
        val key = pasted.trim()
        return key.takeIf { it.length in 10..100 && Regex("[0-9A-Za-z-]+(:fx)?").matches(it) }
    }
}

/** One HTTPS request, described as data so the rules and tests can look at exactly what would be sent. */
internal data class DeepLRequest(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

internal data class DeepLReply(val status: Int, val body: String)

internal object DeepLRequests {
    private fun auth(key: String) = mapOf(
        "Authorization" to "DeepL-Auth-Key $key",
        "Accept" to "application/json",
    )

    /** `POST /v2/translate`: the text, `target_lang` and the auth header. Nothing else (no source_lang, no options). */
    fun translate(key: String, text: String, target: TranslateTarget): DeepLRequest = DeepLRequest(
        "POST", "https://${DeepLRules.hostFor(key)}/v2/translate",
        auth(key) + ("Content-Type" to "application/json"),
        buildJsonObject {
            put("text", buildJsonArray { add(JsonPrimitive(text)) })
            put("target_lang", target.code)
        }.toString(),
    )

    fun usage(key: String): DeepLRequest = DeepLRequest("GET", "https://${DeepLRules.hostFor(key)}/v2/usage", auth(key), null)
}

internal data class DeepLTranslation(val detectedSource: String?, val text: String)
internal data class DeepLUsage(val used: Long, val limit: Long?)

internal object DeepLParser {
    private val json = Json { ignoreUnknownKeys = true }
    private fun root(text: String): JsonObject? = try { json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null }
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    fun translation(body: String): DeepLTranslation? {
        val first = (root(body)?.get("translations") as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val text = first["text"].str() ?: return null
        return DeepLTranslation(first["detected_source_language"].str(), text)
    }

    /** `character_count` and `character_limit`; DeepL reports 1000000000000 when there is no limit, shown as none. */
    fun usage(body: String): DeepLUsage? {
        val o = root(body) ?: return null
        val used = (o["character_count"] as? JsonPrimitive)?.longOrNull ?: return null
        val limit = (o["character_limit"] as? JsonPrimitive)?.longOrNull
        return DeepLUsage(used, limit?.takeIf { it in 1 until NoLimit })
    }
    const val NoLimit = 1_000_000_000_000L

    /** The problem for a non-200 status. 429 and 529 ask for a retry with backoff; 456 and 403 never retry. */
    fun problem(status: Int): TranslateProblem = when (status) {
        403 -> TranslateProblem.KeyRefused
        456 -> TranslateProblem.LimitReached
        429, 529 -> TranslateProblem.TooMany
        413 -> TranslateProblem.TooLong
        in 500..599 -> TranslateProblem.Server
        else -> TranslateProblem.BadResponse
    }
}

/** One saved translation. */
internal data class TranslationEntry(val source: String, val result: String, val target: TranslateTarget, val at: Long)

/** The last [Metrics.translateHistoryMax] translations, newest first, kept on the phone only, outside backups. */
internal object TranslationHistory {
    private val json = Json { ignoreUnknownKeys = true }
    const val MaxChars = 16_384 * 8

    fun add(list: List<TranslationEntry>, entry: TranslationEntry): List<TranslationEntry> =
        (listOf(entry) + list.filterNot { it.source == entry.source && it.target == entry.target }).take(Metrics.translateHistoryMax)

    fun encode(list: List<TranslationEntry>): String = JsonArray(list.take(Metrics.translateHistoryMax).map {
        buildJsonObject { put("s", it.source); put("r", it.result); put("t", it.target.code); put("at", it.at) }
    }).toString()

    /** Garbage and empty text give an empty list; bad items are dropped; at most the limit is kept. */
    fun decode(text: String?): List<TranslationEntry> {
        if (text.isNullOrBlank()) return emptyList()
        val array = try { json.parseToJsonElement(text) as? JsonArray } catch (e: Exception) { null } ?: return emptyList()
        return array.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val s = o["s"].str() ?: return@mapNotNull null
            val r = o["r"].str() ?: return@mapNotNull null
            val t = TranslateTarget.entries.firstOrNull { it.code == o["t"].str() } ?: return@mapNotNull null
            if (s.isEmpty() || r.isEmpty()) return@mapNotNull null
            TranslationEntry(s, r, t, (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L)
        }.take(Metrics.translateHistoryMax)
    }
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
