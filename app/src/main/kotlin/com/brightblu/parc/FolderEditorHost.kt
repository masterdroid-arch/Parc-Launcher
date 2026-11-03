package com.brightblu.parc

import com.brightblu.parc.model.textFamily
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.feature.settings.folders.*
import com.brightblu.parc.model.*
import kotlinx.coroutines.launch

internal const val ArcsFull = "Arcs full, rotate or remove something"

/** Lowest arc index, then lowest vacant slot. Overflow never displaces an existing item. */
internal fun firstFolderPosition(layout: ParcLayout): SlotPosition? =
    layout.arcs.sortedBy { it.arc.index }.firstNotNullOfOrNull { assignment ->
        if (assignment.arc.layer != Layer.Home) null else
            (0 until assignment.arc.slotCapacity).firstOrNull { it !in assignment.slots }
                ?.let { SlotPosition(assignment.arc.id, it) }
    }

/** Apply the editor's changed folders to the latest layout, retaining unrelated concurrent edits. */
internal fun folderDocumentLayout(layout: ParcLayout, previous: FolderDocument, next: FolderDocument): ParcLayout? {
    val removed = previous.folders.filter { old -> next.folders.none { it.id == old.id } }
    var result = layout.copy(
        folders = layout.folders.filterNot { folder -> removed.any { it.id == folder.id } },
        arcs = layout.arcs.map { arc -> arc.copy(slots = arc.slots.filterValues { slot ->
            removed.none { slot == Slot.Folder(it.id) }
        }) },
    )
    if (removed.isNotEmpty()) {
        val inboxId = FolderId(NewFolderKey)
        val inbox = result.folders.firstOrNull { it.id == inboxId } ?: Folder(inboxId, NewFolderName)
        val updated = inbox.copy(appIds = (inbox.appIds + removed.flatMap { it.appIds }).distinct())
        result = result.copy(folders = result.folders.filterNot { it.id == inboxId } + updated)
    }
    for (folder in next.folders) {
        if (folder == previous.folder(folder.id)) continue
        if (result.folders.none { it.id == folder.id }) {
            val position = firstFolderPosition(result) ?: return null
            result = result.copy(arcs = result.arcs.map { arc ->
                if (arc.arc.id == position.arcId) arc.copy(slots = arc.slots + (position.index to Slot.Folder(folder.id))) else arc
            })
        }
        result = result.copy(folders = result.folders.filterNot { it.id == folder.id } + folder)
    }
    return result
}

internal class PlacedFolderWriter(private val repository: LayoutRepository) : FolderEditWriter {
    override suspend fun apply(previous: FolderDocument, next: FolderDocument): FolderEditResult {
        val updated = folderDocumentLayout(repository.layout.value, previous, next)
            ?: return FolderEditResult.Unsupported(ArcsFull)
        return try {
            repository.importLayout(LayoutJson.encode(updated))
            FolderEditResult.Applied
        } catch (_: IllegalArgumentException) {
            FolderEditResult.Unsupported("Layout changed. Try again")
        }
    }
}

