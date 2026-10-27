package com.brightblu.parc.core.data.lock

import com.brightblu.parc.core.data.comms.CommsInbox
import com.brightblu.parc.core.data.comms.CommsHistory
import com.brightblu.parc.core.data.notifications.NotificationPeekStore

/** Listener fails closed until the launcher has loaded its lock policy. Content is never logged. */
object NotificationLockPolicy {
    /**
     * The launcher's "hidden right now" packages. Setting it for the first time (null to a value)
     * runs [onReady] once, so a listener that connected earlier and dropped everything rebuilds
     * (OPUS6, audit F10).
     */
    @Volatile var packages: (() -> Set<String>)? = null
        set(value) {
            val first = field == null && value != null
            field = value
            if (first) try { onReady?.invoke() } catch (_: Exception) { }
        }

    /** Set by the connected listener: rebuild from the active notifications. */
    @Volatile var onReady: (() -> Unit)? = null
    fun blocked(packageName: String): Boolean = packages?.invoke()?.contains(packageName) == true
    fun mayRead(packageName: String): Boolean = packages != null && !blocked(packageName)
    fun refilter() {
        CommsInbox.shared.refilterLocks()
        CommsHistory.shared.refilterLocks()
        NotificationPeekStore.shared.refilterLocks()
    }
}
