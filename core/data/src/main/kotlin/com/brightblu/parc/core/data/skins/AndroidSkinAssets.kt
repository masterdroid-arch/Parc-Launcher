package com.brightblu.parc.core.data.skins

import android.content.Context

/**
 * Reads the authored skin files from `assets/skins/` (DESIGN §2.8). The read is small (two files) but
 * is still disk work, so a host that cannot afford it on the cold-start path can hand its own
 * [SkinAsset] list to [SkinCatalogLoader.parse] instead.
 */
object AndroidSkinAssets {

    /** Asset directory holding the skin JSON files. */
    const val Directory = "skins"

    /** All `*.json` files in [Directory], name-sorted for deterministic order; never throws for an absent directory. */
    fun read(context: Context): List<SkinAsset> {
        val assets = context.assets
        val names = assets.list(Directory)
            ?.filter { it.endsWith(".json") }
            ?.sorted()
            ?: emptyList()
        return names.map { name ->
            val json = assets.open("$Directory/$name").bufferedReader().use { it.readText() }
            SkinAsset(name, json)
        }
    }
}
