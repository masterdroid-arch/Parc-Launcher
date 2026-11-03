package com.brightblu.parc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.*

internal data class HubTemplateSpec(val summary: String, val action: String, val empty: String)
internal val hubTemplates = linkedMapOf(
    "clock" to HubTemplateSpec("Time", "Clock", "Turn on the clock in Icons and clock"),
    "system" to HubTemplateSpec("Phone status", "Details", "Readings are unavailable. Try again shortly"),
    "power" to HubTemplateSpec("Battery", "Details", "Battery readings are unavailable"),
    "network" to HubTemplateSpec("Connection", "Details", "Connect to a network to see traffic"),
    "apps" to HubTemplateSpec("Screen time today", "Details", "Allow usage access to see screen time"),
    "sound" to HubTemplateSpec("Volume", "Details", "Open Sound settings to choose an output"),
    "timers" to HubTemplateSpec("Timers", "Details", "Tap a preset to start a timer"),
    "agenda" to HubTemplateSpec("Next event", "Details", "No events ahead. Add one in your calendar"),
    "media" to HubTemplateSpec("Now playing", "Details", "Play something in a music or video app"),
    "focus" to HubTemplateSpec("Focus", "Details", "Create a mode in Focus settings"),
    "notes" to HubTemplateSpec("Notes", "Details", "Tap + to add your first note"),
    "comms" to HubTemplateSpec("Messages", "Details", "All caught up. New messages will appear here"),
    QuickToggleCatalog.PageId to HubTemplateSpec("Quick toggles", "Details", "No tiles shown. Choose tiles in page options"),
).apply {
    if (WeatherVariant.Available) put(WeatherVariant.PageId, HubTemplateSpec("Weather", "City", "Choose a city in page options to see the weather"))
}
internal val LocalHubPrimary = compositionLocalOf { false }

@Composable
internal fun HubHero(value: String, label: String, skin: Skin) {
    Column {
        BasicText(value, style = hudValueStyle(skin).copy(
            fontSize = (if (LocalHubSize.current == HubSize.Compact) Metrics.hubLineSp else Metrics.hubValueSp).sp,
            fontWeight = FontWeight.Light))
        if (LocalHubSize.current != HubSize.Compact) BasicText(label, style = hudValueStyle(skin))
    }
}

@Composable
internal fun HubPermissionCard(message: String, action: String, skin: Skin, scroll: Boolean = true, allow: () -> Unit) {
    val viewport = if (scroll) Modifier.heightIn(max = LocalHubContentHeight.current.dp).hubPageScroll() else Modifier
    Column(viewport) {
    HudCell(skin, Modifier.fillMaxWidth()) {
        Column {
            BasicText(message, style = hudValueStyle(skin))
            Box(Modifier.heightIn(min = Metrics.menuRowHeightDp.dp)
                .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.accent.toInt()), RoundedCornerShape(skin.shape.surfaceCornerDp.dp))
                .clickable(onClick = allow).padding(horizontal = Metrics.rowGapDp.dp), contentAlignment = Alignment.Center) {
                BasicText(action, style = hudValueStyle(skin, skin.colors.accent))
            }
        }
    }
}
}
