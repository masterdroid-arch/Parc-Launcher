package com.brightblu.parc.core.data.library

import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * T251: the Library's own shelves. The three built-ins are fixed and never stored; only the user's
 * own shelves live in the document, in their chosen order. A book stores the ids of the own shelves
 * it is on, so one book can be on several.
 */
object LibraryShelfIds {
    const val All = "all"
    const val Reading = "reading"
    const val Finished = "finished"

    val builtIn = listOf(All, Reading, Finished)
    fun isBuiltIn(id: String): Boolean = id in builtIn
}

object LibraryShelfLimits {
    const val MaxName = 48
    const val MaxShelves = 50
    const val MaxRules = 12
    const val MaxRuleValue = 200
    const val MaxChars = 300_000
}

/** T252: what a rule looks at. A shelf's rules are alternatives: a book matching any one lands there. */
@Serializable
enum class ShelfRuleKind { Type, Folder, Name }

/**
 * T252: one rule. [value] is the format name (EPUB, PDF, TXT, CBZ), a chosen folder's location, or
 * the text a title or file name must contain. [label] is only the folder's human name for display.
 */
@Serializable
data class ShelfRule(val kind: ShelfRuleKind, val value: String, val label: String = "")

@Serializable
data class LibraryShelf(val id: String, val name: String, val rules: List<ShelfRule> = emptyList()) {
    init {
        require(id.isNotBlank() && id.length <= 64)
        require(name.isNotBlank() && name.length <= LibraryShelfLimits.MaxName)
    }
}

/** [seeded] is true once the first-use default (the Comics shelf) was offered, so deleting it sticks. */
@Serializable
data class LibraryShelvesDocument(
    val shelves: List<LibraryShelf> = emptyList(),
    val seeded: Boolean = false,
) {
    fun shelf(id: String): LibraryShelf? = shelves.firstOrNull { it.id == id }

    fun indexOf(id: String): Int = shelves.indexOfFirst { it.id == id }
}

/** One shelf as the page draws it; the built-ins always come first. */
data class LibraryShelfTab(val id: String, val label: String, val builtIn: Boolean)

/** The shelf's sort choice. Recent follows the reading order, not the last change. */
enum class LibraryShelfSort(val label: String) {
    Recent("Recent"),
    Title("Title"),
    Author("Author"),
    Added("Added"),
}

/** A name after trimming, or the quiet reason it cannot be used. */
data class ShelfNameCheck(val name: String?, val error: String?) {
    val ok: Boolean get() = name != null
}

/**
 * T251: all shelf rules are here and framework-free. The UI only draws the result; stored book
 * membership is a list of own-shelf ids, and deleting a shelf removes those ids without touching
 * the books themselves.
 */
object LibraryShelfRules {
    val builtInTabs: List<LibraryShelfTab> = listOf(
        LibraryShelfTab(LibraryShelfIds.All, "All", true),
        LibraryShelfTab(LibraryShelfIds.Reading, "Reading", true),
        LibraryShelfTab(LibraryShelfIds.Finished, "Finished", true),
    )

    fun tabs(document: LibraryShelvesDocument): List<LibraryShelfTab> =
        builtInTabs + document.shelves.map { LibraryShelfTab(it.id, it.name, false) }

    fun cleanName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    fun checkName(raw: String, document: LibraryShelvesDocument, exceptId: String? = null): ShelfNameCheck {
        val clean = cleanName(raw)
        return when {
            clean.isEmpty() -> ShelfNameCheck(null, "Give the shelf a name.")
            clean.length > LibraryShelfLimits.MaxName ->
                ShelfNameCheck(null, "Keep the name to ${LibraryShelfLimits.MaxName} characters.")
            builtInTabs.any { it.label.equals(clean, ignoreCase = true) } ->
                ShelfNameCheck(null, "That name belongs to a built-in shelf.")
            document.shelves.any { it.id != exceptId && it.name.equals(clean, ignoreCase = true) } ->
                ShelfNameCheck(null, "A shelf with that name already exists.")
            else -> ShelfNameCheck(clean, null)
        }
    }

