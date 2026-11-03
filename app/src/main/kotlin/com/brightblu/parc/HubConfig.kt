package com.brightblu.parc

/** App-owned, framework-free configuration; stable ids also form the persistence format. */
internal object HubCatalog {
    /** T137: Weather exists only in the weather variant (after Clock); other builds never list it. */
    val pages = listOf("clock") + (if (WeatherVariant.Available) listOf(WeatherVariant.PageId) else emptyList()) + listOf("system", "power", "network", "apps", "sound", QuickToggleCatalog.PageId, "timers", "agenda", "media", "focus", "notes", "comms")
    /** Pages that start hidden, for new users and when an older saved config first meets them. */
    val defaultHiddenPages = setOf("notes", QuickToggleCatalog.PageId, WeatherVariant.PageId)
    /** T158: the tabs a user with no saved choice sees, in this order; every other page starts hidden, after them. */
    val defaultTabs = listOf("clock", "power", "focus", "notes")
    val newUserHiddenPages: Set<String> = (pages - defaultTabs.toSet()).toSet()
    val newUserOrder: List<String> = defaultTabs.filter { it in pages } + pages.filterNot { it in defaultTabs }
    val systemHistoryTiles = linkedMapOf(
        "ram history" to com.brightblu.parc.core.data.stats.StatsArchiveMetric.RamPercent,
        "storage history" to com.brightblu.parc.core.data.stats.StatsArchiveMetric.StorageUsedPercent,
        "received history" to com.brightblu.parc.core.data.stats.StatsArchiveMetric.NetworkRxTotalBytes,
        "sent history" to com.brightblu.parc.core.data.stats.StatsArchiveMetric.NetworkTxTotalBytes,
    )
    val powerHistoryTiles = linkedMapOf(
        "battery history" to com.brightblu.parc.core.data.stats.StatsArchiveMetric.BatteryPercent,
        "temperature history" to com.brightblu.parc.core.data.stats.StatsArchiveMetric.BatteryTemperature,
    )
    val defaultHidden = mapOf("system" to systemHistoryTiles.keys.toSet(), "power" to powerHistoryTiles.keys.toSet())
    val systemTiles = listOf("battery", "thermal", "ram", "storage", "network", "wifi", "uptime") + systemHistoryTiles.keys
    val powerTiles = listOf("charging", "time to full", "health and cycles", "battery temperature", "screen on today") + powerHistoryTiles.keys
    val networkTiles = listOf("traffic", "today totals", "top apps", "wifi link", "network type")
    val appsTiles = listOf("screen time today", "screen time 7 days", "new apps", "biggest apps")
    val soundTiles = listOf("media", "ring", "alarm", "call", "output")
    val timersTiles = listOf("timers", "stopwatch", "next alarm")
    val agendaTiles = listOf("next events")
    val mediaTiles = listOf("now playing")
    fun tiles(page: String): List<String> = when (page) {
        "system" -> systemTiles
        "power" -> powerTiles
        "network" -> networkTiles
        "apps" -> appsTiles
        "sound" -> soundTiles
        "timers" -> timersTiles
        "agenda" -> agendaTiles
        "media" -> mediaTiles
        QuickToggleCatalog.PageId -> QuickToggleCatalog.tiles
        else -> emptyList()
    }
}

