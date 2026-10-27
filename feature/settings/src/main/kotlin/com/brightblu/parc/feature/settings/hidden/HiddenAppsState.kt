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
 * Immutable hidden apps screen state. Non-null. [rows] is what the list draws (the query already
 * applied), [hiddenCount] counts the installed apps currently hidden (independent of the query), and
 * [query] is the raw filter text.
 */
data class HiddenAppsUiState(
    val rows: List<HiddenAppRow> = emptyList(),
    val query: String = "",
    val hiddenCount: Int = 0,
)

/**
 * Pure projection of the installed apps against the layout's hidden set. Immutable and thread-safe,
 * so the ordering and filtering rules are unit-tested without a device or a Compose host.
 *
 * Label matching uses a plain case-insensitive contains, not the search engine's diacritic folding:
 * the list is short and the user is scanning labels they can already see. Hidden apps stay in the
 * list, since unhiding is the point of the screen.
 */
object HiddenAppsProjection {

    /** Rows for [apps], sorted by label then id, with [query] applied to the label. */
    fun rows(apps: List<AppEntry>, hidden: Set<AppId>, query: String): List<HiddenAppRow> = apps
        .asSequence()
        .filter { matches(it.label, query) }
        .map { HiddenAppRow(it.id, it.label, it.id in hidden, it.suspended) }
        .sortedWith(compareBy({ it.label.lowercase() }, { it.appId.value }))
        .toList()

    /** True when [label] passes the filter. A blank query keeps everything. */
    fun matches(label: String, query: String): Boolean =
        query.isBlank() || label.contains(query.trim(), ignoreCase = true)
}
