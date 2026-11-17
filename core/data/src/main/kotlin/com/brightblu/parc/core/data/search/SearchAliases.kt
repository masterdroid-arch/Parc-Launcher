package com.brightblu.parc.core.data.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * T259: the search alias of an app, keyed by its stable app id. An alias is a second name the user
 * types to find that app; it never replaces the app's label. Stored on the phone and in backups.
 *
 * The document is a small JSON object so an alias may contain punctuation without inventing a second
 * escaping format. Unknown or malformed entries are dropped instead of failing the whole store.
 */
object SearchAliases {
    /** Longer aliases stop being useful in a search field and are cut when saved. */
    const val MaxLength = 48

    /** More aliases than this would be a dictionary, not a launcher convenience. */
    const val MaxEntries = 256

    /** One line, trimmed, at most [MaxLength] characters. Empty means "no alias". */
    fun normalize(raw: String): String =
        raw.replace('\n', ' ').replace('\r', ' ').trim().take(MaxLength)

    /** Adds, changes or (with a blank alias) removes one app's alias. */
    fun updated(aliases: Map<String, String>, appId: String, alias: String): Map<String, String> {
        if (appId.isBlank()) return aliases
        val clean = normalize(alias)
        if (clean.isEmpty()) return aliases - appId
        val next = LinkedHashMap(aliases)
        next[appId] = clean
        while (next.size > MaxEntries) {
            val first = next.keys.firstOrNull() ?: break
            next.remove(first)
        }
        return next
    }

    /** Encodes a deterministic document; blank aliases and blank ids are never written. */
    fun encode(aliases: Map<String, String>): String {
        val body = buildJsonObject {
            aliases.toSortedMap().forEach { (appId, alias) ->
                val clean = normalize(alias)
                if (appId.isNotBlank() && clean.isNotEmpty()) put(appId, clean)
            }
        }
        return body.toString()
    }

    /** Decodes a stored document. Missing, empty or malformed data reads as no aliases. */
    fun decode(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val objectValue = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyMap()
        return decodeObject(objectValue)
    }

    private fun decodeObject(value: JsonObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((appId, element) in value) {
            if (out.size >= MaxEntries) break
            if (appId.isBlank()) continue
            val alias = runCatching { element.jsonPrimitive.contentOrNull }.getOrNull() ?: continue
            val clean = normalize(alias)
            if (clean.isNotEmpty()) out[appId] = clean
        }
        return out
    }
}
