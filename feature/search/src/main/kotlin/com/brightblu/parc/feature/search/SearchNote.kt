package com.brightblu.parc.feature.search

/**
 * T259: one matching note in the Apps results. The app supplies the title and a short plain line;
 * the sheet never reads the notes store itself.
 */
data class SearchNote(
    val id: String,
    val title: String,
    val detail: String = "",
)
