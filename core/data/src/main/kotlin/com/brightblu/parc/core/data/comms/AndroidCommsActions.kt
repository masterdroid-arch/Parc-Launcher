package com.brightblu.parc.core.data.comms

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.brightblu.parc.core.data.notifications.ParcNotificationListenerService
import java.util.concurrent.ConcurrentHashMap

/**
 * Android side of [CommsActions] (T55). It keeps the current notification by the platform's opaque
 * key only while the listener is connected, so reply can find the exact action the app posted.
 *
 * Reply builds a fresh Intent, attaches the text under the RemoteInput's result key with
 * `RemoteInput.addResultsToIntent`, and sends the action's PendingIntent with the application
 * context. The three [ReplyResult] values map one-to-one onto: send returned, no free-form action
 * exists (or the notification has vanished), and anything threw. Nothing is logged.
 *
 * Mark-as-read (T76) sends the PendingIntent of the action whose
 * `Notification.Action.getSemanticAction()` is `SEMANTIC_ACTION_MARK_AS_READ` (API 31, the module's
 * minSdk is 33). It returns [MarkReadResult.Unavailable] when the notification is gone or carries no
 * such action, and [MarkReadResult.Failed] when the send throws.
 *
 * Dismiss calls the listener service's `cancelNotification(key)`, which is the only supported way
 * for a listener to remove another app's notification. If the listener is unbound the call is a
 * no-op, because no API can cancel it then.
 *
 * The maps are concurrent because posts arrive on a binder thread while the UI may reply. The
 * service attaches itself on connect and detaches on disconnect, which also clears every retained
 * notification.
 */
internal class AndroidCommsActions : CommsActions {

    @Volatile private var context: Context? = null
    @Volatile private var service: ParcNotificationListenerService? = null
    private val notifications = ConcurrentHashMap<String, Notification>()

    fun bindContext(appContext: Context) {
        context = appContext
    }

    fun attach(listener: ParcNotificationListenerService) {
        service = listener
        bindContext(listener.applicationContext)
    }

    fun detach(listener: ParcNotificationListenerService) {
        if (service === listener) {
            service = null
            notifications.clear()
        }
    }

    fun remember(notificationKey: String, notification: Notification) {
        notifications[notificationKey] = notification
    }

    fun forget(notificationKey: String) {
        notifications.remove(notificationKey)
    }

    fun replaceAll(current: Map<String, Notification>) {
        notifications.clear()
        notifications.putAll(current)
    }

    override fun reply(notificationKey: String, text: String): ReplyResult {
        val notification = notifications[notificationKey] ?: return ReplyResult.NoReplyAction
        val appContext = context ?: return ReplyResult.Failed
        val (action, input) = findReplyAction(notification) ?: return ReplyResult.NoReplyAction
        val intent = Intent()
        val results = Bundle().apply { putCharSequence(input.resultKey, text) }
        return try {
            RemoteInput.addResultsToIntent(arrayOf(input), intent, results)
            action.actionIntent.send(appContext, 0, intent)
            ReplyResult.Sent
        } catch (e: PendingIntent.CanceledException) {
            ReplyResult.Failed
        } catch (e: RuntimeException) {
            // A revoked grant or a malformed action must not crash the launcher. No content logged.
            ReplyResult.Failed
        }
    }

    override fun markAsRead(notificationKey: String): MarkReadResult {
        val notification = notifications[notificationKey] ?: return MarkReadResult.Unavailable
        val appContext = context ?: return MarkReadResult.Failed
        val action = findMarkAsReadAction(notification) ?: return MarkReadResult.Unavailable
        return try {
            // The platform requires an Intent argument; mark-as-read has no extras to carry.
            action.actionIntent.send(appContext, 0, Intent())
            MarkReadResult.Marked
        } catch (e: PendingIntent.CanceledException) {
            MarkReadResult.Failed
        } catch (e: RuntimeException) {
            // Same rule as reply: a revoked grant must not crash the launcher and nothing is logged.
            MarkReadResult.Failed
        }
    }

    override fun dismiss(notificationKey: String) {
        val listener = service ?: return
        try {
            listener.cancelNotification(notificationKey)
        } catch (e: RuntimeException) {
            // Access can be revoked between the tap and the binder call; there is nothing to do.
        }
    }

    /**
     * First action whose remote inputs include a usable free-form field. Data-only inputs cannot
     * carry typed text, and a blank result key cannot receive it.
     */
    private fun findReplyAction(notification: Notification): Pair<Notification.Action, RemoteInput>? {
        val actions = notification.actions ?: return null
        for (action in actions) {
            val inputs = action.remoteInputs ?: continue
            for (input in inputs) {
                if (!input.allowFreeFormInput) continue
                if (input.isDataOnly) continue
                if (input.resultKey.isNullOrBlank()) continue
                return action to input
            }
        }
        return null
    }

    /** The action the posting app declared as mark-as-read, if it posted one. */
    private fun findMarkAsReadAction(notification: Notification): Notification.Action? =
        notification.actions?.firstOrNull {
            it.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ
        }

    companion object {
        /** The listener and the repository meet here, because the system creates the service. */
        val shared: AndroidCommsActions by lazy { AndroidCommsActions() }
    }
}
