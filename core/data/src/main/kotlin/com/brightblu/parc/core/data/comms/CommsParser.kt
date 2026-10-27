package com.brightblu.parc.core.data.comms

/**
 * Category values accepted as messages. Verified against `android-36`:
 * `Notification.CATEGORY_MESSAGE` = "msg", `CATEGORY_SOCIAL` = "social",
 * `CATEGORY_EMAIL` = "email". They are repeated here as literals so the parser stays a pure Kotlin
 * file; the extractor passes the platform string through unchanged.
 */
object CommsCategories {
    const val Message = "msg"
    const val Social = "social"
    const val Email = "email"
    val Accepted: Set<String> = setOf(Message, Social, Email)
}

/**
 * A notification reduced to what Commms is allowed to see. Built by the Android extractor and by
 * tests; contains no Android types, so every keep/drop rule below is unit-tested on the JVM.
 *
 * [flags], [visibility] and [category] are the platform values. [picture] is the already-dithered
 * `EXTRA_PICTURE`, if any. [messages] are MessagingStyle messages in the order the app stored them.
 */
internal data class RawNotification(
    val packageName: String,
    val key: String,
    val isOngoing: Boolean = false,
    val flags: Int = 0,
    val visibility: Int = 0,
    val category: String? = null,
    val title: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val conversationTitle: String? = null,
    val isGroupConversation: Boolean = false,
    val postTimeMs: Long = 0L,
    val messages: List<RawMessage> = emptyList(),
    val actions: List<RawAction> = emptyList(),
    val picture: CommsImage? = null,
)

/** One MessagingStyle message; [dataUri] is the optional image the app attached to it. */
internal data class RawMessage(
    val sender: String? = null,
    val text: String? = null,
    val timeMs: Long = 0L,
    val dataUri: String? = null,
    val image: CommsImage? = null,
)

/**
 * An action reduced to the two facts Comms uses: does it carry free-form input (reply), and does it
 * declare the platform's mark-as-read semantic (T76)? [semanticAction] is the raw
 * `Notification.Action.getSemanticAction()` value.
 */
internal data class RawAction(
    val hasFreeFormInput: Boolean,
    val semanticAction: Int = 0,
)

/** One notification after filtering, mapped onto one conversation. */
internal data class ParsedNotification(
    val notificationKey: String,
    val app: String,
    val conversationKey: String,
    val title: String,
    val messages: List<CommsMessage>,
    val updatedAtMs: Long,
)

/** Reply action choice, pure and shared by the parser and the Android action port. */
internal object CommsReplyPlanner {

    /**
     * `Notification.Action.SEMANTIC_ACTION_MARK_AS_READ` (API 31) is the literal 2. It is repeated
     * here, like the filter constants, so this file stays pure Kotlin and unit-testable.
     */
    const val MarkAsReadSemantic = 2

    /** True when any action offers free-form text input. */
    fun canReply(actions: List<RawAction>): Boolean =
        actions.any { it.hasFreeFormInput }

    /** Index of the first free-form reply action, or null. */
    fun replyAction(actions: List<RawAction>): Int? =
        actions.indexOfFirst { it.hasFreeFormInput }.takeIf { it >= 0 }

    /** True when any action declares the platform's mark-as-read semantic. */
    fun canMarkAsRead(actions: List<RawAction>): Boolean =
        actions.any { it.semanticAction == MarkAsReadSemantic }
}

/**
 * Turns a [RawNotification] into a [ParsedNotification], or null when the card says ignore it.
 *
 * Rules, in this order:
 * 1. Comms must be enabled and the package on the allow-list (an empty list shows nothing).
 * 2. Ongoing notifications, group summaries and `VISIBILITY_SECRET` are ignored.
 * 3. A category other than message/social/email is not a conversation. A missing category is
 *    accepted, because some messaging apps omit it while still posting a MessagingStyle body.
 * 4. MessagingStyle messages win. Each one keeps its sender, text, time, notification key, own
 *    image and extracted links. `EXTRA_PICTURE` fills the newest message that has no image.
 * 5. Without a style, `bigText` (if present) else `text` becomes one message; title is the sender.
 * 6. Blank messages without an image are dropped. A notification with nothing left is ignored.
 *
 * Pure: no clock, no Android type, no I/O. The platform flag and visibility values used here are
 * compile-time constants of `android.app.Notification` (`FLAG_ONGOING_EVENT` = 2,
 * `FLAG_GROUP_SUMMARY` = 512, `VISIBILITY_SECRET` = -1), repeated for the same reason.
 */
