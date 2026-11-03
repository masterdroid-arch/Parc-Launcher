package com.brightblu.parc.core.data.skins

import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.SkinColors
import com.brightblu.parc.model.SkinJson
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long

/** Outcome of reading a shared skin file. Immutable. */
sealed class SkinImportResult {
    /** [notes] lists leniencies applied (ignored keys, filled tokens) so the UI can mention them. */
    data class Imported(val skin: Skin, val notes: List<String>) : SkinImportResult()
    data class Rejected(val reason: String) : SkinImportResult()
}

/**
 * Skins as shareable `.parcskin.json` files (T85). Export is the strict skin JSON. Import is lenient on
 * purpose: unknown keys are ignored, missing tokens come from the [base] skin, colours may be written as
 * `#RRGGBB` / `#AARRGGBB` strings, and the result always becomes a new `custom_*` skin, so a file can
 * never replace a built-in skin. Pure: no Android, no files. Thread-safe.
 */
object SkinExchange {
    const val MaxBytes = 64 * 1024
    const val FileSuffix = ".parcskin.json"

    private val pretty = Json { prettyPrint = true; encodeDefaults = true }

    fun fileName(skin: Skin): String = skin.id.removePrefix(SkinNaming.IdPrefix) + FileSuffix

    fun export(skin: Skin): String = pretty.encodeToString(Skin.serializer(), skin) + "\n"

    fun import(text: String, base: Skin, takenIds: Set<String>): SkinImportResult {
        if (text.toByteArray().size > MaxBytes) return SkinImportResult.Rejected("File is larger than ${MaxBytes / 1024} KB")
        val root: JsonObject = try {
            Json.parseToJsonElement(text) as? JsonObject ?: return SkinImportResult.Rejected("Not a skin file")
        } catch (e: Exception) {
            return SkinImportResult.Rejected("Not valid JSON")
        }
        val notes = mutableListOf<String>()
        val knownTop = Skin.serializer().descriptor.elementNames.toSet()
        val knownColors = SkinColors.serializer().descriptor.elementNames.toSet()
        val baseObject = Json.encodeToJsonElement(Skin.serializer(), base) as JsonObject

        val merged = baseObject.toMutableMap()
        // Optional v2 fields absent from a legacy file use schema defaults, not the
        // active skin's module treatment. Existing import fallback tokens are retained.
        for ((section, tokens) in mapOf(
            "colors" to setOf("moduleSurface", "moduleGuide", "moduleInk"),
            "shape" to setOf("moduleStyle", "tileCornerDp", "moduleCornerDp"),
            "typography" to setOf("numeralFamily", "clockDot", "countDot", "bigDot"),
        )) {
            (merged[section] as? JsonObject)?.let { merged[section] = JsonObject(it - tokens) }
        }
        for ((key, value) in root) {
            if (key !in knownTop) { notes += "Ignored unknown key '$key'"; continue }
            if (key == "id") continue
            if (key == "colors") {
                val given = value as? JsonObject ?: return SkinImportResult.Rejected("'colors' must be an object")
                val colors = (merged["colors"] as JsonObject).toMutableMap()
                for ((token, raw) in given) {
                    if (token !in knownColors) { notes += "Ignored unknown colour '$token'"; continue }
                    val argb = readColor(raw) ?: return SkinImportResult.Rejected("Bad colour for '$token'")
                    colors[token] = JsonPrimitive(argb)
                }
                val missing = knownColors.count { it !in given.keys }
                if (missing > 0) notes += "Filled $missing missing colours from ${base.name}"
                merged["colors"] = JsonObject(colors)
            } else merged[key] = value
        }
        val rawName = (merged["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val name = SkinNaming.clean(rawName).take(SkinNaming.MaxNameLength).ifEmpty { "Imported skin" }
        merged["name"] = JsonPrimitive(name)
        merged["id"] = JsonPrimitive(SkinNaming.uniqueId(name, takenIds))
        val skin = try {
            SkinJson.decode(JsonObject(merged).toString())
        } catch (e: Exception) {
            return SkinImportResult.Rejected("Invalid skin: ${e.message?.take(80) ?: "unreadable values"}")
        }
        return SkinImportResult.Imported(skin, notes)
    }

    private fun readColor(element: JsonElement): Long? {
        val p = element as? JsonPrimitive ?: return null
        if (p.isString) return ColorMath.parseHex(p.content)
        return try { p.long.takeIf { it in 0L..0xFFFFFFFFL } } catch (e: Exception) { null }
    }
}