/** App-owned host extends the existing T11 surface without changing the settings module. */
@Composable
internal fun FolderEditorHost(services: LauncherServices, skin: Skin, create: Boolean = false, settingsHost: Boolean = false, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val layout by services.layout.layout.collectAsState()
    val apps by services.unlockedApps.collectAsState()
    val windows by services.folderLock.windows.collectAsState()
    val gate = rememberFolderLockGate(services)
    var state by remember {
        mutableStateOf(FolderEditorState(layout.arcs.sortedBy { it.arc.index }.map { it.arc.slotCapacity },
            FolderDocument.from(layout)).let {
            if (create) FolderEditorMachine.reduce(it, FolderEditorEvent.BeginCreateFolder) else it
        })
    }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val writer = remember(services) { PlacedFolderWriter(services.layout) }
    LaunchedEffect(layout, busy) {
        if (!busy) state = state.copy(document = FolderDocument.from(layout))
    }
    LaunchedEffect(windows) {
        if (state.openFolderId?.let(gate::isFolderLockedNow) == true) {
            state = state.copy(page = EditorPage.Folders, openFolderId = null, prompt = null, undo = emptyList())
            confirmDelete = false
        }
    }
    fun commit(after: FolderEditorState) {
        if (busy) return
        val before = state
        if (after.document == before.document) { state = after; return }
        busy = true
        scope.launch {
            try {
                when (val result = writer.apply(before.document, after.document)) {
                    FolderEditResult.Applied -> state = after.copy(document = FolderDocument.from(services.layout.layout.value))
                    is FolderEditResult.Unsupported -> state = before.copy(notice = result.reason)
                }
            } finally { busy = false }
        }
    }
    fun back() {
        when {
            busy -> Unit
            confirmDelete -> confirmDelete = false
            state.prompt != null -> {
                val applied = FolderEditorMachine.reduce(state, FolderEditorEvent.ApplyNameDraft)
                if (applied.prompt?.issue == null) commit(applied.copy(prompt = null)) else state = applied
            }
            else -> onClose()
        }
    }
    BackHandler { back() }
    val body: @Composable () -> Unit = {
    Column(Modifier.fillMaxSize().background(Color(skin.colors.bg.toInt())).imePadding()) {
        val projection = state.toUi(apps).let { ui -> ui.copy(folders = ui.folders.map { row ->
            if (gate.isFolderLockedNow(row.id)) row.copy(name = if (gate.hideName()) "Locked" else row.name, appCount = 0) else row
        }) }
        FolderEditorScreen(projection, skin, { event ->
            if (!busy) {
                if (event is FolderEditorEvent.OpenFolder && gate.isFolderLockedNow(event.id)) {
                    gate.authenticateFolder(event.id) { commit(FolderEditorMachine.reduce(state, event)) }
                } else commit(FolderEditorMachine.reduce(state, event))
            }
        }, Modifier.weight(1f), topPaddingDp = if (settingsHost) 0f else rememberPageInsets().topDp)
        if (confirmDelete) {
            EditorAction("Remove ${state.openFolder?.name}? Its apps go to New.", skin) {}
            Row {
                EditorAction("Cancel", skin) { confirmDelete = false }
                EditorAction("Remove", skin) {
                    val folder = state.openFolder ?: return@EditorAction
                    confirmDelete = false
                    commit(state.copy(document = state.document.copy(folders = state.document.folders.filterNot { it.id == folder.id }),
                        page = EditorPage.Folders, openFolderId = null, prompt = null, notice = "Folder deleted",
                        undo = emptyList()))
                }
            }
        } else if (state.openFolder != null && state.openFolderId != FolderId(NewFolderKey) && state.prompt == null) {
            EditorAction("Remove folder", skin) { if (!busy) confirmDelete = true }
        }
        if (!settingsHost) EditorAction(if (busy) "Saving…" else "←  Back", skin, Modifier.navigationBarsPadding()) { back() }
    }
    }
    if (settingsHost) SettingsPageFrame(skin, onBack = ::back, content = body) else body()
}

@Composable
internal fun EditorAction(label: String, skin: Skin, modifier: Modifier = Modifier, onClick: () -> Unit) {
    BasicText((if (label.startsWith("Remove")) "×  " else "+  ") + label, modifier.heightIn(min = Metrics.touchTargetDp.dp).clickable(onClick = onClick)
        .parcMinTouch().wrapContentSize(androidx.compose.ui.Alignment.CenterStart)
        .padding(horizontal = Metrics.textGutterDp.dp),
        style = TextStyle(fontFamily = skin.textFamily, color = Color((if (label.startsWith("Remove")) skin.colors.warn else skin.colors.accent).toInt()), fontSize = skin.typography.menu.sizeSp.sp))
}
