package com.brightblu.parc.core.data.notifications

import com.brightblu.parc.core.data.lock.NotificationLockPolicy

/**
 * In-memory list of the notifications the long-press menu may show (T97). Never persisted, never
 * logged, bounded to [MaxEntries]. While [collecting] is false (the user turned `Notifications in
 * menus` off) nothing is kept at all. Thread-safe: the listener writes on a binder thread and the
 * launcher reads on main.
 */
class NotificationPeekStore {
    private val lock = Any()
    private val entries = LinkedHashMap<String, PeekEntry>()

    @Volatile
    var collecting: Boolean = false
        set(value) {
            field = value
            if (!value) clear()
        }

    fun post(entry: PeekEntry) {
        synchronized(lock) {
            entries.remove(entry.key)
            if (collecting && !NotificationLockPolicy.blocked(entry.packageName) && PeekRules.accepts(entry)) {
                entries[entry.key] = entry
                while (entries.size > MaxEntries) entries.remove(entries.keys.first())
            }
        }
    }

    fun remove(key: String) {
        synchronized(lock) { entries.remove(key) }
    }

    /** Resync after a listener gap: the store becomes exactly the accepted [list]. */
    fun replaceAll(list: List<PeekEntry>) {
        synchronized(lock) {
            entries.clear()
            if (collecting) {
                list.filter { !NotificationLockPolicy.blocked(it.packageName) }.filter(PeekRules::accepts).sortedBy { it.postTime }.takeLast(MaxEntries)
                    .forEach { entries[it.key] = it }
            }
        }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    fun refilterLocks() = synchronized(lock) { entries.values.removeAll { NotificationLockPolicy.blocked(it.packageName) }; Unit }
    fun snapshot(): List<PeekEntry> = synchronized(lock) { entries.values.filterNot { NotificationLockPolicy.blocked(it.packageName) } }

    companion object {
        const val MaxEntries = 200

        /** The instance the system-created listener and the launcher share. */
        val shared: NotificationPeekStore by lazy { NotificationPeekStore() }
    }
}
