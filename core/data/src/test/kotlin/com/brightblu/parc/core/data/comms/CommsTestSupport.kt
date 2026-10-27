package com.brightblu.parc.core.data.comms

/** Small builders for the pure Comms tests; every default is a notification Comms should accept. */
internal fun rawNotification(
    packageName: String = CommsPackages.Telegram,
    key: String = "n1",
    isOngoing: Boolean = false,
    flags: Int = 0,
    visibility: Int = 0,
    category: String? = CommsCategories.Message,
    title: String? = "Sam",
    text: String? = "hello",
    bigText: String? = null,
    conversationTitle: String? = null,
    isGroupConversation: Boolean = false,
    postTimeMs: Long = 1_000L,
    messages: List<RawMessage> = emptyList(),
    actions: List<RawAction> = emptyList(),
    picture: CommsImage? = null,
): RawNotification = RawNotification(
    packageName = packageName,
    key = key,
    isOngoing = isOngoing,
    flags = flags,
    visibility = visibility,
    category = category,
    title = title,
    text = text,
    bigText = bigText,
    conversationTitle = conversationTitle,
    isGroupConversation = isGroupConversation,
    postTimeMs = postTimeMs,
    messages = messages,
    actions = actions,
    picture = picture,
)

internal fun rawMessage(
    sender: String? = "Sam",
    text: String? = "hello",
    timeMs: Long = 1_000L,
    dataUri: String? = null,
    image: CommsImage? = null,
): RawMessage = RawMessage(sender, text, timeMs, dataUri, image)

internal fun replyAction(): RawAction = RawAction(hasFreeFormInput = true)

/** The platform's mark-as-read semantic (2) on an action with no input. */
internal fun markReadAction(): RawAction = RawAction(
    hasFreeFormInput = false,
    semanticAction = CommsReplyPlanner.MarkAsReadSemantic,
)

/** A uniform image, useful when a test only cares that a picture is attached. */
internal fun grayImage(argb: Int, width: Int = 2, height: Int = 2): CommsImage =
    OrderedDither.fromPixels(IntArray(width * height) { argb }, width, height)

/** Records every platform call so repository tests assert the exact keys and text. */
internal class FakeCommsActions : CommsActions {

    val replies = mutableListOf<Pair<String, String>>()
    val dismissed = mutableListOf<String>()
    val markedRead = mutableListOf<String>()
    var result: ReplyResult = ReplyResult.Sent
    var markReadResult: MarkReadResult = MarkReadResult.Marked

    override fun reply(notificationKey: String, text: String): ReplyResult {
        replies += notificationKey to text
        return result
    }

    override fun dismiss(notificationKey: String) {
        dismissed += notificationKey
    }

    override fun markAsRead(notificationKey: String): MarkReadResult {
        markedRead += notificationKey
        return markReadResult
    }
}
