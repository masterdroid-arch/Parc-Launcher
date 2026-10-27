package com.brightblu.parc.feature.settings.folders

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.ParcLayout

/** Component-shaped test id. */
internal fun testId(name: String): AppId = AppId("com.test.$name/com.test.$name.Main@0")

/** Three arcs, 18 slots: the DESIGN §4 bucket shape with a roomy last arc. */
internal val TestCapacities: List<Int> = listOf(4, 6, 8)

internal fun testFolder(id: String, name: String, apps: List<AppId>): Folder =
    Folder(FolderId(id), name, emptySet(), apps)

internal fun testGrid(vararg names: String): FolderGrid =
    FolderGrid(TestCapacities, names.map(::testId))

internal fun testLayout(vararg folders: Folder, pins: Set<AppId> = emptySet()): ParcLayout =
    ParcLayout(version = 1, folders = folders.toList(), pins = pins)

internal fun testEntry(id: AppId, label: String, suspended: Boolean = false): AppEntry =
    AppEntry(id = id, label = label, packageName = id.value.substringBefore('/'), isSystem = false,
        installTime = 0L, suspended = suspended)

internal fun testState(
    document: FolderDocument = FolderDocument(),
    page: EditorPage = EditorPage.Folders,
    openFolderId: FolderId? = null,
    drag: DragState? = null,
): FolderEditorState = FolderEditorState(
    arcCapacities = TestCapacities,
    document = document,
    page = page,
    openFolderId = openFolderId,
    drag = drag,
)
