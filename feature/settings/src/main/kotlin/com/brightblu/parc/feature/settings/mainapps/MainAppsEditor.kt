package com.brightblu.parc.feature.settings.mainapps

import com.brightblu.parc.feature.settings.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.TypeToken

/**
 * "Your four main apps" picker surface (T62): a search box, the four slots with reorder, and the
 * alphabetical installed-app list.
 *
 * Stateless: every interaction is a callback into [MainAppsController]. Colours and sizes come only
 * from [Skin] and [Metrics]. Rows are plain text, matching the onboarding flow's quiet look.
 */
@Composable
fun MainAppsEditor(
    state: MainAppsState,
    skin: Skin,
    onQuery: (String) -> Unit,
    onToggle: (AppId) -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = state.withoutLocked(com.brightblu.parc.core.data.lock.NotificationLockPolicy.packages?.invoke().orEmpty())
    Column(modifier.fillMaxWidth()) {
        SectionLabel("Four main apps", skin)
        shown.slots.forEachIndexed { index, choice ->
            SlotRow(
                index = index,
                choice = choice,
                skin = skin,
                canMoveUp = index > 0 && choice != null,
                canMoveDown = index < shown.slots.lastIndex && choice != null && shown.slots[index + 1] != null,
                onClick = { choice?.let { onToggle(it.id) } },
                onMove = onMove,
            )
        }

        SectionLabel("Find an app", skin)
        SearchBox(state.query, skin, onQuery)

        SectionLabel("Installed apps", skin)
        when {
            !state.loaded -> Hint("Reading installed apps...", skin)
            shown.visible.isEmpty() -> Hint("No matching apps.", skin)
            else -> shown.visible.forEach { choice ->
                AppRow(
                    choice = choice,
                    selected = state.isSelected(choice.id),
                    skin = skin,
                    onClick = { onToggle(choice.id) },
                )
            }
        }
    }
}

@Composable
private fun SlotRow(
    index: Int,
    choice: MainAppChoice?,
    skin: Skin,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Metrics.touchTargetDp.dp)
            .padding(vertical = Metrics.gridDp.dp / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            "${index + 1}",
            style = style(skin.colors.textSecondary, skin.typography.caption),
            modifier = Modifier.width(Metrics.gridDp.dp * 5),
        )
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = Metrics.touchTargetDp.dp)
                .clickable(enabled = choice != null, onClick = onClick),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicText(
                choice?.label ?: "empty",
                style = style(
                    if (choice == null) skin.colors.textSecondary else skin.colors.textPrimary,
                    skin.typography.menu,
                ),
            )
        }
        if (choice != null) {
            StepButton("↑", enabled = canMoveUp, skin = skin) { onMove(index, index - 1) }
            StepButton("↓", enabled = canMoveDown, skin = skin) { onMove(index, index + 1) }
            StepButton("−", enabled = true, skin = skin) { onClick() }
        }
    }
}

/** 40 dp text button, the same touch target the hub uses for small actions. */
@Composable
private fun StepButton(label: String, enabled: Boolean, skin: Skin, onClick: () -> Unit) {
    Box(
        Modifier
            .width(Metrics.gridDp.dp * 10)
            .semantics { contentDescription = when(label) { "↑" -> "Move up"; "↓" -> "Move down"; else -> "Remove" } }
            .height(Metrics.gridDp.dp * 10)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = style(
                if (enabled) skin.colors.textSecondary else skin.colors.lineBase,
                skin.typography.caption,
            ),
        )
    }
}

@Composable
private fun SearchBox(query: String, skin: Skin, onQuery: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Metrics.touchTargetDp.dp)
            .background(color(skin.colors.surface), RoundedCornerShape(Metrics.menuCornerDp.dp))
            .border(
                skin.shape.surfaceBorderDp.dp,
                color(skin.colors.lineBase),
                RoundedCornerShape(Metrics.menuCornerDp.dp),
            )
            .padding(horizontal = Metrics.gridDp.dp * 2),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (query.isEmpty()) {
            BasicText(
                "Search apps",
                style = style(skin.colors.textSecondary, skin.typography.menu),
            )
        }
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = style(skin.colors.textPrimary, skin.typography.menu),
            cursorBrush = SolidColor(color(skin.colors.accent)),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AppRow(choice: MainAppChoice, selected: Boolean, skin: Skin, onClick: () -> Unit) {
    SettingsCard(skin) {
        SettingRow(choice.label, skin, SettingKind.Toggle, description = choice.packageName, checked = selected, onClick = onClick)
    }
}

@Composable
private fun SectionLabel(text: String, skin: Skin) {
    SettingsSection(text, skin)
}

@Composable
private fun Hint(text: String, skin: Skin) {
    BasicText(
        text,
        style = style(skin.colors.textSecondary, skin.typography.menu),
        modifier = Modifier.padding(vertical = Metrics.gridDp.dp * 2),
    )
}

private fun color(argb: Long): Color = Color(argb.toInt())

private fun style(tint: Long, token: TypeToken): TextStyle = TextStyle(
    color = color(tint),
    fontSize = token.sizeSp.sp,
    fontWeight = FontWeight(token.weight),
    letterSpacing = token.trackingSp.sp,
)
