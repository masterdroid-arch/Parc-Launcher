package com.brightblu.parc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.brightblu.parc.model.*

@Composable
internal fun ReleaseText(skin: Skin, text: String, heading: Boolean = false) {
    val token = if (heading) skin.typography.title else skin.typography.menu
    BasicText(text, Modifier.padding(vertical = Metrics.textGutterDp.dp / 4)
        .semantics { if (heading) heading() }, style = TextStyle(color = Color(skin.colors.textPrimary.toInt()),
        fontSize = token.sizeSp.sp, fontWeight = if (heading) FontWeight.Bold else FontWeight(token.weight)))
}
@Composable
internal fun ReleaseAction(skin: Skin, label: String, action: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = Metrics.touchTargetDp.dp)
        .clickable(role = Role.Button, onClick = action)) { ReleaseText(skin, label) }
}
@Composable
internal fun ReleaseEntry(skin: Skin, entry: ReleaseNotes) {
    ReleaseText(skin, "Parc ${entry.versionName}", true)
    ReleaseText(skin, entry.date)
    entry.items.forEach { item ->
        ReleaseText(skin, item.headline, true)
        ReleaseText(skin, item.text)
    }
}
@Composable
internal fun WhatsNewSheet(skin: Skin, entry: ReleaseNotes, dismiss: () -> Unit) {
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * Metrics.releaseNotesMaxHeightFraction)
            .background(Color(skin.colors.surface.toInt())).padding(Metrics.textGutterDp.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                ReleaseText(skin, "What's new", true)
                ReleaseEntry(skin, entry)
                ReleaseText(skin, "Older changes are in Settings → About → Version history")
            }
            ReleaseAction(skin, "Got it", dismiss)
        }
    }
}
@Composable
internal fun HomeUpdateNotes(skin: Skin) {
    val context = LocalContext.current
    val entries = remember(context) { loadChangelog(context) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val store = PrivateSeenVersionStore(context)
        // Versions predating this feature have no marker; preserve their update experience.
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (store.lastSeen == null && info.lastUpdateTime > info.firstInstallTime) store.lastSeen = 0
        visible = claimUpdate(store, BuildConfig.VERSION_CODE)
    }
    if (visible) WhatsNewSheet(skin, entries.first { it.versionCode == BuildConfig.VERSION_CODE }) { visible = false }
}
