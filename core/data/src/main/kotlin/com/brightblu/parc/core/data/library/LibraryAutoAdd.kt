package com.brightblu.parc.core.data.library

/**
 * T252: which files in the user's chosen folders are new to the library. A file counts as "seen" once
 * Parc has looked at it, so a book the user removed on purpose is never added again. The first pass
 * only records what is already there and adds nothing: only files that appear later are new.
 */
object LibraryAutoAdd {
    /** At most this many books are added per pass, so opening the Library never turns into a long import. */
    const val MaxPerPass = 20
    const val MaxSeen = 40_000

    fun key(uri: String): String = stableBookId(uri).take(24)

    data class Plan(val add: List<FoundBook>, val seen: Set<String>, val first: Boolean)

    /**
     * [files] must already be limited to the chosen folders. [seen] is null before the first pass.
     * Files left over after [MaxPerPass] stay unseen and are picked up by the next pass.
     */
    fun plan(files: List<FoundBook>, seen: Set<String>?, library: List<Book>): Plan {
        if (seen == null) return Plan(emptyList(), files.mapTo(linkedSetOf()) { key(it.uri) }, first = true)
        val inLibrary = library.mapTo(hashSetOf()) { it.uri }
        val fresh = files.filter { it.uri !in inLibrary && key(it.uri) !in seen }
            .sortedWith(compareByDescending<FoundBook> { it.modifiedAt }.thenBy { it.name })
        val take = fresh.take(MaxPerPass)
        val known = LinkedHashSet(seen)
        // Files already in the library are seen too, so removing one later cannot bring it back.
        files.filter { it.uri in inLibrary }.forEach { known += key(it.uri) }
        take.forEach { known += key(it.uri) }
        return Plan(take, known.trimTo(MaxSeen), first = false)
    }

    private fun LinkedHashSet<String>.trimTo(limit: Int): Set<String> =
        if (size <= limit) this else this.toList().takeLast(limit).toCollection(linkedSetOf())
}
