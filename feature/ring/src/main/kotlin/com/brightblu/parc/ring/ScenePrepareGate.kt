package com.brightblu.parc.ring

/**
 * OPUS2 (replaces the T83 token gate): scene preparation runs one at a time, newest request next.
 *
 * T83 started a new background preparation for every new state and published only the newest. On a
 * phone with ~180 apps the states kept coming (one per loaded icon), so preparations piled up in
 * parallel, each rasterising every slot, the process grew by about a gigabyte, and none of them was
 * ever the newest when it finished: the All-apps scene never appeared.
 *
 * Now at most one preparation is in flight. A request that arrives meanwhile replaces any waiting
 * one (intermediate states are skipped, never queued), and the worker picks it up as soon as the
 * running one ends. Every finished preparation is newer than what is on screen, so it is published;
 * the screen always makes progress. All calls happen on the main thread.
 */
internal class ScenePrepareQueue<T : Any> {
    private var waiting: T? = null
    private var warming: T? = null
    private var running = false

    /** Stores [request] as the next one. True when the caller must start a worker. */
    fun offer(request: T): Boolean {
        waiting = request
        return start()
    }

    /**
     * OPUS3: a background "warm-up" preparation (the All-apps ring prepared while Home is shown, so
     * its rasters are ready before the hub is tapped). It never replaces or overtakes a real
     * request: the worker takes it only when no real request is waiting, and its result is not
     * shown. A newer warm-up replaces an older one that has not started.
     */
    fun offerWarm(request: T): Boolean {
        warming = request
        return start()
    }

    private fun start(): Boolean {
        if (running) return false
        running = true
        return true
    }

    /** The next real request for the worker, then a warm-up; null (and the worker stops) when idle. */
    fun take(): T? = takeNext()?.first

    /** Like [take], and says whether the request is a warm-up (true) whose result is not shown. */
    fun takeNext(): Pair<T, Boolean>? {
        waiting?.let { waiting = null; return it to false }
        warming?.let { warming = null; return it to true }
        running = false
        return null
    }

    /** True while a worker owns the queue. */
    val busy: Boolean get() = running
}

/**
 * OPUS3: slot rasters per scene family (Home, All apps, one folder...), so preparing one scene does
 * not throw away the rasters of another that will be shown again soon. At most [limit] families
 * are kept; the least recently used is dropped.
 */
internal class RasterFamilies<F : Any, R : Any>(private val limit: Int, private val make: () -> R) {
    private val families = object : LinkedHashMap<F, R>(limit, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<F, R>?): Boolean = size > limit
    }

    @Synchronized
    fun get(family: F): R = families.getOrPut(family, make)

    @Synchronized
    fun keys(): Set<F> = families.keys.toSet()
}

/**
 * OPUS2: reuses slot rasters (icon discs, label pills, folder captions) between preparations of
 * the same look, so a rebuilt scene (a new minute, an unread mark, a turned ring) does not
 * re-rasterise every slot. Only entries used by the latest preparation are kept, so the cache never
 * holds more than one scene's rasters. Used by the single preparation worker only.
 */
internal class RasterReuse<K : Any, V : Any> {
    private var style: Any? = null
    private val entries = HashMap<K, V>()
    private val used = HashSet<K>()

    /** Starts a preparation; a different [style] (skin, look, density, size) drops everything. */
    fun begin(style: Any) {
        if (style != this.style) { entries.clear(); this.style = style }
        used.clear()
    }

    fun get(key: K, make: () -> V): V {
        used += key
        return entries.getOrPut(key, make)
    }

    /** Ends a preparation: entries the scene did not use are released. */
    fun end() { entries.keys.retainAll(used) }

    val size: Int get() = entries.size
}

/**
 * OPUS3: when a background-prepared ("warmed") scene may stand in for the requested one until the
 * exact one is ready. Only for the same layer and folder, with the same skin and look, when the
 * screen is not already showing that layer, and never while search or an editor covers the ring
 * (the exact scene follows within moments and replaces it).
 */
internal object WarmStandIn {
    fun usable(warmed: com.brightblu.parc.model.RingState, requested: com.brightblu.parc.model.RingState,
        shown: com.brightblu.parc.model.RingState?, sameSkin: Boolean, sameLook: Boolean): Boolean =
        sameSkin && sameLook && !requested.searchOpen &&
            warmed.layer == requested.layer &&
            warmed.arcs.firstOrNull()?.folderId == requested.arcs.firstOrNull()?.folderId &&
            (shown == null || shown.layer != requested.layer)
}