    /** A stable, never-reused-while-present id; the number always moves forward. */
    fun nextId(shelves: List<LibraryShelf>): String {
        val used = shelves.mapTo(hashSetOf()) { it.id }
        val highest = shelves.mapNotNull { it.id.removePrefix("shelf-").toIntOrNull() }.maxOrNull() ?: 0
        var next = highest + 1
        while ("shelf-$next" in used) next++
        return "shelf-$next"
    }

    fun add(document: LibraryShelvesDocument, rawName: String): LibraryShelvesDocument? {
        if (document.shelves.size >= LibraryShelfLimits.MaxShelves) return null
        val name = checkName(rawName, document).name ?: return null
        return document.copy(shelves = document.shelves + LibraryShelf(nextId(document.shelves), name))
    }

    fun rename(document: LibraryShelvesDocument, id: String, rawName: String): LibraryShelvesDocument? {
        val existing = document.shelf(id) ?: return null
        val name = checkName(rawName, document, exceptId = id).name ?: return null
        if (existing.name == name) return document
        return document.copy(shelves = document.shelves.map { if (it.id == id) it.copy(name = name) else it })
    }

    fun move(document: LibraryShelvesDocument, id: String, delta: Int): LibraryShelvesDocument {
        val from = document.indexOf(id)
        if (from < 0 || document.shelves.size < 2) return document
        val to = (from + delta.coerceIn(-1, 1)).coerceIn(0, document.shelves.lastIndex)
        if (from == to) return document
        val ordered = document.shelves.toMutableList()
        ordered.add(to, ordered.removeAt(from))
        return document.copy(shelves = ordered)
    }

    // ----- T252: rules -----

    const val DefaultComicsName = "Comics"

    fun typeLabel(value: String): String = if (value.equals("TXT", true)) "Text" else value.uppercase(Locale.ROOT)

    /** Short, icon-friendly words for a rule row. */
    fun describe(rule: ShelfRule): String = when (rule.kind) {
        ShelfRuleKind.Type -> "${typeLabel(rule.value)} files"
        ShelfRuleKind.Folder -> "Folder ${rule.label.ifBlank { rule.value.substringAfterLast('/') }}"
        ShelfRuleKind.Name -> "Name has “${rule.value}”"
    }

    fun cleanRule(rule: ShelfRule): ShelfRule? {
        val value = rule.value.trim()
        if (value.isEmpty() || value.length > LibraryShelfLimits.MaxRuleValue) return null
        return when (rule.kind) {
            ShelfRuleKind.Type -> BookFormat.entries.firstOrNull { it.name.equals(value, true) }
                ?.let { ShelfRule(ShelfRuleKind.Type, it.name) }
            ShelfRuleKind.Folder -> ShelfRule(ShelfRuleKind.Folder, value.trimEnd('/').ifEmpty { "/" },
                rule.label.trim().take(LibraryShelfLimits.MaxName))
            ShelfRuleKind.Name -> ShelfRule(ShelfRuleKind.Name, cleanName(value))
        }
    }

    /** T346: the file's own name, used by Details so two copies of one title can be told apart. */
    fun fileName(book: Book): String {
        val last = book.uri.substringAfterLast('/')
        return runCatching { java.net.URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last)
            .substringAfterLast('/').substringAfterLast(':')
    }

    /**
     * T346: the shelf's own search over the books currently on the shelf. A blank query keeps every
     * book; otherwise title, author and file name are matched without regard to case.
     */
    fun search(books: List<Book>, query: String): List<Book> {
        val needle = query.trim()
        if (needle.isEmpty()) return books
        return books.filter { book ->
            book.title.contains(needle, ignoreCase = true) ||
                book.author.contains(needle, ignoreCase = true) ||
                fileName(book).contains(needle, ignoreCase = true)
        }
    }

