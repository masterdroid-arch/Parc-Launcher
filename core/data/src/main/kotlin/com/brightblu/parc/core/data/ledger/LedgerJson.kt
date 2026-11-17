package com.brightblu.parc.core.data.ledger

import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Codec of the persisted ledger document (T185), written by hand over the JSON tree so fields this
 * build does not know are kept on both the document and every day.
 *
 * ```
 * { "v": 1, "first": 20400, "backfilled": true,
 *   "days":   { "20400": { "wifi": 1, "mobile": 2, "screen": 3, "steps": null, "pickups": null,
 *                          "phoneFree": null, "closed": true,
 *                          "appData": {"pkg": 1}, "appScreen": {"pkg": 1} } },
 *   "months": { "2026-11": { "data": {"pkg": 1}, "screen": {"pkg": 1} } } }
 * ```
 * Days are keyed by epoch day (a local date counted in days), so a time zone change never rewrites a
 * stored key. Reading is tolerant: an empty, blank or corrupt document is an empty ledger, a missing
 * field is null (or empty for a map), a wrong-typed field is treated as missing, and a negative
 * measurement is dropped rather than shown. Writing is canonical: days in date order, null fields
 * left out.
 */
internal object LedgerJson {
    const val Version = 1

    private val json = Json { ignoreUnknownKeys = true }

    private val knownDay = setOf(
        "wifi", "mobile", "screen", "steps", "pickups", "phoneFree", "firstPickup", "bins", "closed", "appData", "appScreen",
    )
    private val knownRoot = setOf("v", "first", "backfilled", "days", "months")

    fun encode(ledger: DayLedger): String {
        val root = buildJsonObject {
            put("v", Version)
            ledger.firstDate?.let { put("first", it.toEpochDay()) }
            if (ledger.backfilled) put("backfilled", true)
            put("days", buildJsonObject {
                for (day in ledger.days.values.sortedBy { it.date }) {
                    put(day.date.toEpochDay().toString(), encodeDay(day))
                }
            })
            put("months", buildJsonObject {
                for ((key, month) in ledger.months.toSortedMap()) {
                    put(key, buildJsonObject {
                        if (month.data.isNotEmpty()) put("data", longMap(month.data))
                        if (month.screen.isNotEmpty()) put("screen", longMap(month.screen))
                    })
                }
            })
            for ((key, value) in ledger.extra) if (key !in knownRoot) put(key, value)
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    private fun encodeDay(day: LedgerDay): JsonObject = buildJsonObject {
        day.wifiBytes?.let { put("wifi", it) }
        day.mobileBytes?.let { put("mobile", it) }
        day.screenOnMillis?.let { put("screen", it) }
        day.steps?.let { put("steps", it) }
        day.pickups?.let { put("pickups", it) }
        day.phoneFreeMillis?.let { put("phoneFree", it) }
        day.firstPickupMinute?.let { put("firstPickup", it) }
        day.rhythmBins?.let { put("bins", it) }
        if (day.closed) put("closed", true)
        if (day.appData.isNotEmpty()) put("appData", longMap(day.appData))
        if (day.appScreen.isNotEmpty()) put("appScreen", longMap(day.appScreen))
        for ((key, value) in day.extra) if (key !in knownDay) put(key, value)
    }

    private fun longMap(map: Map<String, Long>): JsonObject = buildJsonObject {
        for ((key, value) in map.toSortedMap()) put(key, value)
    }

    fun decode(text: String?): DayLedger {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return DayLedger.Empty
        val root = try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (invalid: Exception) {
            null
        } ?: return DayLedger.Empty

        val days = LinkedHashMap<LocalDate, LedgerDay>()
        (root["days"] as? JsonObject)?.forEach { (key, value) ->
            val epoch = key.toLongOrNull() ?: return@forEach
            val date = try { LocalDate.ofEpochDay(epoch) } catch (invalid: Exception) { return@forEach }
            val obj = value as? JsonObject ?: return@forEach
            days[date] = decodeDay(date, obj)
        }
        val months = LinkedHashMap<String, MonthApps>()
        (root["months"] as? JsonObject)?.forEach { (key, value) ->
            val obj = value as? JsonObject ?: return@forEach
            if (!MonthKey.matches(key)) return@forEach
            months[key] = MonthApps(
                data = readLongMap(obj["data"]),
                screen = readLongMap(obj["screen"]),
            )
        }
        val storedFirst = (root["first"] as? JsonPrimitive)?.longOrNull?.let {
            try { LocalDate.ofEpochDay(it) } catch (invalid: Exception) { null }
        }
        val oldest = days.values.filter { it.hasData }.minOfOrNull { it.date }
        // The stored first date survives even when its day is gone, but never claims a date later
        // than a day that has data.
        val first = listOfNotNull(storedFirst, oldest).minOrNull()
        return DayLedger(
            days = days,
            months = months,
            firstDate = first,
            backfilled = (root["backfilled"] as? JsonPrimitive)?.booleanOrNull == true,
            extra = root.filterKeys { it !in knownRoot },
        )
    }

    private val MonthKey = Regex("""\d{4}-\d{2}""")

    private fun decodeDay(date: LocalDate, obj: JsonObject): LedgerDay = LedgerDay(
        date = date,
        wifiBytes = obj.nonNegativeLong("wifi"),
        mobileBytes = obj.nonNegativeLong("mobile"),
        screenOnMillis = obj.nonNegativeLong("screen"),
        steps = obj.nonNegativeLong("steps")?.takeIf { it <= Int.MAX_VALUE }?.toInt(),
        pickups = obj.nonNegativeLong("pickups")?.takeIf { it <= Int.MAX_VALUE }?.toInt(),
        phoneFreeMillis = obj.nonNegativeLong("phoneFree"),
        firstPickupMinute = obj.nonNegativeLong("firstPickup")?.takeIf { it < 1_440L }?.toInt(),
        rhythmBins = (obj["bins"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length == 72 && it.all { c -> c in "0123456789abcdef" } },
        closed = (obj["closed"] as? JsonPrimitive)?.booleanOrNull == true,
        appData = readLongMap(obj["appData"]),
        appScreen = readLongMap(obj["appScreen"]),
        extra = obj.filterKeys { it !in knownDay },
    )

    private fun JsonObject.nonNegativeLong(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.longOrNull?.takeIf { it >= 0L }
    }

    private fun readLongMap(element: JsonElement?): Map<String, Long> {
        val obj = element as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, Long>()
        for ((key, value) in obj) {
            val v = (value as? JsonPrimitive)?.longOrNull ?: continue
            if (v > 0L) out[key] = v
        }
        return out
    }
}
