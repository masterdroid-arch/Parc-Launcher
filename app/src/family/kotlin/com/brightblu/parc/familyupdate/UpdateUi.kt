package com.brightblu.parc.familyupdate

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brightblu.parc.feature.settings.ui.SettingKind
import com.brightblu.parc.feature.settings.ui.SettingRow
import com.brightblu.parc.feature.settings.ui.SettingsCard
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.HubSize
import com.brightblu.parc.LocalHubSize
import com.brightblu.parc.hubBodyStyle
import com.brightblu.parc.hubCaptionStyle
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.parcIgnoreObscuredTouches
import com.brightblu.parc.model.readableAccent
import java.time.ZoneId

/*
 * T396: the screens of the update checker. The hub row sits at the top of the Today page; Settings has a second way in
 * at the top of its root and the full controls under Data & help > This build. All three read one engine.
 */

/** The slim notice at the top of Today: the version, Update and Later; progress with Cancel; or the error with Retry. */
@Composable
internal fun UpdateHubRow(skin: Skin) {
    val context = LocalContext.current
    val engine = remember(context) { UpdateRuntime.engine(context) }
    val ui by engine.ui.collectAsState()
    val state = ui.state
    if (state is UpdateState.Idle) return
    if (ui.hubHidden && state !is UpdateState.Downloading) return
    val shape = RoundedCornerShape(skin.shape.surfaceCornerDp.dp)
    val stacked = UpdateLayout.stacked(LocalDensity.current.fontScale)
    // Compact is a short window: one line, no extra padding, so the row is never cut by the page's edge.
    val compact = LocalHubSize.current == HubSize.Compact
    val title = when (state) {
        is UpdateState.Downloading -> UpdateWords.percent(state.fraction)
        is UpdateState.Failed -> if (state.problem == UpdateProblem.NeedInstallPermission) "One more step" else "Update failed"
        else -> if (compact) "Update ${ui.version.orEmpty()}" else UpdateText.available(ui.version.orEmpty())
    }
    val caption = when (state) {
        is UpdateState.Failed -> state.problem.message
        is UpdateState.Available -> if (state.installerOpened) "Installer opened. Confirm there to finish." else ""
        is UpdateState.Downloading -> "Parc ${state.version}"
        UpdateState.Idle -> ""
    }
    val buttons: @Composable () -> Unit = {
        when (state) {
            is UpdateState.Downloading -> UpdateButton("Cancel", skin, primary = false, "Cancel the update download") { engine.cancel() }
            is UpdateState.Failed -> {
                UpdateButton("Retry", skin, primary = true, "Retry the update") { engine.update() }
                UpdateButton("Later", skin, primary = false, "Hide the update notice until tomorrow") { engine.later() }
            }
            else -> {
                UpdateButton("Update", skin, primary = true, "Download and install Parc ${ui.version.orEmpty()}") { engine.update() }
                UpdateButton("Later", skin, primary = false, "Hide the update notice until tomorrow") { engine.later() }
            }
        }
    }
    Column(Modifier.fillMaxWidth()
        .background(Color(skin.colors.surface.toInt()).copy(alpha = skin.shape.surfaceAlpha), shape)
        .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()), shape)
        .padding(horizontal = Metrics.itemGapDp.dp, vertical = if (compact) 0.dp else Metrics.lineGapDp.dp)
        .semantics(mergeDescendants = false) { contentDescription = title },
        verticalArrangement = Arrangement.spacedBy(Metrics.lineGapDp.dp)) {
        val text: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier.padding(start = Metrics.lineGapDp.dp), verticalArrangement = Arrangement.spacedBy(Metrics.lineGapDp.dp / 2)) {
                BasicText(title, maxLines = if (compact) 1 else Int.MAX_VALUE,
                    style = hubBodyStyle(skin, skin.colors.readableAccent).copy(fontWeight = FontWeight.SemiBold))
                if (caption.isNotEmpty()) BasicText(caption, style = hubCaptionStyle(skin))
            }
        }
        if (stacked) {
            text(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp), verticalAlignment = Alignment.CenterVertically) { buttons() }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.lineGapDp.dp), verticalAlignment = Alignment.CenterVertically) {
                text(Modifier.weight(1f))
                buttons()
            }
        }
        if (state is UpdateState.Downloading) {
            val fraction = state.fraction
            Box(Modifier.fillMaxWidth().height(Metrics.updateProgressDp.dp).clip(RoundedCornerShape(50))
                .background(Color(skin.colors.lineBase.toInt()))) {
                Box(Modifier.fillMaxWidth(fraction ?: 0f).height(Metrics.updateProgressDp.dp)
                    .background(Color(skin.colors.accent.toInt()), RoundedCornerShape(50)))
            }
        }
    }
}

