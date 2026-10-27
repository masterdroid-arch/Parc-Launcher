package com.brightblu.parc.feature.search

import com.brightblu.parc.feature.search.calculator.Calculation
import com.brightblu.parc.feature.search.contacts.ContactHit
import com.brightblu.parc.feature.search.extras.EventHit
import com.brightblu.parc.feature.search.extras.ExtraResults
import com.brightblu.parc.feature.search.extras.ExtraText
import com.brightblu.parc.feature.search.extras.FileHit
import com.brightblu.parc.feature.search.extras.ParcSettingHit
import com.brightblu.parc.feature.search.extras.ParcSettingsRanking
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
    fun build(
        query: String,
        earlierQueries: List<String>,
        calculation: Calculation?,
        rows: List<SearchRow>,
        contacts: List<ContactHit>,
        extras: ExtraResults,
        /** OPUS8 (T133): Parc's own settings pages, after apps and contacts, before phone settings. */
        parcSettings: List<ParcSettingHit> = emptyList(),
    ): List<SheetItem> {
        val items = ArrayList<SheetItem>()
        if (query.isBlank() && earlierQueries.isNotEmpty()) {
            earlierQueries.forEach { items += SheetItem.RecentQuery(it) }
            items += SheetItem.RecentHeader
        }
        calculation?.let { items += SheetItem.Calculator(it) }
        rows.forEach { row ->
            items += SheetItem.App(row)
            row.shortcuts.forEach { items += SheetItem.Shortcut(row.appId, it) }
        }
        contacts.forEach { items += SheetItem.Contact(it) }
        if (parcSettings.isNotEmpty()) {
            parcSettings.forEach { items += SheetItem.ParcSetting(it) }
            items += SheetItem.Header(ParcSettingsRanking.Header)
        }
        if (extras.phoneSettings.isNotEmpty()) {
            extras.phoneSettings.forEach { items += SheetItem.Setting(it) }
            items += SheetItem.Header(ExtraText.SettingsHeader)
        }
        if (extras.files.isNotEmpty()) {
            extras.files.forEach { items += SheetItem.File(it) }
            items += SheetItem.Header(ExtraText.FilesHeader)
        }
        if (extras.events.isNotEmpty()) {
            extras.events.forEach { items += SheetItem.Event(it) }
            items += SheetItem.Header(ExtraText.EventsHeader)
        }
        // A remembered query can repeat a key only if the same text was stored twice.
        return items.distinctBy { it.key }
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
