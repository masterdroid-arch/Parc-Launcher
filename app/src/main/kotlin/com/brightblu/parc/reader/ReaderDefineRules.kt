package com.brightblu.parc.reader

import java.util.Locale

/**
 * T256: the pure rules behind Define. The phone's dictionary is reached through the system
 * "process text" action; this file only decides which handlers to offer, so it has no Android
 * types and is unit tested on the JVM.
 */
internal data class DefineHandler(
    val packageName: String,
    val activityName: String,
    val label: String,
    val dictionary: Boolean,
)

/** [all] is what the system answered; [chosen] is what Define should offer (dictionary apps first). */
internal data class DefinePlan(
    val all: List<DefineHandler>,
    val chosen: List<DefineHandler>,
) {
    val hasApp: Boolean get() = all.isNotEmpty()
    val wantsChooser: Boolean get() = chosen.size > 1
}

internal object ReaderDefineRules {
    /** No handler at all: the menu says so instead of opening an empty chooser. */
    const val NoAppMessage = "No dictionary app found. Install one that can define selected text."

    /**
     * Labels and package names are the only signals Android gives us for "this is a dictionary".
     * They are matched in Latin scripts; an app named in another script still appears in the
     * fallback chooser because a process-text handler is by definition able to act on the word.
     */
    private val DictionaryWords = listOf(
        "dictionary", "dict", "define", "definition", "gloss", "thesaurus", "wordnet",
        "dicion", "diccion", "diction", "wörterbuch", "woerterbuch", "vocab", "meaning",
    )

    fun looksLikeDictionary(label: String, packageName: String): Boolean {
        val haystack = "$label $packageName".lowercase(Locale.ROOT)
        return DictionaryWords.any { it in haystack }
    }

    fun plan(handlers: List<DefineHandler>): DefinePlan {
        val dictionary = handlers.filter { it.dictionary }
        return DefinePlan(all = handlers, chosen = dictionary.ifEmpty { handlers })
    }
}
