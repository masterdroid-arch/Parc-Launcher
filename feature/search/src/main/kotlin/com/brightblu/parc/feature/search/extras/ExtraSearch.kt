package com.brightblu.parc.feature.search.extras

import com.brightblu.parc.core.data.agenda.AgendaEvent

/** Which optional sources are switched on in Settings -> Search. A source also needs its permission. */
data class ExtraToggles(
    val phoneSettings: Boolean = true,
    val files: Boolean = true,
    val events: Boolean = true,
)

/** How many rows each source may contribute. */
data class ExtraCaps(
    val phoneSettings: Int = DefaultSettings,
    val files: Int = DefaultFiles,
    val events: Int = DefaultEvents,
) {
    companion object {
        const val DefaultSettings = 3
        const val DefaultFiles = 4
        const val DefaultEvents = 3
    }
}

/** One phone-settings result. */
data class PhoneSettingHit(val title: String, val action: String, val score: Double)

/** Kinds of file the sheet can open. */
enum class FileKind { Image, Video, Audio, Download, Other }

/** A file from the media store. [uri] is a content URI string; the file itself is never read. */
data class FileRecord(
    val id: Long,
    val name: String,
    val uri: String,
    val mime: String?,
    val kind: FileKind,
    /** Seconds since the epoch the file was added; newest first breaks ties. */
    val addedSeconds: Long = 0,
)

/** One file result. */
data class FileHit(val file: FileRecord, val score: Double)

/** One calendar event result; [event] carries the real instants. */
data class EventHit(val event: AgendaEvent, val score: Double)

/** Everything the sheet shows below the app rows, grouped. Immutable. */
data class ExtraResults(
    val phoneSettings: List<PhoneSettingHit> = emptyList(),
    val files: List<FileHit> = emptyList(),
    val events: List<EventHit> = emptyList(),
) {
    val isEmpty: Boolean get() = phoneSettings.isEmpty() && files.isEmpty() && events.isEmpty()

    companion object {
        val Empty = ExtraResults()
    }
}

/** Android answers whether this device has a Settings page for an action. Never throws. */
fun interface SettingsAvailability {
    fun has(action: String): Boolean
}

/** Media files by name. Blocking: call off the main thread. Empty without permission; never throws. */
interface FileSource {
    /** True when at least one media permission is granted (the source then reads only those types). */
    fun hasPermission(): Boolean
    fun search(query: String, limit: Int): List<FileRecord>
}

/** Calendar instances in a window. Blocking; empty without permission; never throws. */
interface EventSource {
    fun hasPermission(): Boolean
    fun events(fromMs: Long, toMs: Long): List<AgendaEvent>
}

/** Pure ranking for the three sources. No platform calls. */
object ExtraRanking {

    /** Phone settings whose page exists on this device, best first, at most [limit]. */
    fun phoneSettings(
        query: String,
        table: List<PhoneSetting>,
        availability: SettingsAvailability,
        limit: Int,
    ): List<PhoneSettingHit> {
        val phrase = ExtraMatcher.phrase(query)
        if (phrase.isEmpty() || limit <= 0) return emptyList()
        val scored = ArrayList<Pair<PhoneSetting, Double>>()
        for (setting in table) {
            var best = ExtraMatcher.score(phrase, setting.title)
            for (keyword in setting.keywords) {
                // Synonyms rank just under the title for the same kind of match.
                val value = ExtraMatcher.score(phrase, keyword)?.times(KeywordWeight)
                if (value != null && (best == null || value > best)) best = value
            }
            if (best != null) scored += setting to best
        }
        // Stable sort keeps the table order for ties.
        val ordered = scored.sortedByDescending { it.second }
        val out = ArrayList<PhoneSettingHit>(limit)
        val seenActions = HashSet<String>()
        for ((setting, score) in ordered) {
            if (out.size >= limit) break
            if (!seenActions.add(setting.action)) continue
            // The device check is last and only for rows that could be shown, so it stays cheap.
            if (availability.has(setting.action)) out += PhoneSettingHit(setting.title, setting.action, score)
        }
        return out
    }

