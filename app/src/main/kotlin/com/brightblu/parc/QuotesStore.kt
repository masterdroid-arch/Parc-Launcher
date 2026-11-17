package com.brightblu.parc

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.brightblu.parc.core.data.backup.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate

internal val Context.quotesDataStore by preferencesDataStore(name = "quotes",
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler("quotes"))

internal class QuotesStore(
    private val data: DataStore<Preferences>,
    private val loadStarters: suspend () -> List<Quote>,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    private val ownKey = stringPreferencesKey(QuotesJson.OwnKey)
    private val hiddenKey = stringPreferencesKey(QuotesJson.HiddenKey)
    private val orderKey = stringPreferencesKey(QuotesJson.OrderKey)
    private fun read(prefs: Preferences) = QuotesJson.decode(prefs[ownKey], prefs[hiddenKey], prefs[orderKey])
    val library = data.data.map(::read)
    suspend fun starters(): List<Quote> = loadStarters()

    /** T192 entry point: persists today's pick before returning; null means every quote is hidden. */
    suspend fun quoteForToday(): Quote? = change { it }
    suspend fun another(): Quote? = change(another = true) { it }
    suspend fun save(quote: Quote, starterId: String? = null) { change { it.save(quote, starterId) } }
    suspend fun hide(id: String) { change { it.hide(id) } }
    suspend fun delete(id: String) { change { it.delete(id) } }
    suspend fun show(id: String) { change { it.copy(hidden = it.hidden - id) } }
    suspend fun restoreStarters() {
        val starters = starters()
        change { it.restoreStarters(starters) }
    }

    private suspend fun change(another: Boolean = false, transform: (QuoteLibrary) -> QuoteLibrary): Quote? {
        val starters = starters()
        var picked: Quote? = null
        data.edit { prefs ->
            val next = transform(read(prefs))
            val visible = next.visible(starters)
            val order = QuoteRules.pick(next.order, visible.map { it.id }, today(), another)
            val values = listOf(Pair(ownKey, QuotesJson.encodeQuotes(next.own)), Pair(hiddenKey, QuotesJson.encodeIds(next.hidden)),
                Pair(orderKey, QuotesJson.encodeOrder(order)))
            values.forEach { (key, text) ->
                require(text.length <= BackupSpecs.quotes.key(key.name)!!.maxLength) { "Quote collection is full" }
                if (prefs[key] != text) prefs[key] = text
            }
            picked = visible.firstOrNull { it.id == order.current }
        }
        return picked
    }

    companion object {
        fun create(context: Context): QuotesStore {
            val app = context.applicationContext
            // Asset parsing is off the UI thread. A store instance caches only immutable bundled text.
            var cached: List<Quote>? = null
            return QuotesStore(app.quotesDataStore, {
                cached ?: withContext(Dispatchers.IO) {
                    QuotesJson.quotes(app.assets.open("starter_quotes.json").bufferedReader().use { it.readText() })
                }.also { cached = it }
            })
        }
    }
}

/** Refuse malformed restores before they can replace a user's collection. */
internal class QuotesBackupAccess(private val delegate: StoreAccess) : StoreAccess {
    override suspend fun read(spec: BackupStoreSpec): Map<String, Any> = delegate.read(spec)
    override suspend fun write(spec: BackupStoreSpec, values: Map<String, Any>) {
        QuotesJson.decode(values[QuotesJson.OwnKey] as? String, values[QuotesJson.HiddenKey] as? String,
            values[QuotesJson.OrderKey] as? String)
        delegate.write(spec, values)
    }
}
