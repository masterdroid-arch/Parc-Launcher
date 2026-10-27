package com.brightblu.parc.core.data

import com.brightblu.parc.model.AppId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Cache identity: an app at a specific raster size. */
internal data class IconKey(val id: AppId, val sizePx: Int)

/** Produces icon bitmaps. The only Android-facing part of the icon path; may be replaced in tests. */
internal interface IconProducer<B : Any> {
    suspend fun rasterise(id: AppId, sizePx: Int): B?
    suspend fun letterTile(label: String, sizePx: Int): B?
}

/** First letter used for a letter tile; '?' when the label has no visible character. */
internal fun firstLetter(label: String): Char = label.trim().firstOrNull()?.uppercaseChar() ?: '?'

/**
 * Non-blocking icon cache. get() never blocks: it returns null on a miss and schedules a
 * background load; loaded ids are announced through onLoaded so the UI can redraw. Bounded by
 * bytes. Duplicate requests for the same key are collapsed while in flight. Thread-safe.
 */
internal class IconCacheManager<B : Any>(
    private val producer: IconProducer<B>,
    private val sizeOf: (B) -> Int,
    private val labelOf: (AppId) -> String?,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val onLoaded: (AppId) -> Unit = {},
    private val logWarn: (String) -> Unit = {},
    maxBytes: Int = DefaultMaxBytes,
) {
    private val floorBytes = maxBytes
    private val cache = ByteLruCache<IconKey, B>(maxBytes, sizeOf)
    private val inFlight = mutableSetOf<IconKey>()
    private val lock = Any()

    /** Bumped by [clear]; a load started under an older generation is discarded and restarted. */
    private var generation = 0

    fun get(id: AppId, sizePx: Int): B? {
        val key = IconKey(id, sizePx)
        cache.get(key)?.let { return it }
        request(key)
        return null
    }

    /**
     * OPUS2: keeps the byte budget large enough for one icon per installed app at [sizePx].
     * A budget smaller than the set one screen needs makes the cache evict icons it is still
     * loading; every load then announced a new icon, the scene was rebuilt, asked for the evicted
     * icons again, and the loop never ended (180 apps at 144 px need about 15 MB; the old fixed
     * budget was 12 MB). Never shrinks below the initial budget.
     */
    fun fitAppCount(appCount: Int, sizePx: Int) {
        val wanted = IconCacheBudget.bytesFor(appCount, sizePx, floorBytes)
        if (wanted != cache.maxBytes()) cache.resize(wanted)
    }

    fun preload(ids: List<AppId>, sizePx: Int = DefaultSizePx) {
        ids.forEach { request(IconKey(it, sizePx)) }
    }

    @Synchronized
    internal fun cachedBytes(): Int = cache.size()

    @Synchronized
    internal fun cachedKeys(): Set<IconKey> = cache.keys()

    /**
     * Drops every cached bitmap after an icon-source change (an icon pack was selected) and returns
     * the ids that were cached, so the host can announce them and the UI asks again. In-flight loads
     * finish without caching and restart against the new source.
     */
    internal fun clear(): Set<AppId> = synchronized(lock) {
        val ids = cache.keys().mapTo(LinkedHashSet()) { it.id }
        generation++
        cache.clear()
        inFlight.clear()
        ids
    }

    private fun request(key: IconKey) {
        if (cache.get(key) != null) return
        val requestedGeneration = synchronized(lock) {
            if (!inFlight.add(key)) return
            generation
        }
        scope.launch(dispatcher) {
            val bitmap = load(key)
            val stale = synchronized(lock) {
                inFlight.remove(key)
                if (requestedGeneration != generation) {
                    true
                } else {
                    if (bitmap != null) cache.put(key, bitmap)
                    false
                }
            }
            if (stale) {
                request(key)
                return@launch
            }
            if (bitmap != null) onLoaded(key.id)
        }
    }

    private suspend fun load(key: IconKey): B? {
        val rasterised = try {
            producer.rasterise(key.id, key.sizePx)
        } catch (e: Exception) {
            logWarn("icon rasterise failed")
            null
        }
        if (rasterised != null) return rasterised
        return try {
            producer.letterTile(labelOf(key.id) ?: "", key.sizePx)
        } catch (e: Exception) {
            logWarn("letter tile failed")
            null
        }
    }

    internal companion object {
        const val DefaultSizePx = 112
        const val DefaultMaxBytes = 12 * 1024 * 1024
    }
}

/** OPUS2: pure budget rule for the launcher icon cache. */
object IconCacheBudget {
    /** Bytes of one ARGB_8888 icon raster of [sizePx] square. */
    fun iconBytes(sizePx: Int): Int = sizePx * sizePx * 4

    /** Headroom over one icon per app, for a second size or a burst during an icon-pack change. */
    const val Headroom = 1.25f

    /** Never more than this, whatever the app count (about 600 apps at 144 px with headroom). */
    const val CeilingBytes = 96 * 1024 * 1024

    fun bytesFor(appCount: Int, sizePx: Int, floorBytes: Int): Int {
        val wanted = (appCount.coerceAtLeast(0).toLong() * iconBytes(sizePx) * Headroom).toLong()
        return wanted.coerceIn(floorBytes.toLong(), maxOf(floorBytes, CeilingBytes).toLong()).toInt()
    }
}
