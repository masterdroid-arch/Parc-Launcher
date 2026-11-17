package com.brightblu.parc

import com.brightblu.parc.arcade.ArcadePage

/** App-owned, framework-free configuration; stable ids also form the persistence format. */
internal object HubCatalog {
    /**
     * T263: the page merges. Old ids stay readable forever: a saved order, a remembered page, a
     * focus mode or a backup that names one of them resolves to the merged page.
     */
    val aliases: Map<String, String> = linkedMapOf(
        "clock" to TodayPage.Id,
        "system" to DevicePage.Id,
        "power" to DevicePage.Id,
        "network" to DevicePage.Id,
        "sound" to ControlsPage.Id,
        QuickToggleCatalog.PageId to ControlsPage.Id,
        "quick_toggles" to ControlsPage.Id,
        QuotesPage.Id to CommonplacePage.Id,
    )
    fun canonicalPage(page: String): String = aliases[page] ?: page
    fun canonicalPages(pages: Iterable<String>): Set<String> = pages.map(::canonicalPage).toSet()

    /** T137: Weather exists only in the weather variant (after Today); other builds never list it. */
    val pages = listOf(TodayPage.Id) + (if (WeatherVariant.Available) listOf(WeatherVariant.PageId) else emptyList()) + listOf(DevicePage.Id, "apps", StepsPage.Id, RhythmPage.Id, SkyPage.Id, YearPage.Id, RecordsPage.Id, CommonplacePage.Id, ControlsPage.Id, "timers", "agenda", "media", "focus", "notes", "comms", LibraryPage.Id) + PersonalExtras.pages.map { it.id } + ArcadePage.Id
    /** Pages that start hidden, for new users and when an older saved config first meets them. */
    val defaultHiddenPages = setOf("notes", ControlsPage.Id, WeatherVariant.PageId, StepsPage.Id, RhythmPage.Id, SkyPage.Id, YearPage.Id, RecordsPage.Id, CommonplacePage.Id, LibraryPage.Id) + PersonalExtras.pages.map { it.id }
    /**
     * Pages that are one body with no tile list to configure (the tile switches are not shown for them, and an
     * empty tile list is not "no tiles shown"). Every M13 page that draws itself is listed here.
     */
    val tilelessPages = setOf(TodayPage.Id, "comms", "focus", "notes", WeatherVariant.PageId, StepsPage.Id, RhythmPage.Id, SkyPage.Id, YearPage.Id, RecordsPage.Id, CommonplacePage.Id, LibraryPage.Id, ArcadePage.Id) + PersonalExtras.pages.map { it.id }
    /** Pages added before 1.5.0 (M13); their place in an old saved order is decided by their position in [pages]. */
    val m13Pages = setOf(TodayPage.Id, StepsPage.Id, RhythmPage.Id, SkyPage.Id, YearPage.Id, RecordsPage.Id, CommonplacePage.Id, LibraryPage.Id, ArcadePage.Id) + PersonalExtras.pages.map { it.id } // T198: pages only some builds carry count as new pages too; T200: so does the arcade, which no config lists until a game is unlocked
    /**
     * T158: the tabs a user with no saved choice sees, in this order; every other page starts hidden, after them.
     * T219: Focus is not auto-pinned. The Focus chip is its front door, so a new user is not left without one.
     * T263: Clock and Power no longer exist; Today and Device take their places in the same two slots.
     */
    val defaultTabs = listOf(TodayPage.Id, DevicePage.Id, "notes")
    // T200: the arcade is not hidden by default: it is invisible until a game is unlocked, and from then on it is an ordinary visible tab.
    val newUserHiddenPages: Set<String> = (pages - defaultTabs.toSet() - ArcadePage.Id).toSet()
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
    val defaultHidden = mapOf(DevicePage.Id to (systemHistoryTiles.keys + powerHistoryTiles.keys).toSet())
    // Legacy page tile lists stay here for the one-way migration and for the old tests that still name them.
    val systemTiles = listOf("battery", "thermal", "ram", "storage", "network", "wifi", "uptime") + systemHistoryTiles.keys
    val powerTiles = listOf("charging", "time to full", "health and cycles", "battery temperature", "screen on today") + powerHistoryTiles.keys
    val networkTiles = listOf("traffic", "data totals", "top apps", "wifi link", "network type")
    val appsTiles = listOf("screen time", "new apps", "biggest apps")
    /**
     * T263: Device is one page with four fixed sections. Every id appears once; the old three pages'
     * tiles map into these by [legacyTile], so nothing they showed is lost and nothing is drawn twice.
     */
    val deviceSections = linkedMapOf(
        "Power" to listOf("battery", "health and cycles", "battery history"),
        "Network" to listOf("traffic", "data totals", "top apps", "wifi link", "network type", "received history", "sent history"),
        "Storage" to listOf("storage", "storage history", "ram", "ram history", "uptime"),
        "Thermal" to listOf("thermal", "battery temperature"),
    )
    val deviceTiles = deviceSections.values.flatten()
    val soundTiles = listOf("media", "ring", "alarm", "call", "output")
    val controlsSections = linkedMapOf("Volume" to soundTiles, "Quick toggles" to QuickToggleCatalog.tiles)
    val controlsTiles = controlsSections.values.flatten()
    val timersTiles = listOf("timers", "stopwatch", "next alarm")
    val agendaTiles = listOf("next events")
    val mediaTiles = listOf("now playing")
    /**
     * T185/T263: tile ids older builds saved, mapped to the tile that replaced them. Network
     * "today totals" became "data totals"; the two Apps screen-time tiles became one. The old
     * System/Power/Network duplicates fold into one Device tile.
     */
    val legacyTiles: Map<String, Map<String, String>> = mapOf(
        "apps" to mapOf("screen time today" to "screen time", "screen time 7 days" to "screen time"),
        "network" to mapOf(
            "today totals" to "data totals",
            "traffic" to "traffic", "data totals" to "data totals", "top apps" to "top apps",
            "wifi link" to "wifi link", "network type" to "network type",
        ),
        "system" to mapOf(
            "battery" to "battery", "thermal" to "thermal", "ram" to "ram", "storage" to "storage",
            "network" to "traffic", "wifi" to "wifi link", "uptime" to "uptime",
            "ram history" to "ram history", "storage history" to "storage history",
            "received history" to "received history", "sent history" to "sent history",
        ),
        "power" to mapOf(
            "charging" to "battery", "time to full" to "battery", "screen on today" to "battery",
            "health and cycles" to "health and cycles", "battery temperature" to "battery temperature",
            "battery history" to "battery history", "temperature history" to "battery temperature",
        ),
    )
    fun currentTile(page: String, tile: String): String = legacyTiles[page]?.get(tile) ?: tile
    fun tiles(page: String): List<String> = when (page) {
        DevicePage.Id -> deviceTiles
        "apps" -> appsTiles
        ControlsPage.Id -> controlsTiles
        "timers" -> timersTiles
        "agenda" -> agendaTiles
        "media" -> mediaTiles
        else -> emptyList()
    }

