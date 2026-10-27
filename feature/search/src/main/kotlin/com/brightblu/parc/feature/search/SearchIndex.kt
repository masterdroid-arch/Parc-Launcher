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
) {
    companion object {
        fun of(doc: SearchDoc): NormalizedDoc = NormalizedDoc(
            doc = doc,
            label = TextNormalizer.normalize(doc.label),
            packageName = TextNormalizer.normalize(doc.packageName),
            keywords = doc.keywords.map(TextNormalizer::normalize),
        )
    }
}

/** Pure folding of a document list into the engine's index. */
object SearchIndex {
    fun of(docs: List<SearchDoc>): List<NormalizedDoc> = docs.map(NormalizedDoc::of)
}
