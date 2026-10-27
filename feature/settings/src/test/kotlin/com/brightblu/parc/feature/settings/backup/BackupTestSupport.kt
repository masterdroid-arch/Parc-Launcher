package com.brightblu.parc.feature.settings.backup

import com.brightblu.parc.core.data.backup.BackupFileStore
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import java.io.FileNotFoundException

/** Component-shaped test id; matches the AppId contract. */
internal fun backupId(name: String): AppId = AppId("com.test.$name/com.test.$name.Main@0")

/** In-memory backup transport with injectable failures, so every outcome is reachable in a JVM test. */
internal class FakeBackupFileStore : BackupFileStore {
    val documents = mutableMapOf<String, String>()
    var readFailure: Exception? = null
    var writeFailure: Exception? = null
    var lastWrittenUri: String? = null
        private set
    var writeCount = 0
        private set

    /** When set, `write` suspends until it completes, so a test can observe the busy state. */
    var writeGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun read(uri: String): String {
        readFailure?.let { throw it }
        return documents[uri] ?: throw FileNotFoundException(uri)
    }

    override suspend fun write(uri: String, text: String) {
        writeGate?.await()
        writeFailure?.let { throw it }
        writeCount++
        documents[uri] = text
        lastWrittenUri = uri
    }
}

/** One arc, one pinned folder app and one hidden app: enough to prove a restore is lossless. */
internal fun sampleBackupLayout(): ParcLayout {
    val reddit = backupId("reddit")
    return ParcLayout(
        version = 1,
        arcs = listOf(
            ArcAssignment(
                ArcSpec("A1", 0, 120f, 180f, 270f, 4, Layer.Home),
                mapOf(0 to Slot.App(reddit)),
            ),
        ),
        folders = listOf(Folder(FolderId("social"), "Social", emptySet(), listOf(reddit))),
        pins = setOf(reddit),
        hidden = setOf(backupId("threads")),
    )
}