/** A pill at least 48 dp tall. Update and Retry are Parc orange, the others are a quiet text pill. */
@Composable
private fun UpdateButton(label: String, skin: Skin, primary: Boolean, description: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    val fill = if (primary) Color(Skin.ParcOrange.toInt()) else Color.Transparent
    val ink = if (primary) Color(Skin.Midnight.colors.bg.toInt()) else Color(skin.colors.textPrimary.toInt())
    Box(Modifier.heightIn(min = Metrics.minTouchDp.dp).widthIn(min = Metrics.minTouchDp.dp).clip(shape).background(fill, shape)
        .semantics { contentDescription = description }
        // T403: Update and Retry start an install, so they ignore touches while another app's window covers them.
        .then(if (primary) Modifier.parcIgnoreObscuredTouches() else Modifier)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = Metrics.itemGapDp.dp * 1.5f),
        contentAlignment = Alignment.Center) {
        BasicText(label, maxLines = 1, style = settingsText(skin, if (primary) Skin.Midnight.colors.bg else skin.colors.textPrimary)
            .copy(color = ink, fontWeight = FontWeight.Medium))
    }
}

/** The "Update available" row at the top of Parc Settings: a second way in to the same update. */
@Composable
internal fun UpdateRootRow(skin: Skin, open: () -> Unit) {
    val context = LocalContext.current
    val engine = remember(context) { UpdateRuntime.engine(context) }
    val ui by engine.ui.collectAsState()
    if (ui.state is UpdateState.Idle) return
    SettingsCard(skin) {
        SettingRow(UpdateText.available(ui.version.orEmpty()), skin, SettingKind.Page, UpdateWords.summary(ui.state), divider = false) { open() }
    }
    Spacer(Modifier.height(Metrics.rowGapDp.dp))
}

/** The rows of Data & help > This build > Updates: the switch, Check now, Last checked, and the update itself. */
@Composable
internal fun UpdateSettingsRows(skin: Skin) {
    val context = LocalContext.current
    val engine = remember(context) { UpdateRuntime.engine(context) }
    val ui by engine.ui.collectAsState()
    val state = ui.state
    SettingRow("Check for updates", skin, SettingKind.Toggle,
        "Asks GitHub once a day.",
        checked = ui.autoCheck,
        info = "Once a day Parc asks GitHub if a newer version exists. That is one request, and GitHub sees your IP address. Parc sends nothing else: no account, no identifier. Nothing is installed without your tap. Turn it off and Parc asks only when you tap Check now.") {
        val on = !ui.autoCheck
        engine.setAutoCheck(on)
        UpdateScheduler.sync(context.applicationContext, on)
    }
    val zone = remember { ZoneId.systemDefault() }
    val last = UpdateText.lastChecked(ui.lastChecked, zone)
    SettingRow(if (ui.checking) "Checking…" else "Check now", skin, SettingKind.Action,
        listOf(last, ui.checkMessage).filter { it.isNotEmpty() }.joinToString("\n"),
        enabled = !ui.checking && state !is UpdateState.Downloading) { engine.checkNow() }
    when (state) {
        UpdateState.Idle -> Unit
        is UpdateState.Available -> Box(Modifier.parcIgnoreObscuredTouches()) {
            SettingRow("Update to ${state.version}", skin, SettingKind.Action,
                UpdateWords.summary(state), divider = false) { engine.update() }
        }
        is UpdateState.Downloading -> {
            SettingRow(UpdateWords.percent(state.fraction), skin, SettingKind.Value, "Parc ${state.version}") { }
            SettingRow("Cancel", skin, SettingKind.Action, "Stop the download. Nothing is installed.", divider = false) { engine.cancel() }
        }
        is UpdateState.Failed -> Box(Modifier.parcIgnoreObscuredTouches()) {
            SettingRow("Retry", skin, SettingKind.Action, state.problem.message, divider = false) { engine.update() }
        }
    }
}
