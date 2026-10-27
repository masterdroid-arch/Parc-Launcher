package com.brightblu.parc.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Disposable presentation cache. The layout repository remains authoritative. No icons or unread data. */
@Serializable
data class StartupSnapshot(val layout: ParcLayout, val apps: List<AppEntry>, val version: Int = 1)

object StartupSnapshotJson {
    private val json = Json { encodeDefaults = true }
    fun encode(snapshot: StartupSnapshot): String {
        val element = json.encodeToJsonElement(StartupSnapshot.serializer(), snapshot)
        return (if (snapshot.layout.folders.any { it.locked }) LockedStorage.transform(element, true) else element).toString()
    }
    fun decode(source: String): StartupSnapshot = json.decodeFromJsonElement(StartupSnapshot.serializer(), LockedStorage.transform(json.parseToJsonElement(source), false)).also {
        require(it.version == 1 && it.layout.version == 1) { "Unsupported startup snapshot" }
        require(it.apps.map { app -> app.id }.distinct().size == it.apps.size) { "Duplicate app IDs" }
    }
}
