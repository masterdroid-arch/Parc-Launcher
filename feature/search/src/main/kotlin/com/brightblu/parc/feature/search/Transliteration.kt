package com.brightblu.parc.feature.search

/**
 * T259: practical Cyrillic/Latin folding for search. The tables cover Russian plus the Ukrainian and
 * Belarusian letters a phone keyboard can produce. Both directions are lossy on purpose: the point is
 * that a name typed in the other script still reaches the app, not that the text can be restored.
 *
 * Pure and stateless; safe from any thread. Input is expected to be already folded by
 * [TextNormalizer], so everything here works on lowercase.
 */
object Transliteration {

    /** The Latin spelling of a Cyrillic name: "Яндекс" becomes "yandeks". */
    fun toLatin(text: String): String {
        if (text.isEmpty()) return text
        val source = text.lowercase()
        val out = StringBuilder(source.length + 4)
        for (char in source) {
            val mapped = CyrillicToLatin[char]
            if (mapped == null) out.append(char) else out.append(mapped)
        }
        return out.toString()
    }

    /**
     * The Cyrillic spelling of a Latin name: "telegram" becomes "телеграм", "yandex" becomes
     * "яндекс". Multi-letter pairs are matched before single letters.
     */
    fun toCyrillic(text: String): String {
        if (text.isEmpty()) return text
        val source = text.lowercase()
        val out = StringBuilder(source.length + 4)
        var index = 0
        while (index < source.length) {
            val pair = if (index + 1 < source.length) source.substring(index, index + 2) else ""
            val triple = if (index + 2 < source.length) source.substring(index, index + 3) else ""
            val four = if (index + 3 < source.length) source.substring(index, index + 4) else ""
            val mapped = when {
                four.isNotEmpty() && LatinToCyrillic[four] != null -> four to LatinToCyrillic.getValue(four)
                triple.isNotEmpty() && LatinToCyrillic[triple] != null -> triple to LatinToCyrillic.getValue(triple)
                pair.isNotEmpty() && LatinToCyrillic[pair] != null -> pair to LatinToCyrillic.getValue(pair)
                else -> null
            }
            if (mapped != null) {
                out.append(mapped.second)
                index += mapped.first.length
            } else {
                val single = LatinToCyrillic[source[index].toString()]
                out.append(single ?: source[index])
                index++
            }
        }
        return out.toString()
    }

    /**
     * Query forms searched for one folded query: the query itself, its Cyrillic form when it is
     * Latin, and its Latin form when it is Cyrillic. Duplicates and empties are removed.
     */
    fun queryVariants(folded: String): List<String> {
        if (folded.isBlank()) return emptyList()
        val variants = LinkedHashSet<String>(3)
        variants += folded
        if (folded.any { it in CyrillicLetters }) {
            TextNormalizer.fold(toLatin(folded)).takeIf { it.isNotBlank() }?.let(variants::add)
        }
        if (folded.any { it in LatinLetters }) {
            TextNormalizer.fold(toCyrillic(folded)).takeIf { it.isNotBlank() }?.let(variants::add)
        }
        return variants.toList()
    }

    private val CyrillicLetters: Set<Char> = "абвгдеёжзийклмнопрстуфхцчшщъыьэюяіїєґў".toSet()
    private val LatinLetters: Set<Char> = "abcdefghijklmnopqrstuvwxyz".toSet()

    private val CyrillicToLatin: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        'і' to "i", 'ї' to "i", 'є' to "e", 'ґ' to "g", 'ў' to "u",
    )

    /**
     * Pairs are checked longest first in [toCyrillic]; the order of this map does not matter because
     * the caller asks for the longest substring it has. An unmatched Latin letter stays as typed, so
     * a mixed query still matches its own Latin part.
     */
    private val LatinToCyrillic: Map<String, String> = mapOf(
        "shch" to "щ", "sch" to "щ", "sh" to "ш", "ch" to "ч", "zh" to "ж", "kh" to "х",
        "ts" to "ц", "ya" to "я", "yu" to "ю", "yo" to "ё", "ye" to "е", "iu" to "ю",
        "ia" to "я", "je" to "е", "dj" to "дж",
        "a" to "а", "b" to "б", "c" to "ц", "d" to "д", "e" to "е", "f" to "ф", "g" to "г",
        "h" to "х", "i" to "и", "j" to "й", "k" to "к", "l" to "л", "m" to "м", "n" to "н",
        "o" to "о", "p" to "п", "q" to "к", "r" to "р", "s" to "с", "t" to "т", "u" to "у",
        "v" to "в", "w" to "в", "x" to "кс", "y" to "й", "z" to "з",
    )
}
