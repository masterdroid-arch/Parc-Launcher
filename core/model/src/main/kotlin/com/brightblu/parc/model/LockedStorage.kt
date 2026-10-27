package com.brightblu.parc.model

import java.util.Base64
import kotlinx.serialization.json.*

/** Reversible obfuscation only. Deliberately no key or security claim. Legacy plain values still read. */
object LockedStorage {
    private const val Prefix = "parc-obf1:"
    fun encode(value: String): String = Prefix + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    fun decode(value: String): String = if (value.startsWith(Prefix))
        String(Base64.getDecoder().decode(value.removePrefix(Prefix)), Charsets.UTF_8) else value

    /** Obfuscate values, retaining keys and numeric types so the document stays versioned JSON. */
    fun transform(element: JsonElement, obscure: Boolean): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { transform(it.value, obscure) })
        is JsonArray -> JsonArray(element.map { transform(it, obscure) })
        is JsonPrimitive -> if (element.isString) JsonPrimitive(if (obscure) encode(element.content) else decode(element.content)) else element
    }
}
