package com.brightblu.parc.core.data.notifications

/** Lock-screen visibility of one notification, reduced to what the menu rules need (T97). */
enum class PeekVisibility {
    Public, Private, Secret;

    companion object {
        /** Maps `Notification.VISIBILITY_*` (1 public, 0 private, -1 secret); anything else is private. */
        fun fromPlatform(value: Int): PeekVisibility = when (value) {
            1 -> Public
            -1 -> Secret
            else -> Private
        }
    }
}

/**
 * What the long-press menu may know about one active notification (T97). Pure data: no
 * `PendingIntent`, no icon, no bitmap. The text is kept in memory only, never written to disk or
 * logged, and dropped when the listener disconnects.
 */
data class PeekEntry(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val visibility: PeekVisibility = PeekVisibility.Private,
    val ongoing: Boolean = false,
    val groupSummary: Boolean = false,
)

/** One notification as the menu shows it. An empty [text] means "title only". */
data class PeekLine(val key: String, val title: String, val text: String)

/** Pure selection and privacy rules for the notifications section of the menus (T97). */
object PeekRules {
    const val MaxPerApp = 3
    const val MaxLineChars = 120
    const val FallbackTitle = "Notification"

    /** Ongoing, group-summary and secret notifications never reach the menu; nor do empty ones. */
    fun accepts(entry: PeekEntry): Boolean =
        !entry.ongoing && !entry.groupSummary && entry.visibility != PeekVisibility.Secret &&
            (entry.title.isNotBlank() || entry.text.isNotBlank())

    /** Up to [limit] accepted notifications of [packageName], newest first (ties by key for stability). */
    fun forApp(entries: Collection<PeekEntry>, packageName: String, limit: Int = MaxPerApp): List<PeekEntry> =
        entries.asSequence()
            .filter { it.packageName == packageName && accepts(it) }
            .sortedWith(compareByDescending<PeekEntry> { it.postTime }.thenBy { it.key })
            .take(limit.coerceAtLeast(0))
            .toList()

    /**
     * The row for one notification. The text is dropped when the user turned `Show message text`
     * off. The launcher is only on screen while the phone is unlocked, so a notification that is PRIVATE on the lock screen
     * still shows its text here; SECRET ones never reach the menu at all.
     */
    fun line(entry: PeekEntry, showText: Boolean): PeekLine {
        val textAllowed = showText && entry.visibility != PeekVisibility.Secret
        val title = entry.title.ifBlank { if (textAllowed) entry.text else FallbackTitle }
        val text = if (textAllowed && entry.text.isNotBlank() && entry.text != title) entry.text else ""
        return PeekLine(entry.key, title, text)
    }

    /** Accepted notification count per package, for the folder menu. */
    fun counts(entries: Collection<PeekEntry>): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (e in entries) if (accepts(e)) out[e.packageName] = (out[e.packageName] ?: 0) + 1
        return out
    }

    /** First non-empty line of [raw], trimmed and cut to [max] characters with an ellipsis. */
    fun oneLine(raw: CharSequence?, max: Int = MaxLineChars): String {
        val first = raw?.toString()?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }.orEmpty()
        return if (first.length <= max) first else first.take(max - 1).trimEnd() + "…"
    }
}