internal class CommsParser(
    private val maxMessagesPerNotification: Int = MaxMessagesPerNotification,
) {

    init {
        require(maxMessagesPerNotification >= 1) { "maxMessagesPerNotification must be positive" }
    }

    fun parse(raw: RawNotification, policy: CommsPolicy): ParsedNotification? {
        if (!policy.enabled) return null
        if (raw.packageName !in policy.allowedPackages) return null
        if (raw.isOngoing || raw.flags and FlagOngoing != 0) return null
        if (raw.flags and FlagGroupSummary != 0) return null
        if (raw.visibility == VisibilitySecret) return null
        if (raw.category != null && raw.category !in CommsCategories.Accepted) return null

        val canReply = CommsReplyPlanner.canReply(raw.actions)
        val canMarkRead = CommsReplyPlanner.canMarkAsRead(raw.actions)
        val title = raw.conversationTitle?.clean()
            ?: raw.title?.clean()
            ?: raw.messages.lastOrNull()?.sender?.clean()
            ?: ""
        val conversationKey = raw.conversationTitle?.clean()
            ?: raw.title?.clean()
            ?: raw.messages.firstOrNull()?.sender?.clean()
            ?: raw.key

        val styled = raw.messages.mapNotNull { message ->
            val text = message.text?.trim().orEmpty()
            val image = message.image
            if (text.isEmpty() && image == null) return@mapNotNull null
            val sender = message.sender?.clean() ?: raw.title?.clean().orEmpty()
            val time = if (message.timeMs > 0L) message.timeMs else raw.postTimeMs
            CommsMessage(
                notificationKey = raw.key,
                sender = sender,
                text = text,
                timeMs = time,
                image = image,
                links = CommsLinks.extract(text),
                canReply = canReply,
                canMarkRead = canMarkRead,
            )
        }.sortedWith(compareBy { it.timeMs })

        val messages = if (styled.isNotEmpty()) {
            attachPicture(styled, raw.picture).takeLast(maxMessagesPerNotification)
        } else {
            val text = (raw.bigText?.clean() ?: raw.text?.clean()).orEmpty()
            if (text.isEmpty() && raw.picture == null) return null
            val fallbackSender = raw.title?.clean().orEmpty()
            listOf(
                CommsMessage(
                    notificationKey = raw.key,
                    sender = fallbackSender,
                    text = text,
                    timeMs = raw.postTimeMs,
                    image = raw.picture,
                    links = CommsLinks.extract(text),
                    canReply = canReply,
                    canMarkRead = canMarkRead,
                ),
            )
        }

        val updated = messages.maxOfOrNull { it.timeMs } ?: raw.postTimeMs
        return ParsedNotification(
            notificationKey = raw.key,
            app = raw.packageName,
            conversationKey = conversationKey,
            title = title,
            messages = messages,
            updatedAtMs = maxOf(updated, raw.postTimeMs),
        )
    }

    private fun attachPicture(messages: List<CommsMessage>, picture: CommsImage?): List<CommsMessage> {
        if (picture == null) return messages
        val target = messages.indexOfLast { it.image == null }
        if (target < 0) return messages
        return messages.mapIndexed { index, message ->
            if (index == target) message.copy(image = picture) else message
        }
    }

    private fun String.clean(): String? = trim().takeIf { it.isNotEmpty() }

    private companion object {
        const val FlagOngoing = 2
        const val FlagGroupSummary = 512
        const val VisibilitySecret = -1

        /** The platform keeps at most 25 MessagingStyle messages; 40 leaves headroom. */
        const val MaxMessagesPerNotification = 40
    }
}