internal data class HubConfig(
    val size: HubSize = HubSize.Standard,
    val clock: ClockOptions = ClockOptions(),
    val hiddenPages: Set<String> = HubCatalog.newUserHiddenPages,
    val pageOrder: List<String> = HubCatalog.newUserOrder,
    val tileOrder: Map<String, List<String>> = emptyMap(),
    val hiddenTiles: Map<String, Set<String>> = HubCatalog.defaultHidden,
) {
    val orderedPages get() = (pageOrder.filter { it in HubCatalog.pages } + HubCatalog.pages).distinct()
    val pages get() = orderedPages.filterNot { it in hiddenPages }
    fun movePage(page: String, delta: Int): HubConfig {
        val order = orderedPages.toMutableList()
        val from = order.indexOf(page)
        if (from < 0) return this
        order.add((from + delta).coerceIn(0, order.lastIndex), order.removeAt(from))
        return copy(pageOrder = order)
    }
    fun tiles(page: String): List<String> {
        val known = HubCatalog.tiles(page)
        return (tileOrder[page].orEmpty().filter { it in known } + known).distinct()
    }
    fun showPage(page: String, show: Boolean): HubConfig = if (page !in HubCatalog.pages) this else
        copy(hiddenPages = if (show) hiddenPages - page else hiddenPages + page)
    fun showTile(page: String, tile: String, show: Boolean): HubConfig {
        if (tile !in HubCatalog.tiles(page)) return this
        val hidden = hiddenTiles[page].orEmpty()
        return copy(hiddenTiles = hiddenTiles + (page to if (show) hidden - tile else hidden + tile))
    }
    fun move(page: String, tile: String, delta: Int): HubConfig {
        val order = tiles(page).toMutableList()
        val from = order.indexOf(tile)
        if (from < 0) return this
        val to = (from + delta).coerceIn(0, order.lastIndex)
        order.add(to, order.removeAt(from))
        return copy(tileOrder = tileOrder + (page to order))
    }
    fun encode(): String = buildList {
        orderedPages.forEach { page ->
            add("$page:${page !in hiddenPages}:${tiles(page).joinToString(",")}:${tiles(page).filter { it in hiddenTiles[page].orEmpty() }.joinToString(",")}")
        }
    }.joinToString(";")

    companion object {
        /**
         * Pages the saved document does not know (added by a later version) join at their catalog
         * position: before the first following catalog page the user already has, else at the end.
         */
        internal fun withNewPages(saved: List<String>): List<String> {
            val result = saved.toMutableList()
            HubCatalog.pages.forEachIndexed { index, page ->
                if (page in result) return@forEachIndexed
                val anchor = HubCatalog.pages.drop(index + 1).firstOrNull { it in result }
                if (anchor == null) result.add(page) else result.add(result.indexOf(anchor), page)
            }
            return result
        }

        fun decode(raw: String): HubConfig {
            val savedOrder = raw.split(';').map { it.split(':') }
                .filter { it.size == 4 && it[0] in HubCatalog.pages }.map { it[0] }.distinct()
            // T158: no saved choice (never written, empty or unreadable) gets the four default tabs. A document
            // with any saved row keeps exactly its pages, order and hidden flags; only pages it has never met are added.
            if (savedOrder.isEmpty()) return HubConfig()
            // Pre-clock documents gain Clock first, without un-hiding any saved page.
            val order = if ("clock" !in savedOrder) HubCatalog.pages else withNewPages(savedOrder)
            var result = HubConfig(pageOrder = order.distinct(), hiddenPages = HubCatalog.defaultHiddenPages)
            raw.split(';').forEach { row ->
                val parts = row.split(':')
                if (parts.size != 4 || parts[0] !in HubCatalog.pages) return@forEach
                val page = parts[0]
                result = result.showPage(page, parts[1] != "false")
                result = result.copy(tileOrder = result.tileOrder + (page to parts[2].split(',').filter { it in HubCatalog.tiles(page) }.distinct()))
                parts[2].split(',').forEach { result = result.showTile(page, it, it !in parts[3].split(',')) }
                parts[3].split(',').forEach { result = result.showTile(page, it, false) }
            }
            return result
        }
    }
}

/** The page's name on screen. Ids stay lowercase because they are the stored format. */
internal fun hubPageName(page: String): String =
    if (page == QuickToggleCatalog.PageId) "Quick toggles" else page.replaceFirstChar { it.uppercase() }

/** Hidden or unknown remembered pages always return to System. */
internal fun restoredHubPage(saved: String, config: HubConfig): String =
    saved.takeIf { it in config.pages } ?: "system"

/**
 * OPUS1: the page a horizontal swipe lands on. The visible pages form a loop: forward from the
 * last page goes to the first, back from the first goes to the last. One page stays put; an
 * unknown current page starts from the first. Order is exactly [pages] (the saved order).
 */
internal fun hubPageAfterSwipe(pages: List<String>, current: String?, forward: Boolean): String? {
    if (pages.isEmpty()) return null
    val index = pages.indexOf(current)
    if (index < 0) return pages.first()
    val step = if (forward) 1 else -1
    return pages[Math.floorMod(index + step, pages.size)]
}

/** Stable values: unknown or legacy expanded flags always resolve to today's Standard size. */
internal enum class HubSize(val id: String) {
    Compact("compact"), Standard("standard"), Tall("tall");
    fun next() = entries[(ordinal + 1) % entries.size]
    companion object { fun decode(raw: String?) = entries.firstOrNull { it.id == raw } ?: Standard }
}
internal val LocalHubSize = androidx.compose.runtime.compositionLocalOf { HubSize.Standard }

/**
 * T158: the pure side of the tab strip. The visible pages are the tabs, in the saved order. The four default tabs
 * lead for a new user; a page an older config enabled (Timers, Agenda, Comms ...) is an extra tab that stays where
 * the user saved it.
 */
internal object HubTabs {
    /** A pill's selected fill: `surface` with a share of `accent` mixed in, opaque. Colours are 0xAARRGGBB. */
    fun selectedFill(surface: Long, accent: Long, mix: Float = com.brightblu.parc.model.Metrics.hubTabSelectedMix): Long {
        fun channel(shift: Int): Long {
            val a = (surface shr shift) and 0xff
            val b = (accent shr shift) and 0xff
            return (a + (b - a) * mix).toLong().coerceIn(0L, 255L)
        }
        return 0xFF000000L or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
    /** Where the tab strip scrolls to (px from its start) so the selected pill is fully visible. */
    fun scrollTarget(pillStart: Float, pillWidth: Float, viewStart: Float, viewWidth: Float): Float = when {
        pillStart < viewStart -> pillStart
        pillStart + pillWidth > viewStart + viewWidth -> pillStart + pillWidth - viewWidth
        else -> viewStart
    }.coerceAtLeast(0f)
}
