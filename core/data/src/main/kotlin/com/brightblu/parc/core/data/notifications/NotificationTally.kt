package com.brightblu.parc.core.data.notifications

/**
 * One active content notification: the system's opaque key plus the package it belongs to.
 *
 * A key is what the platform hands a listener (`StatusBarNotification.key`); Parc never reads a
 * title, a body or an icon out of the notification. The value lives in memory only and is never
 * written to disk or to a log (PROJECT.md §6.3).
 */
internal data class UnreadKey(val packageName: String, val key: String)

/**
 * Pure unread tally (T15): package -> number of currently active content notifications.
 *
 * Why keys and not just counters: the platform re-posts the same key for an update and removes by
 * key, so a bare counter drifts (an update would count twice, a removal of an unknown key would
 * underflow). Keys are the smallest thing that makes post/update/remove exact, and they never leave
 * the process. Nothing here is time- or Android-dependent, so [`NotificationTallyTest`] pins the
 * whole counting rule.
 *
 * Order of [counts] follows first appearance of a package, which keeps the published snapshot
 * stable across unrelated updates (no reordering churn in the ring's pulse set).
 */
internal class NotificationTally(private val maxPerPackage: Int = DefaultMaxPerPackage) {

    init {
        require(maxPerPackage >= 1) { "maxPerPackage must be positive" }
    }

    private val keysByPackage = LinkedHashMap<String, MutableSet<String>>()
    private val packageOfKey = HashMap<String, String>()

    /**
     * Records a notification as active. `unread = false` (an ongoing one, a group summary, anything
     * that must not count) removes the key if it was counted, so a post can also correct an earlier
     * post for the same key.
     *
     * Returns whether the tally changed.
     */
    fun post(packageName: String, key: String, unread: Boolean): Boolean {
        if (key.isEmpty()) return false
        if (!unread) return remove(key)

        val previousPackage = packageOfKey[key]
        if (previousPackage == packageName) return false
        if (previousPackage != null) {
            // Same key under a different package: an update that changed owner (work-profile edge).
            keysByPackage[previousPackage]?.remove(key)
            prune(previousPackage)
        }
        val keys = keysByPackage.getOrPut(packageName) { LinkedHashSet() }
        // A runaway app must not grow the map without bound; beyond the cap new keys are ignored and
        // the count stays at the cap instead of silently dropping notifications already counted.
        if (keys.size >= maxPerPackage) return previousPackage != null
        keys.add(key)
        packageOfKey[key] = packageName
        return true
    }

    /** Drops a notification by key. Unknown keys (including removed non-unread ones) are a no-op. */
    fun remove(key: String): Boolean {
        val packageName = packageOfKey.remove(key) ?: return false
        keysByPackage[packageName]?.remove(key)
        prune(packageName)
        return true
    }

    /**
     * Replaces the tally with exactly [keys]. Used on listener connect to resync after a gap in
     * which posts or removals may have been missed.
     */
    fun replaceAll(keys: List<UnreadKey>) {
        clear()
        for (entry in keys) post(entry.packageName, entry.key, unread = true)
    }

    fun clear() {
        keysByPackage.clear()
        packageOfKey.clear()
    }

    /** Immutable snapshot; packages with no notifications are absent, never zero. */
    fun counts(): Map<String, Int> {
        val snapshot = LinkedHashMap<String, Int>(keysByPackage.size)
        for ((packageName, keys) in keysByPackage) snapshot[packageName] = keys.size
        return snapshot
    }

    /** How many keys are tracked across every package; the memory ceiling of the tally. */
    fun size(): Int = packageOfKey.size

    private fun prune(packageName: String) {
        if (keysByPackage[packageName]?.isEmpty() == true) keysByPackage.remove(packageName)
    }

    companion object {
        /**
         * Keys kept per package. Telegram and WhatsApp peak in the tens; 200 leaves room for a busy
         * day while bounding a misbehaving app's memory to a few tens of kilobytes.
         */
        const val DefaultMaxPerPackage = 200
    }
}