    /** The section a Device tile belongs to, or null for a page without Device's sections. */
    fun deviceSection(tile: String): String? = deviceSections.entries.firstOrNull { tile in it.value }?.key
    /** The section a Controls tile belongs to, or null. */
    fun controlsSection(tile: String): String? = controlsSections.entries.firstOrNull { tile in it.value }?.key
}

internal data class HubConfig(
    val size: HubSize = HubSize.Standard,
    val clock: ClockOptions = ClockOptions(),
    val hiddenPages: Set<String> = HubCatalog.newUserHiddenPages,
    val pageOrder: List<String> = HubCatalog.newUserOrder,
    val tileOrder: Map<String, List<String>> = emptyMap(),
    val hiddenTiles: Map<String, Set<String>> = HubCatalog.defaultHidden,
    /** T200: true once an arcade game is unlocked (read from the arcade state, never saved in the page document). Until then the arcade page does not exist. */
    val arcadeOpen: Boolean = false,
    /** T258: pages that keep their own size. A page not listed here uses [size], the default for every page. */
    val pageSizes: Map<String, HubSize> = emptyMap(),
) {
    /** The size this page is drawn at: its own when set, else the default. A null page (none shown) uses the default. */
    fun sizeFor(page: String?): HubSize {
        val id = page?.let(HubCatalog::canonicalPage)
        return pageSizes[id] ?: size
    }
    /** Give [page] its own size, or hand it back to the default with a null [own]. */
    fun withPageSize(page: String, own: HubSize?): HubConfig {
        val id = HubCatalog.canonicalPage(page)
        if (id !in HubCatalog.pages) return this
        return copy(pageSizes = if (own == null) pageSizes - id else pageSizes + (id to own))
    }
    /** Every catalog page in the saved order, the arcade included whether or not it is unlocked (this is what is saved). */
    private val allPages get() = (pageOrder.filter { it in HubCatalog.pages } + HubCatalog.pages).distinct()
    val orderedPages get() = allPages.filter { it != ArcadePage.Id || arcadeOpen }
    val pages get() = orderedPages.filterNot { it in hiddenPages }
    fun movePage(page: String, delta: Int): HubConfig {
        val id = HubCatalog.canonicalPage(page)
        val order = orderedPages.toMutableList()
        val from = order.indexOf(id)
        if (from < 0) return this
        order.add((from + delta).coerceIn(0, order.lastIndex), order.removeAt(from))
        return copy(pageOrder = order)
    }
    /** T186: put [page] at [toIndex] of the full ordered list (hidden pages included); hidden pages are moved over like any other. */
    fun movePageTo(page: String, toIndex: Int): HubConfig {
        val id = HubCatalog.canonicalPage(page)
        val order = orderedPages
        if (id !in order) return this
        return copy(pageOrder = HubReorder.moveTo(order, id, toIndex))
    }
    /** T186: the visible tabs were reordered to [newVisible]; hidden pages (and pages not in [visible]) keep their places. */
    fun reorderVisible(visible: List<String>, newVisible: List<String>): HubConfig =
        copy(pageOrder = HubReorder.reorderVisible(orderedPages, visible, newVisible))
    fun tiles(page: String): List<String> {
        val id = HubCatalog.canonicalPage(page)
        val known = HubCatalog.tiles(id)
        return (tileOrder[id].orEmpty().filter { it in known } + known).distinct()
    }
    fun showPage(page: String, show: Boolean): HubConfig {
        val id = HubCatalog.canonicalPage(page)
        if (id !in HubCatalog.pages) return this
        return copy(hiddenPages = if (show) hiddenPages - id else hiddenPages + id)
    }
    fun showTile(page: String, tile: String, show: Boolean): HubConfig {
        val id = HubCatalog.canonicalPage(page)
        if (tile !in HubCatalog.tiles(id)) return this
        val hidden = hiddenTiles[id].orEmpty()
        return copy(hiddenTiles = hiddenTiles + (id to if (show) hidden - tile else hidden + tile))
    }
    fun move(page: String, tile: String, delta: Int): HubConfig {
        val page = HubCatalog.canonicalPage(page)
        val order = tiles(page).toMutableList()
        val from = order.indexOf(tile)
        if (from < 0) return this
        val to = (from + delta).coerceIn(0, order.lastIndex)
        order.add(to, order.removeAt(from))
        return copy(tileOrder = tileOrder + (page to order))
    }
    fun encode(): String = buildList {
        allPages.forEach { page ->
            add("$page:${page !in hiddenPages}:${tiles(page).joinToString(",")}:${tiles(page).filter { it in hiddenTiles[page].orEmpty() }.joinToString(",")}")
        }
    }.let { rows ->
        // T258: own sizes ride in one extra row. Older readers skip it (it does not have four parts).
        val sizes = allPages.filter { it in pageSizes }.joinToString(",") { "$it=${pageSizes.getValue(it).id}" }
        if (sizes.isEmpty()) rows else rows + "~sizes:$sizes"
    }.joinToString(";")

    companion object {
        internal const val SizesRow = "~sizes:"

        /** Unknown pages, unknown sizes and malformed pairs are dropped; the rest is kept. */
        internal fun decodeSizes(raw: String): Map<String, HubSize> {
            val row = raw.split(';').firstOrNull { it.startsWith(SizesRow) } ?: return emptyMap()
            val result = linkedMapOf<String, HubSize>()
            row.removePrefix(SizesRow).split(',').forEach { pair ->
                val parts = pair.split('=')
                val page = parts.getOrNull(0)?.let(HubCatalog::canonicalPage) ?: return@forEach
                val size = HubSize.entries.firstOrNull { it.id == parts.getOrNull(1) }
                if (parts.size == 2 && page in HubCatalog.pages && size != null) result.putIfAbsent(page, size)
            }
            return result
        }

        /**
         * Pages the saved document does not know (added by a later version) join at their catalog
         * position: before the first following catalog page the user already has, else at the end.
         */
        internal fun withNewPages(saved: List<String>): List<String> {
            val result = saved.map(HubCatalog::canonicalPage).filter { it in HubCatalog.pages }.distinct().toMutableList()
            HubCatalog.pages.forEachIndexed { index, page ->
                if (page in result) return@forEachIndexed
                val anchor = HubCatalog.pages.drop(index + 1).firstOrNull { it in result }
                if (anchor == null) result.add(page) else result.add(result.indexOf(anchor), page)
            }
            return result
        }

        fun decode(raw: String): HubConfig = decodeRows(raw).copy(pageSizes = decodeSizes(raw))

        private data class SavedRow(val rawPage: String, val page: String, val shown: Boolean,
            val shownTiles: List<String>, val hiddenTiles: List<String>)

        private fun decodeRows(raw: String): HubConfig {
            val rows = raw.split(';').mapNotNull { row ->
                val parts = row.split(':')
                if (parts.size != 4) return@mapNotNull null
                val page = HubCatalog.canonicalPage(parts[0])
                if (page !in HubCatalog.pages) return@mapNotNull null
                SavedRow(parts[0], page, parts[1] != "false", parts[2].split(','), parts[3].split(','))
            }
            // T158: no saved choice (never written, empty or unreadable) gets the default tabs. A document
            // with any saved row keeps exactly its pages, order and hidden flags; only pages it has never met are added.
            if (rows.isEmpty()) return HubConfig()
            // T263: a merged page takes the first place of any page it replaces. Every source row's
            // visibility is kept: the merged page is shown when at least one old page was shown.
            val order = withNewPages(rows.map { it.page })
            var result = HubConfig(pageOrder = order, hiddenPages = HubCatalog.defaultHiddenPages)
            rows.groupBy { it.page }.forEach { (page, sources) ->
                result = result.showPage(page, sources.any { it.shown })
                val shownTiles = sources.flatMap { source -> source.shownTiles.map { HubCatalog.currentTile(source.rawPage, it) } }
                    .filter { it in HubCatalog.tiles(page) }.distinct()
                result = result.copy(tileOrder = result.tileOrder + (page to shownTiles))
                // The old format lists every configured tile as shown and repeats the hidden ones in the
                // fourth field. A tile that folded several old ids is hidden only when every old page
                // that had it hid it; one page showing it keeps it visible.
                val occurrences = sources.flatMap { source ->
                    (source.shownTiles + source.hiddenTiles).filter { it.isNotEmpty() }.distinct().map { raw ->
                        HubCatalog.currentTile(source.rawPage, raw) to (raw in source.hiddenTiles)
                    }
                }.filter { it.first in HubCatalog.tiles(page) }
                occurrences.groupBy { it.first }.forEach { (tile, votes) ->
                    result = result.showTile(page, tile, !votes.all { it.second })
                }
            }
            return result
        }
    }
}

