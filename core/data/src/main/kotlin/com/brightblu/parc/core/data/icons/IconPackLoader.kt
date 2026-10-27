package com.brightblu.parc.core.data.icons

import com.brightblu.parc.core.data.ByteLruCache

/**
 * Applies the selected icon pack, caches pack bitmaps, and falls back to the app's own icon.
 *
 * Pure with respect to Android: the source and the fallback are injected, and [B] is the bitmap
 * type, so the whole lookup/cache/fallback path is exercised by JVM unit tests. Thread-safe: the
 * icon cache calls it from a background dispatcher.
 */
internal class IconPackLoader<B : Any>(
    private val source: IconPackSource<B>,
    private val sizeOf: (B) -> Int,
    private val fallback: (component: String, sizePx: Int) -> B?,
    private val logWarn: (String) -> Unit = {},
    maxBytes: Int = DefaultMaxBytes,
) {
    private data class Key(val pack: String, val drawable: String, val sizePx: Int)

    private val bitmaps = ByteLruCache<Key, B>(maxBytes, sizeOf)

    /** Pack filters are loaded once and dropped by [clear]; a stale empty map is never re-read. */
    private val filters = HashMap<String, Map<String, String>>()
    private val lock = Any()

    /**
     * The pack icon for [component] at [sizePx], or the app's own icon when no pack is selected, the
     * pack has no entry for the component, the entry's drawable is missing, or reading the pack
     * fails. Never throws.
     */
    fun iconFor(component: String, sizePx: Int, selectedPack: String?): B? {
        val pack = IconPackRules.normalizeSelection(selectedPack)
        if (pack != null) {
            val key = AppFilterParser.normalizeComponent(component)
            val drawable = key?.let { filterFor(pack)[it] }
            if (drawable != null) {
                val cacheKey = Key(pack, drawable, sizePx)
                bitmaps.get(cacheKey)?.let { return it }
                val bitmap = try {
                    source.drawable(pack, drawable, sizePx)
                } catch (e: Exception) {
                    logWarn("icon pack drawable unreadable")
                    null
                }
                if (bitmap != null) {
                    bitmaps.put(cacheKey, bitmap)
                    return bitmap
                }
            }
        }
        return try {
            fallback(component, sizePx)
        } catch (e: Exception) {
            logWarn("app icon fallback failed")
            null
        }
    }

    /** Drops every cached pack bitmap and every parsed filter; the next lookup reads again. */
    fun clear() {
        synchronized(lock) { filters.clear() }
        bitmaps.clear()
    }

    internal fun cachedBytes(): Int = bitmaps.size()

    private fun filterFor(pack: String): Map<String, String> {
        synchronized(lock) { filters[pack]?.let { return it } }
        val loaded = try {
            source.appFilter(pack)
        } catch (e: Exception) {
            logWarn("icon pack filter unreadable")
            emptyMap()
        }
        synchronized(lock) { filters[pack] = loaded }
        return loaded
    }

    companion object {
        /**
         * Pack bitmaps duplicate what the app-icon cache already holds, so this budget is smaller.
         * One 112 px ARGB bitmap is about 48 KB; 4 MB holds a full phone's worth of icon packs.
         */
        const val DefaultMaxBytes = 4 * 1024 * 1024
    }
}
