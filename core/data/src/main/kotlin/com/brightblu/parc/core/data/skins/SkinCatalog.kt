package com.brightblu.parc.core.data.skins

import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.SkinJson

/** One skin file as read from disk: its file name and its raw JSON text. Immutable, non-null. */
data class SkinAsset(val name: String, val json: String)

/** A skin file that was refused; the asset is skipped, never half-applied (DESIGN §2.8). Immutable. */
data class SkinRejection(val asset: String, val reason: String)

/**
 * Validated, immutable skin set. Non-null; [skins] is an immutable snapshot in display order with the
 * default skin first. [byId] never throws: an unknown or blank id resolves to [default], so the
 * launcher can always paint (a downgrade or a hand-edited setting cannot leave it skinless).
 */
class SkinCatalog(
    val skins: List<Skin>,
    val default: Skin,
    val rejections: List<SkinRejection> = emptyList(),
) {
    init {
        require(skins.isNotEmpty()) { "A skin catalog must contain at least the default skin" }
        require(default in skins) { "The default skin must be part of the catalog" }
        require(skins.map { it.id }.distinct().size == skins.size) { "Duplicate skin ids" }
    }

    val ids: List<String> get() = skins.map { it.id }

    fun byId(id: String): Skin = skins.firstOrNull { it.id == id } ?: default
}

/**
 * Parses skin assets into a [SkinCatalog] (DESIGN §2.8). Pure: it takes name/text pairs, so the asset
 * read stays Android-side and the validation runs in a plain unit test.
 *
 * A file is accepted only when it decodes strictly through [SkinJson] (unknown tokens, missing fields
 * and out-of-range values all throw), its `id` matches its file name and the id is unique. Every
 * refusal is recorded in [SkinCatalog.rejections] instead of throwing, so one bad file cannot stop the
 * launcher. The built-in [fallback] is always present and first, even when every asset is rejected.
 */
object SkinCatalogLoader {

    /** The skin the launcher falls back to when no valid asset provides it. */
    val BuiltInDefault: Skin = Skin.Midnight

    fun parse(assets: List<SkinAsset>, fallback: Skin = BuiltInDefault): SkinCatalog {
        val accepted = mutableListOf<Skin>()
        val rejections = mutableListOf<SkinRejection>()
        val seen = mutableSetOf<String>()

        for (asset in assets) {
            val baseName = asset.name.substringBeforeLast('.').trim()
            val skin = try {
                SkinJson.decode(asset.json)
            } catch (error: Exception) {
                rejections += SkinRejection(asset.name, "invalid skin JSON: ${error.message ?: error::class.java.simpleName}")
                continue
            }
            when {
                skin.id.isBlank() -> rejections += SkinRejection(asset.name, "blank skin id")
                skin.id != baseName -> rejections += SkinRejection(asset.name, "id '${skin.id}' does not match file name")
                !seen.add(skin.id) -> rejections += SkinRejection(asset.name, "duplicate skin id '${skin.id}'")
                else -> accepted += skin
            }
        }

        val default = accepted.firstOrNull { it.id == fallback.id } ?: fallback
        val ordered = buildList {
            add(default)
            addAll(accepted.filter { it.id != default.id }.sortedBy { it.id })
        }
        return SkinCatalog(ordered, default, rejections)
    }
}