/** The page's name on screen. Ids stay lowercase because they are the stored format. */
internal fun hubPageName(page: String): String = when (val id = HubCatalog.canonicalPage(page)) {
    else -> when {
    extraPage(id) != null -> extraPage(id)!!.title
    // T220: the displayed page names are explicit so the tab, template, search and docs cannot drift.
    id == TodayPage.Id -> "Today"
    id == WeatherVariant.PageId -> "Weather"
    id == DevicePage.Id -> "Device"
    id == "apps" -> "Apps"
    id == StepsPage.Id -> "Steps"
    id == RhythmPage.Id -> "Rhythm"
    id == SkyPage.Id -> "Sky"
    id == YearPage.Id -> "Year"
    id == RecordsPage.Id -> "Records"
    id == CommonplacePage.Id -> "Commonplace"
    id == ControlsPage.Id -> "Controls"
    id == "timers" -> "Timers"
    id == "agenda" -> "Agenda"
    id == "media" -> "Media"
    id == "focus" -> "Focus"
    id == "notes" -> "Notes"
    id == "comms" -> "Messages"
    id == LibraryPage.Id -> "Library"
    id == com.brightblu.parc.arcade.ArcadePage.Id -> "Arcade"
    else -> id.replaceFirstChar { it.uppercase() }
    }
}

