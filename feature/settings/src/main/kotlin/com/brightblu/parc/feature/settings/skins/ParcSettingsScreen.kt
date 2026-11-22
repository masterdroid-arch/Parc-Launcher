package com.brightblu.parc.feature.settings.skins

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.brightblu.parc.feature.settings.ui.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.HapticsLevel
import com.brightblu.parc.core.data.MainAppEdit
import com.brightblu.parc.core.data.MainAppEditRefused
import com.brightblu.parc.core.data.MainAppsSlotCount
import com.brightblu.parc.feature.settings.mainapps.MainAppChoice
import com.brightblu.parc.feature.settings.mainapps.MainAppsState
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.brightblu.parc.feature.settings.mainapps.MainAppsEditor
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcPage
import com.brightblu.parc.model.Skin

/**
 * Settings screen (DESIGN §5 S14): skin picker, haptics and clock, plus the reserved left-hand mirror.
 * Colours and sizes come only from [Skin] and [Metrics]; the screen is stateless, so a host can drive
 * it from [SettingsController] or from a preview without touching persistence.
 */
@Composable
fun ParcSettingsScreen(
    state: SettingsUiState,
    skin: Skin,
    onSelectSkin: (String) -> Unit,
    onSelectHaptics: (HapticsLevel) -> Unit,
    onSetClockVisible: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    topPaddingDp: Float = Metrics.keepOutTopDp,
    extra: @Composable () -> Unit = {},
    gallery: (@Composable () -> Unit)? = null,
) {
    ParcPage(modifier.background(color(skin.colors.bg)).padding(top = topPaddingDp.dp), safeTop = false) {
        BasicText("Skin", style = skinTokenStyle(skin, skin.colors.textPrimary, skin.typography.title))
        gallery?.invoke()

        extra()
    }
}

/** Human label for a haptics token (DESIGN §2.7). */
fun hapticsLabel(level: HapticsLevel): String = when (level) {
    HapticsLevel.Off -> "Off"
    HapticsLevel.Light -> "Light"
    HapticsLevel.Strong -> "Strong"
}

/** Settings edits Home directly. Onboarding has its own suggestion-backed controller. */
@Composable
fun MainAppsSettingsSection(
    skin: Skin,
    layout: ParcLayout,
    apps: List<AppEntry>,
    save: suspend (MainAppEdit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val inner = layout.arcs.firstOrNull { it.arc.id == "A1" }
    val positions = List(minOf(inner?.arc?.slotCapacity ?: 0, MainAppsSlotCount)) { inner?.slots?.get(it) }
    val options = remember(apps) { apps.map { MainAppChoice(it.id, it.label, it.packageName) } }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val picker = MainAppsState(options = options, liveSlots = positions, loaded = inner != null, query = query)
    val undo = rememberEditorUndo()

    fun submit(edit: MainAppEdit) {
        scope.launch {
            try {
                withContext(NonCancellable) { save(edit) }
                message = null
                if (edit is MainAppEdit.Remove) {
                    undo.offer { submit(MainAppEdit.Restore(edit.id, edit.index, pinned = edit.id in layout.pins)) }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (refused: MainAppEditRefused) {
                message = refused.message
            } catch (_: Exception) {
                message = "Could not save main apps. Try again."
            }
        }
    }
    EditorScaffold(EditorSpec.MainApps, skin, empty = picker.loaded && positions.all { it == null },
        instruction = message ?: EditorSpec.MainApps.instruction,
        undo = undo.action?.let { { undo.run() } },
        preview = {
            BasicText("Inner ring: " + picker.slots.indices.joinToString(" · ") { picker.slotLabel(it) },
                style = settingsText(skin, skin.colors.accent, true))
        }) {
        MainAppsEditor(
            state = picker, skin = skin, onQuery = { query = it },
            onToggle = { id ->
                val selected = positions.indexOf(Slot.App(id))
                if (selected >= 0) submit(MainAppEdit.Remove(id, selected))
                else submit(MainAppEdit.Add(id))
            },
            onMove = { from, to ->
                val app = positions[from] as Slot.App
                submit(MainAppEdit.Move(app.id, from, to, positions[to]))
            },
            modifier = Modifier.padding(vertical = Metrics.sectionHeaderGapDp.dp),
        )
    }
}

@Composable
internal fun Section(title: String, skin: Skin) {
    SettingsSection(title, skin)
}

@Composable
private fun SkinRow(candidate: Skin, selected: Boolean, skin: Skin, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Metrics.atlasPreviewHeightDp.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = Metrics.lineGapDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniRing(candidate, Modifier.size(Metrics.atlasPreviewWidthDp.dp, Metrics.atlasPreviewHeightDp.dp))
        BasicText(
            candidate.name,
            style = skinTokenStyle(skin,
                if (selected) skin.colors.accent else skin.colors.textPrimary,
                skin.typography.menu,
            ),
            modifier = Modifier
                .weight(1f)
                .padding(start = Metrics.rowGapDp.dp),
        )
        BasicText(
            if (selected) "Selected" else "",
            style = skinTokenStyle(skin, skin.colors.textSecondary, skin.typography.caption),
        )
    }
}

@Composable
internal fun OptionRow(
    label: String,
    skin: Skin,
    onClick: () -> Unit,
    detail: String = "",
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Metrics.touchTargetDp.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Metrics.lineGapDp.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(
            label,
            style = skinTokenStyle(skin,
                when {
                    !enabled -> skin.colors.textSecondary
                    selected -> skin.colors.accent
                    else -> skin.colors.textPrimary
                },
                skin.typography.menu,
            ),
            modifier = Modifier.weight(1f),
        )
        val note = when {
            selected && detail.isEmpty() -> "Selected"
            else -> detail
        }
        if (note.isNotEmpty()) {
            BasicText(note, style = skinTokenStyle(skin, skin.colors.textSecondary, skin.typography.caption))
        }
    }
}