    /** Files whose name matches, best first then newest, at most [limit]. */
    fun files(query: String, files: List<FileRecord>, limit: Int): List<FileHit> {
        val phrase = ExtraMatcher.phrase(query)
        if (phrase.isEmpty() || limit <= 0) return emptyList()
        val hits = ArrayList<FileHit>()
        for (file in files) {
            // File names are separated by dots, underscores and dashes; the matcher treats them as spaces.
            val score = ExtraMatcher.score(phrase, file.name, allowFuzzy = false) ?: continue
            hits += FileHit(file, score)
        }
        return hits.sortedWith(compareByDescending<FileHit> { it.score }.thenByDescending { it.file.addedSeconds }
            .thenBy { it.file.name.lowercase() }.thenBy { it.file.id }).take(limit)
    }

    /** Events of the window [fromMs, toMs) whose title matches, soonest first, at most [limit]. */
    fun events(query: String, events: List<AgendaEvent>, fromMs: Long, toMs: Long, limit: Int): List<EventHit> {
        val phrase = ExtraMatcher.phrase(query)
        if (phrase.isEmpty() || limit <= 0) return emptyList()
        val seen = HashSet<Triple<Long, Long, Long>>()
        val hits = ArrayList<EventHit>()
        for (event in events) {
            if (event.title.isBlank()) continue
            // An event that already ended, or starts after the window, is out. All-day events are UTC midnights.
            if (event.endMs <= fromMs && event.beginMs < fromMs) continue
            if (event.beginMs >= toMs) continue
            if (!seen.add(Triple(event.eventId, event.beginMs, event.calendarId))) continue
            val score = ExtraMatcher.score(phrase, event.title) ?: continue
            hits += EventHit(event, score)
        }
        // Better title match first, then the one that happens sooner.
        return hits.sortedWith(compareByDescending<EventHit> { it.score }.thenBy { it.event.beginMs }
            .thenBy { it.event.eventId }).take(limit)
    }

    private const val KeywordWeight = 0.9
}

/**
 * Runs the enabled sources for one query. Blocking and pure apart from its three ports; the sheet
 * calls it on a background dispatcher after a 150 ms debounce. A source that is switched off, lacks
 * its permission or throws contributes nothing, and never affects the others.
 */
class ExtraSearch(
    private val availability: SettingsAvailability,
    private val files: FileSource,
    private val events: EventSource,
    private val table: List<PhoneSetting> = PhoneSettingsTable.all,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun filesGranted(): Boolean = safe(false) { files.hasPermission() }

    fun calendarGranted(): Boolean = safe(false) { events.hasPermission() }

    fun search(query: String, toggles: ExtraToggles, caps: ExtraCaps = ExtraCaps()): ExtraResults {
        if (ExtraMatcher.phrase(query).isEmpty()) return ExtraResults.Empty
        val settingsHits = if (toggles.phoneSettings) {
            safe(emptyList()) { ExtraRanking.phoneSettings(query, table, availability, caps.phoneSettings) }
        } else emptyList()
        val fileHits = if (toggles.files && filesGranted()) {
            safe(emptyList()) {
                // The provider narrows by name; ranking and the cap are applied here either way.
                ExtraRanking.files(query, files.search(query.trim(), ProviderRows), caps.files)
            }
        } else emptyList()
        val eventHits = if (toggles.events && calendarGranted()) {
            safe(emptyList()) {
                val now = clock()
                val to = now + WindowDays * DayMs
                ExtraRanking.events(query, events.events(now, to), now, to, caps.events)
            }
        } else emptyList()
        return ExtraResults(settingsHits, fileHits, eventHits)
    }

    private inline fun <T> safe(fallback: T, block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        fallback
    }

    companion object {
        /** Calendar window: today and the next 60 days. */
        const val WindowDays = 60L
        const val DayMs = 24L * 60 * 60 * 1000

        /** Rows asked of the media provider before the pure ranker keeps the best few. */
        const val ProviderRows = 200
    }
}
