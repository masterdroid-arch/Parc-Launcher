package com.brightblu.parc

import android.content.Context
import kotlinx.serialization.json.*

internal data class ChangeItem(val headline: String, val text: String)
internal data class ReleaseNotes(val versionName: String, val versionCode: Int, val date: String, val items: List<ChangeItem>)

internal fun parseChangelog(text: String): List<ReleaseNotes> = Json.parseToJsonElement(text).jsonArray.map { value ->
    val entry = value.jsonObject
    ReleaseNotes(entry.getValue("versionName").jsonPrimitive.content,
        entry.getValue("versionCode").jsonPrimitive.int, entry.getValue("date").jsonPrimitive.content,
        entry.getValue("items").jsonArray.map { item -> item.jsonObject.let {
            ChangeItem(it.getValue("headline").jsonPrimitive.content, it.getValue("text").jsonPrimitive.content)
        } })
}
internal fun loadChangelog(context: Context): List<ReleaseNotes> =
    context.assets.open("changelog.json").bufferedReader().use { parseChangelog(it.readText()) }.sortedByDescending { it.versionCode }

internal interface SeenVersionStore {
    var lastSeen: Int?
}
/** Keep the high-water mark on downgrade; acknowledge before display to survive recreation. */
internal fun claimUpdate(store: SeenVersionStore, current: Int): Boolean {
    val previous = store.lastSeen
    if (previous == null || current > previous) store.lastSeen = current
    return previous != null && current > previous
}
internal class PrivateSeenVersionStore(context: Context) : SeenVersionStore {
    private val prefs = context.applicationContext.getSharedPreferences("release_seen", Context.MODE_PRIVATE)
    override var lastSeen: Int?
        get() = if (prefs.contains("version")) prefs.getInt("version", 0) else null
        set(value) { if (value != null) check(prefs.edit().putInt("version", value).commit()) }
}
