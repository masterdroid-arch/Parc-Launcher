package com.brightblu.parc.feature.settings.hidden

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.feature.settings.ui.SettingKind
import com.brightblu.parc.feature.settings.ui.SettingRow
import com.brightblu.parc.feature.settings.ui.SettingsCard
import com.brightblu.parc.feature.settings.ui.SettingsSection
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.LayoutJson
import com.brightblu.parc.model.LayoutRepository
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcPage
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.hidden
import com.brightblu.parc.model.withFolderHidden
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** One hidden folder row: its stable id, name and member count. Immutable, non-null. */
data class HiddenFolderRow(val folderId: FolderId, val name: String, val appCount: Int)

/**
 * T218: the folders that carry `parc:hidden`. Immutable. Only folders are listed; their apps stay
 * members, so unhiding restores the folder without touching a slot, a pin or a lock.
 */
data class HiddenFoldersUiState(val rows: List<HiddenFolderRow> = emptyList()) {
    val canUnhideAll: Boolean get() = rows.isNotEmpty()
}

/** T219: folder-only count copy shared by the row and the list heading. */
object HiddenFoldersText {
    fun summary(count: Int): String = when (count) {
        0 -> "None"
        1 -> "1 folder"
        else -> "$count folders"
    }

    fun sectionTitle(count: Int): String =
        if (count == 1) "Hidden · 1 folder" else "Hidden · $count folders"
}

/** Pure projection: hidden folders sorted by name, then by stable id. */
object HiddenFoldersProjection {
    fun rows(folders: List<Folder>): List<HiddenFolderRow> = folders.asSequence()
        .filter { it.hidden }
        .map { HiddenFolderRow(it.id, it.name, it.appIds.size) }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.folderId.value }))
        .toList()

    fun state(folders: List<Folder>): HiddenFoldersUiState = HiddenFoldersUiState(rows(folders))
}

/**
 * Connects the hidden folder list to the layout. The layout is the source of truth: every change is
 * a repository write and the rows are rebuilt from the published snapshot, never from local state.
 */
class HiddenFoldersController(
    private val repository: LayoutRepository,
    private val scope: CoroutineScope,
) {
    private val stateFlow = MutableStateFlow(HiddenFoldersUiState())
    val state: StateFlow<HiddenFoldersUiState> = stateFlow.asStateFlow()

    init {
        scope.launch { repository.layout.collect { rebuild(it.folders) } }
    }

    /** Removes one folder's hide tag. The folder, its apps, its position and its lock stay. */
    fun unhide(id: FolderId) {
        scope.launch { writeHidden(setOf(id), hidden = false) }
    }

    /** Removes the hide tag from every folder in one write. */
    fun unhideAll() {
        val ids = repository.layout.value.folders.filter { it.hidden }.mapTo(linkedSetOf()) { it.id }
        if (ids.isEmpty()) return
        scope.launch { writeHidden(ids, hidden = false) }
    }

    private suspend fun writeHidden(ids: Set<FolderId>, hidden: Boolean) {
        val snapshot = repository.layout.value
        val next = ids.fold(snapshot) { layout, id -> layout.withFolderHidden(id, hidden) }
        if (next == snapshot) return
        runCatching { repository.importLayout(LayoutJson.encode(next)) }
    }

    private fun rebuild(folders: List<Folder>) {
        stateFlow.value = HiddenFoldersProjection.state(folders)
    }
}

/** Live hidden folder list; the host supplies the controller and the skin. */
@Composable
fun HiddenFolders(
    controller: HiddenFoldersController,
    skin: Skin,
    modifier: Modifier = Modifier,
    topPaddingDp: Float = Metrics.keepOutTopDp,
) {
    val state by controller.state.collectAsState()
    HiddenFoldersScreen(
        state = state,
        skin = skin,
        onUnhide = controller::unhide,
        onUnhideAll = controller::unhideAll,
        modifier = modifier,
        topPaddingDp = topPaddingDp,
    )
}

/** Stateless surface, so the list rules can be driven without a real repository. */
@Composable
fun HiddenFoldersScreen(
    state: HiddenFoldersUiState,
    skin: Skin,
    onUnhide: (FolderId) -> Unit,
    onUnhideAll: () -> Unit,
    modifier: Modifier = Modifier,
    topPaddingDp: Float = Metrics.keepOutTopDp,
) {
    ParcPage(modifier.background(Color(skin.colors.bg.toInt())).padding(top = topPaddingDp.dp),
        safeTop = false, scroll = true) {
        BasicText("Hidden folders",
            style = settingsText(skin).copy(fontSize = skin.typography.title.sizeSp.sp))
        BasicText(VisibilityCopy.Scope,
            style = settingsText(skin, skin.colors.textSecondary, true),
            modifier = Modifier.padding(top = Metrics.rowGapDp.dp, bottom = Metrics.sectionHeaderGapDp.dp))
        SettingsSection(HiddenFoldersText.sectionTitle(state.rows.size), skin)
        if (state.rows.isEmpty()) {
            BasicText("No hidden folders.",
                style = settingsText(skin, skin.colors.textSecondary, true),
                modifier = Modifier.padding(horizontal = Metrics.settingsRowInsetDp.dp))
        } else {
            SettingsCard(skin) {
                state.rows.forEachIndexed { index, row ->
                    SettingRow(
                        title = row.name,
                        skin = skin,
                        kind = SettingKind.Action,
                        description = (if (row.appCount == 1) "1 app" else "${row.appCount} apps") + " · Unhide",
                        icon = "",
                        divider = index != state.rows.lastIndex,
                    ) { onUnhide(row.folderId) }
                }
            }
        }
        if (state.canUnhideAll) {
            SettingsCard(skin) {
                SettingRow("Unhide all", skin, SettingKind.Action,
                    description = "Show every hidden folder on Home again",
                    icon = "", divider = false, onClick = onUnhideAll)
            }
        }
    }
}
