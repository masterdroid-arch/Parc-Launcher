package com.brightblu.parc.core.data.library

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

/**
 * T225: one folder the user has explicitly allowed Parc to scan. A plain directory has an absolute
 * path; a folder chosen through the system picker has a persisted content-tree URI and [tree] is true.
 * The label is the human name shown in Find books and is never used to open a file.
 */
@Serializable
data class BookFolder(
    val location: String,
    val label: String,
    val tree: Boolean = false,
) {
    init {
        require(location.isNotBlank() && label.isNotBlank())
    }

    fun scanFolder(): ScanFolder = ScanFolder(location, label, tree)
}

/** The pure T225 rules: what may never be scanned, and what is quiet enough to suggest. */
object BookFolderRules {
    /** Only these names are suggested on a first run; they stay unticked until the user confirms. */
    val suggestedNames: List<String> = listOf("Download", "Documents", "Books")

    private fun normalized(path: String): String =
        path.replace('\\', '/').trim().trimEnd('/').lowercase(Locale.ROOT)

    private fun rooted(path: String): String {
        // Document ids look like "primary:Android/data/...", so a colon is a path separator here.
        val normal = normalized(path).replace(':', '/')
        return if (normal.startsWith('/')) normal else "/$normal"
    }

    /** Android/data and Android/obb are app-private or app-owned; no chosen root may walk into them. */
    fun isAppPrivate(path: String): Boolean {
        val value = rooted(path)
        fun under(segment: String): Boolean =
            value == segment || value.endsWith(segment) || value.contains("$segment/")
        return under("/android/data") || under("/android/obb")
    }

    /** WhatsApp media is hidden from suggestions only; it stays reachable by browsing. */
    fun isWhatsAppMedia(path: String): Boolean {
        val value = rooted(path)
        fun under(segment: String): Boolean =
            value == segment || value.endsWith(segment) || value.contains("$segment/")
        return under("/whatsapp/media") || under("/whatsapp business/media")
    }

    /** Suggestions never include app-private folders or WhatsApp media. */
    fun isSuggestable(path: String): Boolean = !isAppPrivate(path) && !isWhatsAppMedia(path)

    /** Keep only the rows and records that belong to the folders the user chose now. Picked files stay. */
    fun retainFolders(index: BookIndex, locations: Set<String>): BookIndex =
        BookIndexRules.retainFolders(index, locations)

    /**
     * T346: forgotten picker folders whose Android read grant nothing still needs. A grant is kept when
     * a retained Library book lives under that tree, because its marks and place would otherwise be
     * stranded. Plain-directory folders hold no grant and are never returned.
     */
    fun releaseableGrants(removed: List<BookFolder>, retainedUris: List<String>): List<String> =
        removed.filter { it.tree }
            .map { it.location.trimEnd('/') }
            .filter { location ->
                retainedUris.none { uri -> uri == location || uri.startsWith("$location/") }
            }
            .distinct()
}

object BookFolderJson {
    /** A few hundred chosen folders is far more than anyone needs; this bounds a corrupt value. */
    const val MaxChars = 200_000
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(folders: List<BookFolder>): String = json.encodeToString(
        JsonObject(mapOf(
            "folders" to JsonArray(folders.map { json.encodeToJsonElement(BookFolder.serializer(), it) }),
        )),
    ).also { require(it.length <= MaxChars) { "The chosen folders are too large" } }

    /** Empty, old, malformed and partial documents read as whatever valid folders they still contain. */
    fun decode(text: String?): List<BookFolder> {
        if (text.isNullOrBlank() || text.length > MaxChars) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(text)
            when (root) {
                is JsonArray -> root
                is JsonObject -> root["folders"]?.jsonArray
                else -> null
            }?.mapNotNull {
                runCatching { json.decodeFromJsonElement(BookFolder.serializer(), it) }.getOrNull()
            }?.map { folder ->
                if (!folder.tree && folder.location.startsWith("content://")) folder.copy(tree = true) else folder
            }.orEmpty().distinctBy { it.location }
        }.getOrDefault(emptyList())
    }
}

internal val Context.parcBookFolderStore by preferencesDataStore(
    name = "parc_book_folders",
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler("parc_book_folders"),
)

/**
 * T225: the folders the user ticked, kept on the phone only. They are not in the backup: a path or a
 * URI grant from another phone would point at the wrong files.
 */
class BookFolderStore(
    private val store: DataStore<Preferences>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(context.applicationContext.parcBookFolderStore)

    val folders: Flow<List<BookFolder>> = store.data.map { BookFolderJson.decode(it[Key]) }.flowOn(io)

    suspend fun snapshot(): List<BookFolder> = withContext(io) { BookFolderJson.decode(store.data.first()[Key]) }

    suspend fun save(folders: List<BookFolder>) = withContext(io) {
        val text = BookFolderJson.encode(folders.distinctBy { it.location })
        store.edit { it[Key] = text }
    }

    companion object {
        const val KeyName = "folders_v1"
        private val Key = stringPreferencesKey(KeyName)
    }
}
