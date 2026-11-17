package com.brightblu.parc.feature.search

import com.brightblu.parc.feature.search.calculator.Calculation
import com.brightblu.parc.feature.search.contacts.ContactHit
import com.brightblu.parc.feature.search.extras.EventHit
import com.brightblu.parc.feature.search.extras.ExtraResults
import com.brightblu.parc.feature.search.extras.FileHit
import com.brightblu.parc.feature.search.extras.ParcSettingHit
import com.brightblu.parc.feature.search.extras.PhoneSettingHit

/** One row of the search sheet's result list. [key] is stable for the list's item identity. */
sealed class SheetItem(val key: String) {
    /** "Recent searches" with its Clear action, shown above the remembered queries. */
    data object RecentHeader : SheetItem("recent-searches-header")
    data class RecentQuery(val text: String) : SheetItem("recent-search-$text")
    data class Calculator(val calculation: Calculation) : SheetItem("calculator")
    data class App(val row: SearchRow) : SheetItem(row.appId.value)
    /** A shortcut of the top hit; drawn just above its app so the app name stays nearest the field. */
    data class Shortcut(val appId: com.brightblu.parc.model.AppId, val shortcut: com.brightblu.parc.model.AppShortcut) :
        SheetItem("shortcut-${appId.value}-${shortcut.id}")
    data class Contact(val hit: ContactHit) : SheetItem("contact-${hit.contact.id}")
    data class Header(val title: String) : SheetItem("header-$title")
    data class Setting(val hit: PhoneSettingHit) : SheetItem("extra-setting-${hit.action}")
    /** OPUS8 (T133): a Parc settings page. */
    data class ParcSetting(val hit: ParcSettingHit) : SheetItem("parc-setting-${hit.key}")
    data class File(val hit: FileHit) : SheetItem("extra-file-${hit.file.kind}-${hit.file.id}")
    data class Note(val note: SearchNote) : SheetItem("note-${note.id}")
    data class Event(val hit: EventHit) : SheetItem("extra-event-${hit.event.eventId}-${hit.event.beginMs}")
}

/**
 * OPUS3: the order of the search sheet's result list, nearest the search field FIRST.
 *
 * The list is drawn bottom-up (`reverseLayout`), so item 0 sits right above the field, under the
 * thumb, and the list "starts" there: the best match is always visible without scrolling, and
 * weaker results continue upwards. A section's caption comes after its rows so it is drawn above
 * them. Pure, so the order is unit-tested.
 */
object SearchSheetItems {
    /** T248: captions of the two suggestion groups under an empty field. */
    const val MostUsedSection = "Most used"
    const val RecentAppsSection = "Recent apps"

    /** T259: the four sections of a typed search, in reading order from the field outwards. */
    const val AppsSection = "Apps"
    const val NotesSection = "Notes"
    const val FilesSection = "Files"
    const val ActionsSection = "Actions"

    fun build(
        query: String,
        earlierQueries: List<String>,
        calculation: Calculation?,
        rows: List<SearchRow>,
        contacts: List<ContactHit>,
        extras: ExtraResults,
        /** OPUS8 (T133): Parc's own settings pages, after apps and contacts, before phone settings. */
        parcSettings: List<ParcSettingHit> = emptyList(),
        /** T259: matching notes from the app's notes store. */
        notes: List<SearchNote> = emptyList(),
    ): List<SheetItem> {
        val items = ArrayList<SheetItem>()
        calculation?.let { items += SheetItem.Calculator(it) }
        val typed = query.isNotBlank()
        // T248: with an empty field the app suggestions are nearest the field, then the remembered
        // searches above them. A typed query keeps its hits in rank order, inside named sections.
        appendRows(items, rows, typed)
        if (query.isBlank() && earlierQueries.isNotEmpty()) {
            earlierQueries.forEach { items += SheetItem.RecentQuery(it) }
            items += SheetItem.RecentHeader
        }
        if (typed) {
            if (notes.isNotEmpty()) {
                notes.forEach { items += SheetItem.Note(it) }
                items += SheetItem.Header(NotesSection)
            }
            if (extras.files.isNotEmpty()) {
                extras.files.forEach { items += SheetItem.File(it) }
                items += SheetItem.Header(FilesSection)
            }
            appendActions(items, contacts, parcSettings, extras)
        } else {
            // An empty field has no typed sections; keep the earlier layout for the rare rows a
            // source may still return while search settles.
            contacts.forEach { items += SheetItem.Contact(it) }
            if (parcSettings.isNotEmpty()) {
                parcSettings.forEach { items += SheetItem.ParcSetting(it) }
                items += SheetItem.Header(com.brightblu.parc.feature.search.extras.ParcSettingsRanking.Header)
            }
            if (extras.phoneSettings.isNotEmpty()) {
                extras.phoneSettings.forEach { items += SheetItem.Setting(it) }
                items += SheetItem.Header(com.brightblu.parc.feature.search.extras.ExtraText.SettingsHeader)
            }
            if (extras.files.isNotEmpty()) {
                extras.files.forEach { items += SheetItem.File(it) }
                items += SheetItem.Header(com.brightblu.parc.feature.search.extras.ExtraText.FilesHeader)
            }
            if (extras.events.isNotEmpty()) {
                extras.events.forEach { items += SheetItem.Event(it) }
                items += SheetItem.Header(com.brightblu.parc.feature.search.extras.ExtraText.EventsHeader)
            }
        }
        // A remembered query can repeat a key only if the same text was stored twice.
        return items.distinctBy { it.key }
    }

