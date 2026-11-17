package com.brightblu.parc.core.data.notifications

import com.brightblu.parc.core.data.comms.CommsDropReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One notification the listener refused to show, reduced to the posting package and the reason.
 * There is deliberately no title, sender, text, image or notification key: this is a diagnostic
 * ring, not a second message store.
 */
data class NotificationDrop(val packageName: String, val reason: CommsDropReason)

/** Immutable in-memory listener state. [lastDropReasons] is newest first. */
data class NotificationListenerSnapshot(
    val connected: Boolean = false,
    val connectedAtMs: Long? = null,
    val lastDropReasons: List<NotificationDrop> = emptyList(),
)

/**
 * Process-wide, in-memory listener status (T213).
 *
 * - [connected] and [connectedAtMs] are set by
 *   [ParcNotificationListenerService.onListenerConnected] and cleared on disconnect, so the
 *   Messages footer can say when Parc started listening.
 * - [lastDropReasons] is the last [MaxDropReasons] package-only drops after a filter, newest first.
 *   It is capped so a chatty app cannot grow it, and it dies with the process.
 *
 * Nothing here is persisted, logged or included in a backup, and a drop never carries content.
 */
object NotificationListenerStatus {

    /** The Why sheet shows five, so the ring keeps five. */
    const val MaxDropReasons = 5

    private val mutable = MutableStateFlow(NotificationListenerSnapshot())

    /** Observable snapshot for Compose. */
    val state: StateFlow<NotificationListenerSnapshot> = mutable.asStateFlow()

    /** True while the system has the listener bound. */
    val connected: Boolean get() = state.value.connected

    /** Wall-clock time of the last successful connect, or null when unknown/disconnected. */
    val connectedAtMs: Long? get() = state.value.connectedAtMs

    /** The last [MaxDropReasons] dropped notifications, newest first, package and reason only. */
    val lastDropReasons: List<NotificationDrop> get() = state.value.lastDropReasons

    /** Marks the listener connected and records when it started; [nowMs] is injectable for tests. */
    fun onConnected(nowMs: Long = System.currentTimeMillis()) {
        mutable.update { it.copy(connected = true, connectedAtMs = nowMs) }
    }

    /** Marks the listener disconnected and forgets the connect time; the drop ring is kept. */
    fun onDisconnected() {
        mutable.update { it.copy(connected = false, connectedAtMs = null) }
    }

    /**
     * Adds one package-only drop. A blank package is ignored. Duplicate reports are kept because
     * each is a real notification event; the ring stays at [MaxDropReasons].
     */
    fun recordDrop(packageName: String, reason: CommsDropReason) {
        val packageNameTrimmed = packageName.trim()
        if (packageNameTrimmed.isEmpty()) return
        mutable.update { snapshot ->
            snapshot.copy(
                lastDropReasons = (listOf(NotificationDrop(packageNameTrimmed, reason)) + snapshot.lastDropReasons)
                    .take(MaxDropReasons),
            )
        }
    }

    /** Forgets everything, e.g. when the service is destroyed. */
    fun clear() {
        mutable.value = NotificationListenerSnapshot()
    }
}