/**
 * M13 review: a page the user hid (or a Focus mode filtered out) can still be shown for one visit, for example the Notes
 * page when a reminder is tapped. [forced] is placed where [ordered] puts it. A null [forced] changes nothing.
 */
internal fun withForcedHubPage(pages: List<String>, forced: String?, ordered: List<String>): List<String> {
    if (forced == null || forced in pages || forced !in ordered) return pages
    val keep = pages.toSet() + forced
    return ordered.filter { it in keep }
}

/** T263: an old or unknown remembered page returns to its merged page, else to the first visible page (Device). */
internal fun restoredHubPage(saved: String, config: HubConfig): String {
    val id = HubCatalog.canonicalPage(saved)
    return id.takeIf { it in config.pages } ?: DevicePage.Id.takeIf { it in config.pages }
        ?: config.pages.firstOrNull() ?: TodayPage.Id
}

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
 * T158: the pure side of the tab strip. The visible pages are the tabs, in the saved order. The default tabs
 * lead for a new user; a page an older config enabled (Timers, Agenda, Comms ...) is an extra tab that stays where
 * the user saved it.
 */
internal object HubTabs {
    /** How long the strip waits for a tapped page to become selected before it stops waiting. */
    const val CentreGiveUpMs = 1500L
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

    /**
     * T240: where a tapped pill's name sits in the middle of the strip, clamped to the content's real scroll
     * range. [maxScroll] comes from the live ScrollState; it is 0 while the names already fit.
     */
    fun centreTarget(pillStart: Float, pillWidth: Float, viewWidth: Float, maxScroll: Float): Float {
        if (!pillStart.isFinite() || !pillWidth.isFinite() || !viewWidth.isFinite() || !maxScroll.isFinite()) return 0f
        if (pillWidth <= 0f || viewWidth <= 0f || maxScroll <= 0f) return 0f
        val centred = pillStart + pillWidth / 2f - viewWidth / 2f
        return centred.coerceIn(0f, maxScroll)
    }
}

