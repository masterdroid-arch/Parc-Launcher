package com.brightblu.parc.core.data.backup

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.LayoutJson
import com.brightblu.parc.model.LayoutRepository
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The document could not be read as a Parc backup at all (truncated, not JSON, too big, too new). */
class BackupFormatException(message: String) : Exception(message)

/** What a restore will (or did) change. Counts only; never names an app or a value. */
data class RestoreSummary(
    val folders: Int,
    val apps: Int,
    val settings: Int,
    val skipped: Int = 0,
    val hasLayout: Boolean = true,
) {
    /** `restored 3 folders, 41 apps, 12 settings` without the verb; sections the file lacks are omitted. */
    fun describe(): String {
        val parts = ArrayList<String>(3)
        if (hasLayout) {
            parts += plural(folders, "folder")
            parts += plural(apps, "app")
        }
        parts += plural(settings, "setting")
        return parts.joinToString(", ")
    }

    private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
}

/**
 * A validated backup waiting for confirmation. Everything in it already passed the type and size
 * checks, so [FullBackup.apply] cannot be fed hostile values. Immutable.
 */
class ParsedBackup internal constructor(
    val version: Int,
    internal val layoutJson: String?,
    internal val values: Map<String, Map<String, Any>>,
    val summary: RestoreSummary,
)

/**
 * Full backup and restore (T87): the layout (arcs, folders, pins, hidden apps, which also carries the
 * main-app arrangement) plus the allow-listed settings of every store.
 *
 * Document, version 2:
 * ```
 * { "schema": "parc-backup", "backupVersion": 2,
 *   "layout": { ...the version 1 layout document... },
 *   "settings": { "<storeId>": { "<key>": value, ... }, ... } }
 * ```
 * Version 1 was the bare layout document (no `schema` key); it is still read and restores the layout
 * only. Unknown keys and unknown stores are ignored; a section that fails validation is skipped and
 * counted; a file with nothing usable is refused. Keys missing from the file leave the current value
 * alone, so a partial file restores exactly what it contains.
 *
 * Thread-safety: stateless apart from the injected stores; [export] and [apply] suspend.
 */
class FullBackup(
    private val layout: LayoutRepository,
    private val stores: List<BackupStore>,
) {
    /** The whole backup as JSON text. */
    suspend fun export(): String {
        val values = LinkedHashMap<String, Map<String, Any>>()
        for (store in stores) {
            val read = try {
                store.access.read(store.spec)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyMap()
            }
            val clean = read.filter { (name, v) -> store.spec.key(name)?.let { BackupValues.matches(it, v) } == true }
            if (clean.isNotEmpty()) values[store.spec.id] = clean
        }
        return BackupCodec.encode(LayoutJson.decode(layout.exportLayout()), values)
    }

    /** Validates [text]; throws [BackupFormatException] when nothing in it can be restored. */
    fun parse(text: String): ParsedBackup = BackupCodec.parse(text, stores.map { it.spec })

    /**
     * Applies a parsed backup: the layout first (atomic; a failure there changes nothing), then each
     * store. Returns the summary of what was written; a store that fails to write is counted as skipped.
     */
    suspend fun apply(parsed: ParsedBackup): RestoreSummary {
        parsed.layoutJson?.let { layout.importLayout(it) }
        var written = 0
        var failed = 0
        for (store in stores) {
            val incoming = parsed.values[store.spec.id]
            // T132: legacy backups predate hub_size. Restoring one must not keep a target's Tall
            // override. This migration changes no other missing-key/partial-restore behaviour.
            val values = if (store.spec.id == BackupSpecs.Hub && incoming?.containsKey("hub_size") != true)
                incoming.orEmpty() + ("hub_size" to "standard") else incoming ?: continue
            try {
                store.access.write(store.spec, if (store.spec.id == BackupSpecs.Hub && "clock_options" !in values)
                    values + ("clock_options" to "true|false|") else values)
                written += incoming?.size ?: 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += incoming?.size ?: 0
            }
        }
        return parsed.summary.copy(settings = written, skipped = parsed.summary.skipped + failed)
    }
}

/** Pure JSON codec for the backup envelope. */
object BackupCodec {
    const val Schema = "parc-backup"
    const val CurrentVersion = 2

