package com.brightblu.parc.core.data.skins

import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.SkinJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * User-made skins as one JSON file each (`custom_<slug>.json`) in [dir] (T85). [skins] always mirrors the
 * valid files, sorted by name; a corrupt or foreign file is ignored, never fatal. Writes are atomic
 * (temp file then rename). Thread-safe: mutations are synchronised and the flow is a snapshot.
 */
class CustomSkinStore(private val dir: File) {
    private val lock = Any()
    private val state = MutableStateFlow(load())
    val skins: StateFlow<List<Skin>> = state.asStateFlow()

    fun ids(): Set<String> = state.value.mapTo(mutableSetOf()) { it.id }

    /** Writes or replaces [skin]; false when the id is not a custom id or the file cannot be written. */
    fun save(skin: Skin): Boolean = synchronized(lock) {
        if (!SkinNaming.isCustomId(skin.id) || !SkinNaming.isValidName(skin.name)) return false
        val ok = runCatching {
            dir.mkdirs()
            val tmp = File(dir, "${skin.id}.tmp")
            tmp.writeText(SkinJson.encode(skin))
            val target = File(dir, "${skin.id}.json")
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        }.isSuccess
        if (ok) state.value = load()
        ok
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        if (!SkinNaming.isCustomId(id)) return false
        val removed = File(dir, "$id.json").delete()
        state.value = load()
        removed
    }

    private fun load(): List<Skin> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching { SkinJson.decode(f.readText()) }.getOrNull()
                ?.takeIf { SkinNaming.isCustomId(it.id) && f.name == "${it.id}.json" && SkinNaming.isValidName(it.name) }
        }.sortedBy { it.name.lowercase() }
    }
}