    fun matches(rule: ShelfRule, book: Book): Boolean = when (rule.kind) {
        ShelfRuleKind.Type -> book.format.name.equals(rule.value, true)
        // Both plain paths and picker trees put a chosen folder's location in front of every file under it.
        ShelfRuleKind.Folder -> book.uri.startsWith(rule.value.trimEnd('/') + "/")
        ShelfRuleKind.Name -> rule.value.isNotBlank() &&
            (book.title.contains(rule.value, true) || fileName(book).contains(rule.value, true))
    }

    fun matches(shelf: LibraryShelf, book: Book): Boolean = shelf.rules.any { matches(it, book) }

    /** How many books a rule matches now; the rule list shows this beside each rule. */
    fun matchCount(rule: ShelfRule, books: List<Book>): Int = books.count { matches(rule, it) }

    fun addRule(document: LibraryShelvesDocument, shelfId: String, rule: ShelfRule): LibraryShelvesDocument? {
        val shelf = document.shelf(shelfId) ?: return null
        val clean = cleanRule(rule) ?: return null
        if (shelf.rules.size >= LibraryShelfLimits.MaxRules) return null
        if (shelf.rules.any { it.kind == clean.kind && it.value.equals(clean.value, true) }) return document
        return document.copy(shelves = document.shelves.map {
            if (it.id == shelfId) it.copy(rules = it.rules + clean) else it
        })
    }

    fun removeRule(document: LibraryShelvesDocument, shelfId: String, index: Int): LibraryShelvesDocument {
        val shelf = document.shelf(shelfId) ?: return document
        if (index !in shelf.rules.indices) return document
        return document.copy(shelves = document.shelves.map {
            if (it.id == shelfId) it.copy(rules = it.rules.filterIndexed { i, _ -> i != index }) else it
        })
    }

    /** Rules only ever add a book to a shelf; they never take one off, so a hand-made change is kept. */
    fun applyRules(document: LibraryShelvesDocument, book: Book): Book {
        val extra = document.shelves.filter { it.id !in book.shelves && matches(it, book) }.map { it.id }
        return if (extra.isEmpty()) book else book.copy(shelves = book.shelves + extra)
    }

    fun applyRules(document: LibraryShelvesDocument, books: List<Book>): List<Book> =
        books.map { applyRules(document, it) }

    /** First use: a "Comics" shelf that takes every CBZ. Never again once [seeded]. */
    fun seedDefaults(document: LibraryShelvesDocument): LibraryShelvesDocument {
        if (document.seeded) return document
        val withComics = if (document.shelves.size < LibraryShelfLimits.MaxShelves &&
            checkName(DefaultComicsName, document).ok) {
            document.copy(shelves = document.shelves + LibraryShelf(nextId(document.shelves), DefaultComicsName,
                listOf(ShelfRule(ShelfRuleKind.Type, BookFormat.CBZ.name))))
        } else document
        return withComics.copy(seeded = true)
    }

    fun without(document: LibraryShelvesDocument, id: String): LibraryShelvesDocument =
        document.copy(shelves = document.shelves.filterNot { it.id == id })

    fun withShelf(book: Book, shelfId: String, on: Boolean): Book {
        if (shelfId.isBlank() || LibraryShelfIds.isBuiltIn(shelfId)) return book
        val has = shelfId in book.shelves
        if (on == has) return book
        return book.copy(shelves = if (on) book.shelves + shelfId else book.shelves - shelfId)
    }

    fun withoutShelf(book: Book, shelfId: String): Book =
        if (shelfId in book.shelves) book.copy(shelves = book.shelves - shelfId) else book

    /** Mark finished also records that the one-time last-page question has been settled. */
    fun markFinished(book: Book, finished: Boolean): Book =
        book.copy(finished = finished, finishAsked = true)

    fun markFinishAsked(book: Book): Book = if (book.finishAsked) book else book.copy(finishAsked = true)

    /** A book reaches its last page once; after the question, finished or not, it is never asked again. */
    fun shouldAskFinished(book: Book): Boolean =
        book.reachedEnd && !book.finished && !book.finishAsked