    /** Refuse anything larger than this before parsing: allows an optional 2 MB wallpaper encoded as base64 plus settings. */
    const val MaxDocumentChars = 5_000_000

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = false }

    fun encode(layout: ParcLayout, values: Map<String, Map<String, Any>>): String {
        val obscureSettings = layout.folders.any { com.brightblu.parc.model.LockedFolderTag in it.tags }
        val root = buildJsonObject {
            put("schema", Schema)
            put("backupVersion", CurrentVersion)
            put("layout", lenient.parseToJsonElement(LayoutJson.encode(layout)))
            if (obscureSettings) put("lockedSettingsObfuscated", true)
            put(
                "settings",
                buildJsonObject {
                    for ((storeId, kv) in values.toSortedMap()) {
                        put(storeId, buildJsonObject { for ((k, v) in kv.toSortedMap()) put(k, toJson(v)) })
                    }
                }.let { if (obscureSettings) com.brightblu.parc.model.LockedStorage.transform(it, true) else it },
            )
        }
        return root.toString()
    }

    private fun toJson(value: Any): JsonElement = when (value) {
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Set<*> -> JsonArray(value.map { JsonPrimitive(it as String) }.sortedBy { it.content })
        else -> JsonNull
    }

    fun parse(text: String, specs: List<BackupStoreSpec>): ParsedBackup {
        if (text.length > MaxDocumentChars) throw BackupFormatException("Backup is too large")
        val root = try {
            lenient.parseToJsonElement(text)
        } catch (e: StackOverflowError) {
            throw BackupFormatException("Backup is nested too deeply")
        } catch (e: Exception) {
            throw BackupFormatException("Backup is not valid JSON")
        }
        if (root !is JsonObject) throw BackupFormatException("Backup is not an object")

        val schema = (root["schema"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val declared = (root["backupVersion"] as? JsonPrimitive)?.intOrNull
        val version = when {
            schema == null && declared == null -> 1
            schema == Schema && declared != null -> declared
            else -> throw BackupFormatException("Not a Parc backup")
        }
        if (version < 1) throw BackupFormatException("Unknown backup version")
        if (version > CurrentVersion) throw BackupFormatException("Backup is from a newer Parc")

        var skipped = 0
        val layoutElement: JsonElement? = if (version == 1) root else root["layout"]
        var layoutJson: String? = null
        var layout: ParcLayout? = null
        if (layoutElement != null && layoutElement !is JsonNull) {
            val decoded = decodeLayout(layoutElement)
            if (decoded == null) skipped++ else {
                layout = decoded
                layoutJson = LayoutJson.encode(decoded)
            }
        }

        val values = LinkedHashMap<String, Map<String, Any>>()
        var count = 0
        if (version >= 2) {
            val settings = try {
                root["settings"]?.let {
                    if ((root["lockedSettingsObfuscated"] as? JsonPrimitive)?.booleanOrNull == true)
                        com.brightblu.parc.model.LockedStorage.transform(it, false) else it
                }
            } catch (_: StackOverflowError) {
                skipped++; null
            } catch (_: Exception) {
                skipped++; null
            }
            if (settings != null && settings !is JsonNull && settings !is JsonObject) skipped++
            if (settings is JsonObject) {
                for (spec in specs) {
                    val section = settings[spec.id] ?: continue
                    if (section !is JsonObject) { skipped++; continue }
                    val (ok, bad) = readSection(spec, section)
                    skipped += bad
                    if (ok.isNotEmpty()) { values[spec.id] = ok; count += ok.size }
                }
            }
        }
        if (layout == null && count == 0) throw BackupFormatException("Nothing to restore in this file")
        val summary = RestoreSummary(
            folders = layout?.folders?.size ?: 0,
            apps = layout?.let(::distinctApps) ?: 0,
            settings = count,
            skipped = skipped,
            hasLayout = layout != null,
        )
        return ParsedBackup(version, layoutJson, values, summary)
    }

    private fun decodeLayout(element: JsonElement): ParcLayout? = try {
        // A real export always carries `arcs`; refusing a bare `{}` stops an unrelated JSON file from
        // wiping the layout.
        if (element !is JsonObject || "arcs" !in element) null else lenient.decodeFromJsonElement(ParcLayout.serializer(), com.brightblu.parc.model.LockedStorage.transform(element, false)).takeIf { it.version == 1 }
    } catch (e: StackOverflowError) {
        null
    } catch (e: Exception) {
        null
    }

    private fun distinctApps(layout: ParcLayout): Int {
        val ids = HashSet<AppId>()
        for (assignment in layout.arcs) {
            for (slot in assignment.slots.values) if (slot is Slot.App) ids += slot.id
        }
        for (folder in layout.folders) ids += folder.appIds
        ids += layout.pins
        return ids.size
    }

    private fun readSection(spec: BackupStoreSpec, section: JsonObject): Pair<Map<String, Any>, Int> {
        val ok = LinkedHashMap<String, Any>()
        var bad = 0
        for ((name, element) in section) {
            val key = spec.key(name) ?: continue
            val value = readValue(key, element)
            if (value == null || !BackupValues.matches(key, value)) bad++ else ok[name] = value
        }
        return ok to bad
    }

    private fun readValue(key: BackupKey, element: JsonElement): Any? = when (key.type) {
        BackupValueType.Bool -> (element as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        BackupValueType.Int -> (element as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        BackupValueType.Long -> (element as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        BackupValueType.Text -> (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        BackupValueType.TextSet -> (element as? JsonArray)?.let { array ->
            if (array.size > BackupKey.MaxSetEntries) {
                null
            } else {
                val items = array.map { e -> (e as? JsonPrimitive)?.takeIf { it.isString }?.content }
                if (items.any { it == null }) null else items.filterNotNull().toSet()
            }
        }
    }
}
