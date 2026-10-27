package com.brightblu.parc.core.data.comms

import android.app.Notification
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification

/**
 * Reads a [StatusBarNotification] into the parser's Android-free [RawNotification] (T55). This is
 * the only place the extras are touched, and it reads only the fields the card names:
 *
 * - ongoing / group-summary / visibility / category / post time from the platform wrapper,
 * - title, text, bigText, conversation title, group flag and `EXTRA_PICTURE`,
 * - `EXTRA_MESSAGES` decoded through `Notification.MessagingStyle.Message.getMessagesFromBundleArray`,
 * - whether any action carries a free-form RemoteInput (reply) and each action's semantic code
 *   (T76 reads the platform's mark-as-read one from it).
 *
 * Images are converted to [CommsImage] here so no full-size bitmap reaches the inbox. A malformed
 * message array is treated as no MessagingStyle and falls back to title/text; it never throws into
 * the listener. Nothing is logged and nothing leaves the process.
 */
internal class CommsNotificationExtractor(
    private val images: CommsImageLoader,
) {

    /** Returns null only when the platform hands over a notification with no object at all. */
    fun extract(sbn: StatusBarNotification): RawNotification? {
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: Bundle.EMPTY
        val picture = extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
            ?.let(images::fromBitmap)

        return RawNotification(
            packageName = sbn.packageName,
            key = sbn.key,
            isOngoing = sbn.isOngoing,
            flags = notification.flags,
            visibility = notification.visibility,
            category = notification.category,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
            isGroupConversation = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
            postTimeMs = sbn.postTime,
            messages = messages(extras),
            actions = notification.actions?.map { action ->
                RawAction(
                    hasFreeFormInput = action.remoteInputs?.any { it.allowFreeFormInput } == true,
                    semanticAction = action.semanticAction,
                )
            }.orEmpty(),
            picture = picture,
        )
    }

    private fun messages(extras: Bundle): List<RawMessage> {
        val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
            ?: return emptyList()
        val styleMessages = try {
            val array = Array<Parcelable>(bundles.size) { bundles[it] }
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(array)
        } catch (e: RuntimeException) {
            return emptyList()
        }

        return styleMessages.map { message ->
            val sender = message.senderPerson?.name?.toString()
            val uri = message.dataUri?.toString()
            RawMessage(
                sender = sender,
                text = message.text?.toString(),
                timeMs = message.timestamp,
                dataUri = uri,
                image = images.fromUri(uri),
            )
        }
    }
}
