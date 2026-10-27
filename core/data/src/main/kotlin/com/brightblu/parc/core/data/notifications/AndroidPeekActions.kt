package com.brightblu.parc.core.data.notifications

import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import java.util.concurrent.ConcurrentHashMap

/**
 * Opens or dismisses a notification on behalf of the long-press menu (T97). Holds the content
 * intents in memory only while the listener is bound; every call is wrapped so a revoked listener, a
 * cancelled intent or a vanished notification can never throw into the launcher.
 */
class AndroidPeekActions {
    private val intents = ConcurrentHashMap<String, PendingIntent>()
    private val autoCancel = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var service: NotificationListenerService? = null

    fun attach(listener: NotificationListenerService) { service = listener }

    fun detach(listener: NotificationListenerService) {
        if (service === listener) service = null
        intents.clear()
        autoCancel.clear()
    }

    fun remember(key: String, intent: PendingIntent?, cancelOnOpen: Boolean) {
        if (intent == null) intents.remove(key) else intents[key] = intent
        if (cancelOnOpen) autoCancel.add(key) else autoCancel.remove(key)
    }

    fun forget(key: String) {
        intents.remove(key)
        autoCancel.remove(key)
    }

    fun clearIntents() {
        intents.clear()
        autoCancel.clear()
    }

    /** Fires the content intent. False when it is missing or was cancelled. */
    fun open(key: String): Boolean {
        val intent = intents[key] ?: return false
        return try {
            com.brightblu.parc.core.data.PendingIntentLaunch.send(intent)
            if (key in autoCancel) cancel(key)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun cancel(key: String) {
        try {
            service?.cancelNotification(key)
        } catch (e: Exception) {
            // Listener gone or notification already removed: nothing left to cancel.
        }
    }

    /** Asks the bound listener to re-read the active notifications (the menu section was just enabled). */
    fun refresh() {
        try {
            (service as? ParcNotificationListenerService)?.refreshPeek()
        } catch (e: Exception) {
            // Nothing to refresh while unbound.
        }
    }

    fun cancelAll(keys: Collection<String>) { keys.forEach(::cancel) }

    companion object {
        val shared: AndroidPeekActions by lazy { AndroidPeekActions() }
    }
}
