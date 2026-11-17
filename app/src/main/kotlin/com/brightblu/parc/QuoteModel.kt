package com.brightblu.parc

import java.time.LocalDate
import kotlinx.serialization.json.*

internal data class Quote(
    val id: String,
    val text: String,
    val author: String = "",
    val work: String = "",
    val translator: String = "",
) {
    val credit: String get() = listOf(author, work, translator.takeIf { it.isNotBlank() }?.let { "Translated by $it" }.orEmpty())
        .filter { it.isNotBlank() }.joinToString(" · ")
}

internal data class QuoteOrder(
    val day: String = "",
    val current: String? = null,
    val remaining: List<String> = emptyList(),
    val shown: Set<String> = emptySet(),
)

internal data class QuoteLibrary(
    val own: List<Quote> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val order: QuoteOrder = QuoteOrder(),
) {
    fun visible(starters: List<Quote>): List<Quote> = (starters + own).filterNot { it.id in hidden }
    fun save(quote: Quote, starterId: String? = null): QuoteLibrary {
        require(QuoteRules.valid(quote))
        require(quote.id.startsWith("own-"))
        return copy(own = own.filterNot { it.id == quote.id } + quote,
            hidden = (hidden - quote.id) + listOfNotNull(starterId))
    }
    fun hide(id: String): QuoteLibrary = copy(hidden = hidden + id)
    fun delete(id: String): QuoteLibrary = if (own.any { it.id == id })
        copy(own = own.filterNot { it.id == id }, hidden = hidden - id) else hide(id)
    fun restoreStarters(starters: List<Quote>): QuoteLibrary = copy(hidden = hidden - starters.map { it.id }.toSet())
}

internal object QuoteRules {
    const val MaxText = 2_000
    const val MaxCredit = 240
    fun valid(q: Quote): Boolean = q.id.isNotBlank() && q.text.isNotBlank() && q.text.length <= MaxText &&
        listOf(q.author, q.work, q.translator).all { it.length <= MaxCredit }

    /** Reconcile additions/deletions before picking. A new id joins the unshown tail of this cycle. */
    fun pick(order: QuoteOrder, visibleIds: List<String>, today: LocalDate, another: Boolean = false): QuoteOrder {
        val ids = visibleIds.distinct()
        val seen = order.shown.intersect(ids.toSet())
        val pending = (order.remaining + ids).distinct().filter { it in ids && it !in seen }
        val day = today.toString()
        if (!another && order.day == day && order.current in ids) return order.copy(remaining = pending, shown = seen)
        if (ids.isEmpty()) return QuoteOrder(day = day)
        // No adjacent repeat at a cycle boundary when more than one quote exists.
        val queue = pending.ifEmpty { ids.filterNot { it == order.current } + ids.filter { it == order.current } }
        return QuoteOrder(day, queue.first(), queue.drop(1), (if (pending.isEmpty()) emptySet() else seen) + queue.first())
    }

    /** Only text/plain SEND is accepted. Overlength text stays visible in the editor until shortened. */
    fun sharedText(action: String?, type: String?, text: CharSequence?): String? =
        if (action == "android.intent.action.SEND" && type == "text/plain") text?.toString()?.takeIf { it.isNotBlank() } else null
}

/** Framework-free JSON; missing fields decode as defaults, unknown fields are ignored. Invalid documents fail closed. */
internal object QuotesJson {
    const val OwnKey = "own_v1"
    const val HiddenKey = "hidden_v1"
    const val OrderKey = "order_v1"
    private fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun quotes(raw: String?): List<Quote> {
        if (raw.isNullOrBlank()) return emptyList()
        return Json.parseToJsonElement(raw).jsonArray.map { entry ->
            val o = entry.jsonObject
            Quote(o.string("id"), o.string("text"), o.string("author"), o.string("work"), o.string("translator"))
                .also { require(QuoteRules.valid(it)) { "Invalid quote" } }
        }.also { require(it.map(Quote::id).distinct().size == it.size) }
    }
    fun encodeQuotes(quotes: List<Quote>): String = buildJsonArray {
        quotes.forEach { q -> add(buildJsonObject {
            put("id", q.id); put("text", q.text); put("author", q.author); put("work", q.work)
            if (q.translator.isNotEmpty()) put("translator", q.translator)
        }) }
    }.toString()
    fun ids(raw: String?): List<String> = if (raw.isNullOrBlank()) emptyList() else
        Json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }.distinct()
    fun encodeIds(ids: Collection<String>): String = JsonArray(ids.map(::JsonPrimitive)).toString()
    fun order(raw: String?): QuoteOrder {
        if (raw.isNullOrBlank()) return QuoteOrder()
        val o = Json.parseToJsonElement(raw).jsonObject
        return QuoteOrder(o.string("day"), o.string("current").ifBlank { null },
            ids(o["remaining"]?.toString()), ids(o["shown"]?.toString()).toSet())
    }
    fun encodeOrder(order: QuoteOrder): String = buildJsonObject {
        put("day", order.day); order.current?.let { put("current", it) }
        put("remaining", JsonArray(order.remaining.map(::JsonPrimitive)))
        put("shown", JsonArray(order.shown.map(::JsonPrimitive)))
    }.toString()
    fun decode(own: String? = null, hidden: String? = null, order: String? = null): QuoteLibrary =
        QuoteLibrary(quotes(own).also { require(it.all { q -> q.id.startsWith("own-") }) }, ids(hidden).toSet(), order(order))
}
