package com.brightblu.parc.core.data.usage

import kotlinx.serialization.json.Json
import java.io.File

/** Rank state boundary. Reads happen on the constructor path, so an implementation must be cheap. */
internal interface UsageStateStore {
    /** Persisted state, or the empty state when nothing valid is stored. */
    fun read(): UsageRankState

    /** Replaces the document. A failed write is not fatal: the in-memory state stays correct. */
    fun write(state: UsageRankState)
}

/**
 * Versioned JSON file for [UsageRankState], written atomically (temp file + rename) exactly like the
 * app snapshot: the ring must be able to read it on the first frame, so no database is involved.
 *
 * Tolerant by design: a missing, unreadable, corrupt or differently versioned file reads as the
 * empty state, which simply means the next recompute places every app from scratch. Contains ids
 * only, never labels. Not thread-safe for concurrent writes; callers serialize writes.
 */
internal class FileUsageStateStore(private val file: File) : UsageStateStore {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    override fun read(): UsageRankState {
        if (!file.exists()) return UsageRankState()
        return try {
            val state = json.decodeFromString(UsageRankState.serializer(), file.readText())
            if (state.version != UsageRankState.SchemaVersion) UsageRankState() else state
        } catch (e: Exception) {
            UsageRankState()
        }
    }

    override fun write(state: UsageRankState) {
        val parent = file.parentFile ?: File(".")
        parent.mkdirs()
        val temp = File(parent, "${file.name}.tmp")
        temp.writeText(json.encodeToString(UsageRankState.serializer(), state))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}
