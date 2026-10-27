package com.brightblu.parc.core.data.comms

/**
 * A link inside a message body, as a UTF-16 span: [start] inclusive and [endExclusive] exclusive,
 * matching the offsets of the string the parser saw. Immutable, never out of order.
 */
data class CommsLink(val start: Int, val endExclusive: Int) {

    init {
        require(start >= 0) { "start must be non-negative" }
        require(endExclusive > start) { "endExclusive must be after start" }
    }

    /** Number of UTF-16 units in the link. */
    val length: Int get() = endExclusive - start
}

/**
 * A 1-bit thumbnail image (T55): black-and-white pixels packed row-major, left to right, with the
 * least significant bit of the first word holding the top-left pixel.
 *
 * The card caps images at 96x96 and wants a compact bitmask, which is at most 144 longs (1152
 * bytes) per image. A set bit means "ink": the source pixel was brighter than the ordered-dither
 * threshold. [com.brightblu.parc.core.data.comms.OrderedDither] is the only producer and is a pure
 * function; the full-size bitmap is never stored.
 *
 * Immutable: the word list is copied on construction, and equality is by size and content.
 */
class CommsImage private constructor(
    val width: Int,
    val height: Int,
    words: List<Long>,
) {

    /** Packed words; read-only, with padding bits beyond the last pixel cleared. */
    val words: List<Long> = words

    init {
        require(width in 1..MaxSide) { "width must be 1..$MaxSide" }
        require(height in 1..MaxSide) { "height must be 1..$MaxSide" }
        require(this.words.size == wordCountFor(width, height)) {
            "expected ${wordCountFor(width, height)} words, got ${this.words.size}"
        }
    }

    /** Number of packed longs needed for the pixels. */
    val wordCount: Int get() = words.size

    /** True when the pixel at [x], [y] is ink. */
    fun bitAt(x: Int, y: Int): Boolean {
        require(x in 0 until width) { "x out of bounds" }
        require(y in 0 until height) { "y out of bounds" }
        val bit = y * width + x
        return ((words[bit ushr 6] ushr (bit and 63)) and 1L) == 1L
    }

    /** Reads one packed word (for a renderer that walks the mask a word at a time). */
    fun wordAt(index: Int): Long {
        require(index in words.indices) { "word index out of bounds" }
        return words[index]
    }

    override fun equals(other: Any?): Boolean =
        other is CommsImage && width == other.width && height == other.height && words == other.words

    override fun hashCode(): Int = 31 * (31 * width + height) + words.hashCode()

    override fun toString(): String = "CommsImage(${width}x$height, $wordCount words)"

    companion object {
        /** Hard cap from the card; also the packed-mask memory bound. */
        const val MaxSide = 96

        /** Number of longs needed for [width] x [height] pixels. */
        fun wordCountFor(width: Int, height: Int): Int =
            (width * height + 63) / 64

        /**
         * Builds an image from packed words, copying them and clearing any padding bits beyond the
         * last pixel so two masks of the same picture compare equal regardless of the producer.
         */
        fun fromWords(width: Int, height: Int, words: List<Long>): CommsImage {
            val count = wordCountFor(width, height)
            require(words.size == count) { "expected $count words, got ${words.size}" }
            val copy = LongArray(count)
            for (index in 0 until count) copy[index] = words[index]
            val usedInLast = width * height - (count - 1) * 64
            if (usedInLast < 64) {
                copy[count - 1] = copy[count - 1] and ((1L shl usedInLast) - 1L)
            }
            return CommsImage(width, height, copy.toList())
        }
    }
}

/** Identity of a conversation: the posting app plus the app's own conversation key. */
data class CommsConversationId(val app: String, val key: String)

/**
 * One message line. [notificationKey] is the platform's opaque key for the notification the
 * message came from, which is also what dismissal cancels. [text] may be empty for an image-only
 * message. [links] are spans into [text]. [canReply] and [canMarkRead] describe actions the posting
 * app attached to that notification (T76 adds the read one). All values are immutable and
 * non-null.
 */
data class CommsMessage(
    val notificationKey: String,
    val sender: String,
    val text: String,
    val timeMs: Long,
    val image: CommsImage? = null,
    val links: List<CommsLink> = emptyList(),
    val canReply: Boolean = false,
    val canMarkRead: Boolean = false,
)

/**
 * A conversation aggregated from every active notification that maps to it. [messages] are in
 * chronological order (oldest first) and bounded by the inbox; [canReply] is true when at least one
 * message still has a free-form reply action, [canMarkRead] when at least one notification offers
 * the platform's mark-as-read action (T76). The new field defaults to false so every existing
 * positional construction stays valid.
 */
data class CommsConversation(
    val id: CommsConversationId,
    val title: String,
    val messages: List<CommsMessage>,
    val canReply: Boolean,
    val updatedAtMs: Long,
    val canMarkRead: Boolean = false,
) {
    val app: String get() = id.app
    val key: String get() = id.key
}

/** Outcome of [CommsRepository.reply]. */
enum class ReplyResult {
    /** The notification's reply PendingIntent accepted the text. */
    Sent,

    /** The notification has no free-form reply action (or it vanished). */
    NoReplyAction,

    /** The reply action existed but could not be filled or sent. */
    Failed,
}

/**
 * Package names Parc treats as message apps when Comms has never been configured. The set is
 * stored in DataStore as package names, never as labels, and stays empty if the user disables every
 * app (an empty allow-list shows nothing, as the card requires).
 */
object CommsPackages {
    const val Telegram = "org.telegram.messenger"
    const val WhatsApp = "com.whatsapp"
    const val Instagram = "com.instagram.android"
    const val Messages = "com.google.android.apps.messaging"
    const val Signal = "org.thoughtcrime.securesms"
    const val Discord = "com.discord"
    const val Imo = "com.imo.android.imoim"

    /** The card's default allow-list, in the order the settings UI should show it. */
    val Default: Set<String> = linkedSetOf(
        Telegram,
        WhatsApp,
        Instagram,
        Messages,
        Signal,
        Discord,
        Imo,
    )
}

/**
 * What the parser may keep: the master Comms switch and the app allow-list. The inbox applies it
 * on every post and again when settings change, so disabling Comms also throws away what was held.
 */
data class CommsPolicy(
    val enabled: Boolean = true,
    val allowedPackages: Set<String> = CommsPackages.Default,
)