/**
 * T186: the pure side of dragging pages into a new order. Nothing here knows about Compose; the tab strip and the
 * settings list feed it measured positions and sizes (pixels, any one axis).
 */
internal object HubReorder {
    /** [page] moved to [toIndex] (clamped); other ids keep their relative order. Unknown pages change nothing. */
    fun moveTo(order: List<String>, page: String, toIndex: Int): List<String> {
        val from = order.indexOf(page)
        if (from < 0) return order
        val result = order.toMutableList()
        result.removeAt(from)
        result.add(toIndex.coerceIn(0, result.size), page)
        return result
    }

    /**
     * The visible pages of [order] take the sequence [newVisible]; every other page (hidden, or not shown now) stays in its
     * exact slot. Ids in [newVisible] that are not visible are ignored; a visible page [newVisible] leaves out goes to the end
     * of the visible sequence in its old order.
     */
    fun reorderVisible(order: List<String>, visible: List<String>, newVisible: List<String>): List<String> {
        val slots = order.toSet().let { known -> visible.filter { it in known }.toSet() }
        val wanted = (newVisible.filter { it in slots } + visible.filter { it in slots }).distinct().iterator()
        return order.map { if (it in slots && wanted.hasNext()) wanted.next() else it }
    }

    /** Where a dragged item, moved [offset] px from its resting place, would be inserted among the others (0..n-1). */
    fun insertIndex(starts: FloatArray, sizes: FloatArray, from: Int, offset: Float): Int {
        if (from !in starts.indices) return from
        val centre = starts[from] + sizes[from] / 2f + offset
        return starts.indices.filter { it != from }.count { starts[it] + sizes[it] / 2f < centre }
    }

    /** The start of every item (by original index) once [from] sits at [to]; the gap between items is kept as measured. */
    fun reflow(starts: FloatArray, sizes: FloatArray, from: Int, to: Int): FloatArray {
        val n = starts.size
        if (n == 0 || from !in 0 until n) return starts.copyOf()
        val gap = if (n > 1) ((starts[n - 1] + sizes[n - 1] - starts[0] - sizes.sum()) / (n - 1)).coerceAtLeast(0f) else 0f
        val order = (0 until n).toMutableList().also { it.removeAt(from); it.add(to.coerceIn(0, n - 1), from) }
        val result = FloatArray(n)
        var x = starts[0]
        order.forEach { result[it] = x; x += sizes[it] + gap }
        return result
    }

    /** px to scroll this frame (negative is towards the start) when the dragged item [start]..[end] nears a view edge. */
    fun edgeScroll(start: Float, end: Float, viewStart: Float, viewEnd: Float, zone: Float, maxStep: Float): Float {
        if (zone <= 0f) return 0f
        val toStart = start - viewStart
        val toEnd = viewEnd - end
        return when {
            toStart < zone -> -maxStep * ((zone - toStart) / zone).coerceIn(0f, 1f)
            toEnd < zone -> maxStep * ((zone - toEnd) / zone).coerceIn(0f, 1f)
            else -> 0f
        }
    }
}
