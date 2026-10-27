package com.brightblu.parc.core.data.focus

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * JSON codecs for the two stored focus documents (T72).
 *
 * Reading is tolerant on purpose: a missing, blank, corrupt or future-shaped document reads as "no
 * modes" or "no override", so a bad pref can never leave the launcher hiding apps it cannot name.
 * Unknown keys are ignored so a newer build can add a field without an older one discarding every
 * mode. Writing is canonical ([encodeDefaults] on), so a round trip is stable.
 *
 * Duplicate mode names in a stored document are collapsed to the first occurrence, because the name
 * is the mode's identity and [FocusRules.activeFocus] picks the first match. Thread-safe: [json] is
 * immutable and the functions allocate their own results.
 */
object FocusJson {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** The stored list as JSON text. */
    fun encodeModes(modes: List<FocusMode>): String =
        json.encodeToString(ListSerializer(FocusMode.serializer()), modes)

    /** The stored list, or an empty list when the text is absent or unusable. */
    fun decodeModes(text: String?): List<FocusMode> = decodeStored(text).modes

    /**
     * OPUS6 (audit F4): the stored list decoded mode by mode. A mode this build cannot read (an
     * out-of-range value, an unknown option, a damaged entry) is kept as its raw JSON in [unreadable]
     * instead of failing the whole list, so a later save writes it back untouched. [corrupt] means
     * the text exists but is not a list at all; such text must never be overwritten.
     */
    data class Stored(val modes: List<FocusMode>, val unreadable: List<JsonElement>, val corrupt: Boolean)

    fun decodeStored(text: String?): Stored {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return Stored(emptyList(), emptyList(), corrupt = false)
        val array = try {
            json.parseToJsonElement(raw) as? JsonArray
        } catch (invalid: Exception) {
            null
        } ?: return Stored(emptyList(), emptyList(), corrupt = true)
        val modes = ArrayList<FocusMode>(array.size)
        val unreadable = ArrayList<JsonElement>()
        for (element in array) {
            try {
                modes += json.decodeFromJsonElement(FocusMode.serializer(), element)
            } catch (invalid: Exception) {
                unreadable += element
            }
        }
        return Stored(modes.distinctBy { it.name }, unreadable, corrupt = false)
    }

    /** [modes] followed by the [unreadable] entries exactly as they were stored. */
    fun encodeStored(modes: List<FocusMode>, unreadable: List<JsonElement>): String {
        val readable = json.encodeToJsonElement(ListSerializer(FocusMode.serializer()), modes) as JsonArray
        return json.encodeToString(JsonArray.serializer(), JsonArray(readable + unreadable))
    }

    /** The stored manual override as JSON text. */
    fun encodeOverride(override: FocusOverride): String =
        json.encodeToString(FocusOverride.serializer(), override)

    /** The stored override, or null when the text is absent or unusable. */
    fun decodeOverride(text: String?): FocusOverride? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return try {
            json.decodeFromString(FocusOverride.serializer(), raw)
        } catch (invalid: Exception) {
            null
        }
    }
}
