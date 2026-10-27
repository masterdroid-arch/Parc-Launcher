package com.brightblu.parc.feature.settings.folders

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.LayoutRepository

/**
 * Records the folder mutations the editor is allowed to make, so a test can assert that the writer
 * does not rewrite a folder it did not change. Everything else is delegated to the fake.
 */
internal class RecordingLayout(private val delegate: LayoutRepository) : LayoutRepository by delegate {
    val calls = mutableListOf<String>()

    override suspend fun createFolder(id: FolderId, name: String) {
        calls += "createFolder:${id.value}"
        delegate.createFolder(id, name)
    }

    override suspend fun renameFolder(id: FolderId, name: String) {
        calls += "renameFolder:${id.value}:$name"
        delegate.renameFolder(id, name)
    }

    override suspend fun addToFolder(folderId: FolderId, id: AppId) {
        calls += "addToFolder:${folderId.value}:${id.value}"
        delegate.addToFolder(folderId, id)
    }

    override suspend fun removeFromFolder(folderId: FolderId, id: AppId) {
        calls += "removeFromFolder:${folderId.value}:${id.value}"
        delegate.removeFromFolder(folderId, id)
    }
}
