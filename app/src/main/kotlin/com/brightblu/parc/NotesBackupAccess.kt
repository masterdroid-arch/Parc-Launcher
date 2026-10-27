package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupStoreSpec
import com.brightblu.parc.core.data.backup.StoreAccess

/** Validate the document before replacing local notes; malformed restores leave notes intact. */
internal class NotesBackupAccess(private val delegate: StoreAccess) : StoreAccess {
    override suspend fun read(spec: BackupStoreSpec): Map<String, Any> = delegate.read(spec)
    override suspend fun write(spec: BackupStoreSpec, values: Map<String, Any>) {
        (values[NotesJson.Key] as? String)?.let { NotesJson.decode(it) }
        delegate.write(spec, values)
    }
}
