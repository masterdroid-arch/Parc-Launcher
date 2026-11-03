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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.HapticsLevel
import com.brightblu.parc.feature.settings.mainapps.MainAppsController
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

/**
 * The Settings-side main-app editor (T62): the same picker the onboarding flow shows, so the four
 * apps stay editable after first run. Saving writes the DataStore list; the layout repository
 * watches its revision and rewrites the innermost arc once.
 */
@Composable
fun MainAppsSettingsSection(skin: Skin, lockedPackages: Set<String> = emptySet(), onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(context, scope) { MainAppsController.create(context, scope, useSuggestions = false) }
    val picker by controller.state.collectAsState()
    val safePicker = picker.copy(options = picker.options.filterNot { it.packageName in lockedPackages })

    val undo = rememberEditorUndo()
    val writerScope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate) }
    var saveError by remember { mutableStateOf(false) }
    val writer = remember(controller) { EditorWriter<List<com.brightblu.parc.model.AppId>>(writerScope,
        onError = { saveError = true }) { controller.saveSelection(it); saveError = false } }
    androidx.compose.runtime.DisposableEffect(writer) { onDispose { writer.flush() } }
    fun change(block: () -> Unit) { block(); writer.changed(controller.state.value.selected) }
    EditorScaffold(EditorSpec.MainApps, skin, empty = picker.loaded && picker.selected.isEmpty(),
        undo = if (undo.action != null) ({ undo.run() }) else null,
        preview = { BasicText("Inner ring: " + safePicker.slots.joinToString(" · ") { it?.label ?: "Empty" }, style = settingsText(skin, skin.colors.accent, true)) }) {
        if (saveError) SettingRow("Retry", skin, SettingKind.Action, description = "Could not save main apps") {
            writer.changed(controller.state.value.selected)
        }
        MainAppsEditor(
            state = safePicker, skin = skin,
            onQuery = controller::setQuery,
            onToggle = { id ->
                val before = controller.state.value.selected
                change { controller.toggle(id) }
                if (id in before) undo.offer { change {
                    val current = controller.state.value.selected
                    if (id !in current && current.size < com.brightblu.parc.feature.settings.mainapps.MainAppsPicker.MaxMainApps)
                        controller.restoreSelection(current.toMutableList().apply { add(before.indexOf(id).coerceAtMost(size), id) })
                } }
            },
            onMove = { from, to -> change { controller.move(from, to) } },
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