    /**
     * T259: every typed row belongs to one of the four sections. A typed app result carries the
     * "Apps" caption; contacts and calendar rows become "Actions" because tapping them starts an
     * action (a card, a call, a message, an event) rather than opening an app or a file.
     */
    private fun appendActions(
        items: MutableList<SheetItem>,
        contacts: List<ContactHit>,
        parcSettings: List<ParcSettingHit>,
        extras: ExtraResults,
    ) {
        val actions = ArrayList<SheetItem>()
        contacts.forEach { actions += SheetItem.Contact(it) }
        parcSettings.forEach { actions += SheetItem.ParcSetting(it) }
        extras.phoneSettings.forEach { actions += SheetItem.Setting(it) }
        extras.events.forEach { actions += SheetItem.Event(it) }
        if (actions.isEmpty()) return
        items += actions
        items += SheetItem.Header(ActionsSection)
    }

    /**
     * T248/T259: consecutive rows of one kind, then that group's caption (the list is drawn
     * bottom-up, so the caption lands above its rows). Empty-field suggestions keep their Most used
     * and Recent apps groups; a typed result is one Apps section. Pure, so the grouping is tested.
     */
    private fun appendRows(items: MutableList<SheetItem>, rows: List<SearchRow>, typed: Boolean) {
        if (rows.isEmpty()) return
        if (typed) {
            rows.forEach { appendRow(items, it) }
            items += SheetItem.Header(AppsSection)
            return
        }
        var index = 0
        while (index < rows.size) {
            val section = sectionOf(rows[index])
            if (section == null) {
                appendRow(items, rows[index])
                index++
                continue
            }
            while (index < rows.size && sectionOf(rows[index]) == section) {
                appendRow(items, rows[index])
                index++
            }
            items += SheetItem.Header(section)
        }
    }

    private fun appendRow(items: MutableList<SheetItem>, row: SearchRow) {
        items += SheetItem.App(row)
        row.shortcuts.forEach { items += SheetItem.Shortcut(row.appId, it) }
    }

    private fun sectionOf(row: SearchRow): String? = when {
        row.isMostUsed -> MostUsedSection
        row.isRecent -> RecentAppsSection
        else -> null
    }

    /**
     * Whether the list must jump back to its first item: whenever the query or the first item
     * changes. Without this the list keeps the row that used to be first (Compose keeps the scroll
     * position on that row's key), so after typing, the best matches could sit above the visible
     * part of the list.
     */
    fun resetScroll(previousQuery: String?, previousFirstKey: String?, query: String, firstKey: String?): Boolean =
        previousQuery != query || previousFirstKey != firstKey

    /** How many items are hidden above the visible part; 0 means nothing more to scroll to. */
    fun hiddenAbove(total: Int, lastVisibleIndex: Int): Int =
        if (total <= 0) 0 else (total - 1 - lastVisibleIndex.coerceAtLeast(0)).coerceAtLeast(0)
}
