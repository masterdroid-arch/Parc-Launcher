package com.brightblu.parc.feature.search

/**
 * A [SearchDoc] with every searched field already folded. Immutable, non-null.
 *
 * Folding once at index time keeps `query` cheap: no normalisation happens per document per keystroke.
 */
class NormalizedDoc private constructor(
    val doc: SearchDoc,
    val label: NormalizedText,
    val packageName: NormalizedText,
    val keywords: List<NormalizedText>,
    /** T259: the Latin spelling of a Cyrillic field, so "tinkoff" finds "Тинькофф". */
    val labelLatin: NormalizedText,
    val packageNameLatin: NormalizedText,
    val keywordsLatin: List<NormalizedText>,
) {
    companion object {
        fun of(doc: SearchDoc): NormalizedDoc = NormalizedDoc(
            doc = doc,
            label = TextNormalizer.normalize(doc.label),
            packageName = TextNormalizer.normalize(doc.packageName),
            keywords = doc.keywords.map(TextNormalizer::normalize),
            labelLatin = TextNormalizer.normalize(Transliteration.toLatin(doc.label)),
            packageNameLatin = TextNormalizer.normalize(Transliteration.toLatin(doc.packageName)),
            keywordsLatin = doc.keywords.map { TextNormalizer.normalize(Transliteration.toLatin(it)) },
        )
    }
}

/** Pure folding of a document list into the engine's index. */
object SearchIndex {
    fun of(docs: List<SearchDoc>): List<NormalizedDoc> = docs.map(NormalizedDoc::of)
}
