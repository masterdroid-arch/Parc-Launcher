package com.brightblu.parc.core.data.notifications

import com.brightblu.parc.model.NotificationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Production [NotificationState] (PROJECT.md §6.2/§6.3, T15): package -> unread count, in memory.
 *
 * Privacy boundary: the store keeps opaque system keys (see [NotificationTally]) and publishes
 * counts. It never stores or publishes a title, a body, an icon or an app name, never touches disk,
 * and never logs. The published map is an immutable snapshot; state is only emitted when the counts
 * actually change, so a repeated post of the same notification cannot retrigger a pulse.
 *
 * Threading: listener callbacks arrive on a binder thread, the ring reads on main. The tally and the
 * published value are updated together under one lock, so a reader never sees a count that no
 * offered notification justifies. `StateFlow` then handles delivery.
 */
class NotificationUnreadStore internal constructor(
    private val tally: NotificationTally,
) : NotificationState {

    private val lock = Any()
    private val mutable = MutableStateFlow<Map<String, Int>>(emptyMap())

    override val unread: StateFlow<Map<String, Int>> = mutable.asStateFlow()

    /** A notification is now active for [packageName]; `unread = false` un-counts it. */
    internal fun posted(packageName: String, key: String, unread: Boolean = true) {
        publish { tally.post(packageName, key, unread) }
    }

    /** A notification left the shade. Unknown keys are ignored, so a double report is harmless. */
    internal fun removed(key: String) {
        publish { tally.remove(key) }
    }

    /** Resync after a listener gap: the tally becomes exactly [keys]. */
    internal fun rebuilt(keys: List<UnreadKey>) {
        publish { tally.replaceAll(keys) }
    }

    /** Forgets every count; used when the listener disconnects and counting stops. */
    internal fun cleared() {
        publish { tally.clear() }
    }

    /** Current count for one package; 0 when it has no notifications. */
    fun countFor(packageName: String): Int = mutable.value[packageName] ?: 0

    private fun publish(change: () -> Unit) {
        synchronized(lock) {
            change()
            val next = tally.counts()
            if (next != mutable.value) mutable.value = next
        }
    }

    companion object {
        /**
         * The instance the ring and the system-instantiated listener share. The platform creates the
         * service itself, so it cannot be constructor-injected; everything that counts lives in the
         * tally, which is.
         */
        val shared: NotificationUnreadStore by lazy { NotificationUnreadStore(NotificationTally()) }
    }
}
