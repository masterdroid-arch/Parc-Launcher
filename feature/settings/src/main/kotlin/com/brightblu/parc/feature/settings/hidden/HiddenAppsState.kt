package com.brightblu.parc.feature.settings.hidden

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId

/** One installed app the user can hide or unhide (T17, F-17). Immutable, non-null. */
data class HiddenAppRow(
    val appId: AppId,
    val label: String,
    val hidden: Boolean,
    val suspended: Boolean,
)

/**
 * Immutable hidden apps screen state. Non-null. [rows] is every matching installed app (the query
 * already applied) and stays for existing callers; [hiddenRows] and [visibleRows] are the two
 * non-overlapping sections the T219 screen draws. [hiddenCount] counts every permanently hidden
 * installed app regardless of the query, so the section heading does not change while searching.
 */
data class HiddenAppsUiState(
    val rows: List<HiddenAppRow> = emptyList(),
    val query: String = "",
    val hiddenCount: Int = 0,
    val hiddenRows: List<HiddenAppRow> = emptyList(),
    val visibleRows: List<HiddenAppRow> = emptyList(),
)

/**
 * Pure projection of the installed apps against the layout's hidden set. Immutable and thread-safe,
 * so the ordering and filtering rules are unit-tested without a device or a Compose host.
 *
 * Label matching uses a plain case-insensitive contains, not the search engine's diacritic folding:
 * the list is short and the user is scanning labels they can already see. Hidden apps stay in
 * [rows]; the screen splits them into [hiddenRows] and [visibleRows] so no row is drawn twice.
 */
object HiddenAppsProjection {

    /** Rows for [apps], sorted by label then id, with [query] applied to the label. */
    fun rows(apps: List<AppEntry>, hidden: Set<AppId>, query: String): List<HiddenAppRow> = apps
        .asSequence()
        .filter { matches(it.label, query) }
        .map { HiddenAppRow(it.id, it.label, it.id in hidden, it.suspended) }
        .sortedWith(compareBy({ it.label.lowercase() }, { it.appId.value }))
        .toList()

    /** The Hidden section: permanently hidden rows only, in the same label order. */
    fun hiddenRows(apps: List<AppEntry>, hidden: Set<AppId>, query: String): List<HiddenAppRow> =
        rows(apps, hidden, query).filter { it.hidden }

    /** The All apps section: rows that are not already in the Hidden section. */
    fun visibleRows(apps: List<AppEntry>, hidden: Set<AppId>, query: String): List<HiddenAppRow> =
        rows(apps, hidden, query).filterNot { it.hidden }

    /** True when [label] passes the filter. A blank query keeps everything. */
    fun matches(label: String, query: String): Boolean =
        query.isBlank() || label.contains(query.trim(), ignoreCase = true)
}