    /** Called by the reader when the last page is displayed; the flag stays for the one-time question. */
    fun markReachedEnd(book: Book): Book = if (book.reachedEnd) book else book.copy(reachedEnd = true)

    /** "Reading" means opened and not marked finished; a finished mark is independent of the last page. */
    fun isReading(book: Book): Boolean = book.lastOpenedAt != null && !book.finished

    fun booksFor(shelfId: String, books: List<Book>): List<Book> = when (shelfId) {
        LibraryShelfIds.All -> books
        LibraryShelfIds.Reading -> books.filter(::isReading)
        LibraryShelfIds.Finished -> books.filter { it.finished }
        else -> books.filter { shelfId in it.shelves }
    }

    fun sorted(books: List<Book>, sort: LibraryShelfSort): List<Book> = books.sortedWith(comparator(sort))

    fun emptyMessage(shelfId: String): String = when (shelfId) {
        LibraryShelfIds.All -> "No books yet."
        LibraryShelfIds.Reading -> "No books in progress. A book appears here once it is opened."
        LibraryShelfIds.Finished -> "No finished books yet."
        else -> "No books on this shelf yet. Hold a book to add it."
    }

    private fun comparator(sort: LibraryShelfSort): Comparator<Book> = when (sort) {
        LibraryShelfSort.Recent -> compareByDescending<Book> { it.lastOpenedAt ?: Long.MIN_VALUE }
            .thenByDescending { it.addedAt }
            .thenBy { it.title.lowercase(Locale.ROOT) }
        LibraryShelfSort.Title -> compareBy<Book> { it.title.lowercase(Locale.ROOT) }
            .thenBy { it.author.lowercase(Locale.ROOT) }
        LibraryShelfSort.Author -> compareBy<Book> { it.author.isBlank() }
            .thenBy { it.author.lowercase(Locale.ROOT) }
            .thenBy { it.title.lowercase(Locale.ROOT) }
        LibraryShelfSort.Added -> compareByDescending<Book> { it.addedAt }
            .thenBy { it.title.lowercase(Locale.ROOT) }
    }
}

/** Empty, old or malformed data reads as no own shelves; one bad row cannot discard the others. */
object LibraryShelvesJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(document: LibraryShelvesDocument): String {
        val text = json.encodeToString(LibraryShelvesDocument.serializer(), document)
        require(text.length <= LibraryShelfLimits.MaxChars) { "Shelves are too large" }
        return text
    }

    fun decode(raw: String?): LibraryShelvesDocument {
        if (raw.isNullOrBlank() || raw.length > LibraryShelfLimits.MaxChars) return LibraryShelvesDocument()
        val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return LibraryShelvesDocument()
        val rows = runCatching {
            when (parsed) {
                is JsonArray -> parsed
                is JsonObject -> parsed["shelves"]?.jsonArray
                else -> null
            }
        }.getOrNull() ?: return LibraryShelvesDocument()
        val seeded = runCatching {
            (parsed as? JsonObject)?.get("seeded")?.jsonPrimitive?.booleanOrNull
        }.getOrNull() ?: false
        val ids = hashSetOf<String>()
        val names = hashSetOf<String>()
        val shelves = rows.mapNotNull { row ->
            val shelf = runCatching { json.decodeFromJsonElement(LibraryShelf.serializer(), row) }.getOrNull()
                ?: return@mapNotNull null
            if (LibraryShelfIds.isBuiltIn(shelf.id)) return@mapNotNull null
            if (!ids.add(shelf.id)) return@mapNotNull null
            if (!names.add(shelf.name.lowercase(Locale.ROOT))) return@mapNotNull null
            shelf.copy(rules = shelf.rules.mapNotNull(LibraryShelfRules::cleanRule)
                .distinct().take(LibraryShelfLimits.MaxRules))
        }.take(LibraryShelfLimits.MaxShelves)
        return LibraryShelvesDocument(shelves, seeded)
    }
}
