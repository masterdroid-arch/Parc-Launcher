package com.brightblu.parc

import com.brightblu.parc.model.textFamily
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.brightblu.parc.model.*
import com.brightblu.parc.model.ParcDialogArea

/** T221: every release heading keeps the skin token's weight; nothing forces Bold. */
internal fun releaseTextWeight(token: TypeToken): FontWeight = FontWeight(token.weight)

internal fun releaseTextToken(skin: Skin, heading: Boolean, caption: Boolean): TypeToken = when {
    // T271: the title token (28 sp) is far too large for a list of notes; headings use the menu size, heavier.
    heading -> skin.typography.menu.let { TypeToken(it.sizeSp + 2f, maxOf(it.weight, 500), it.trackingSp) }
    caption -> skin.typography.caption
    else -> skin.typography.menu
}

@Composable
internal fun ReleaseText(skin: Skin, text: String, heading: Boolean = false, caption: Boolean = false) {
    val token = releaseTextToken(skin, heading, caption)
    BasicText(text, Modifier.padding(vertical = Metrics.lineGapDp.dp)
        .semantics { if (heading) heading() }, style = TextStyle(fontFamily = skin.textFamily,
        color = Color((if (caption) skin.colors.textSecondary else skin.colors.textPrimary).toInt()),
        fontSize = token.sizeSp.sp, fontWeight = releaseTextWeight(token), letterSpacing = token.trackingSp.sp))
}
@Composable
internal fun ReleaseAction(skin: Skin, label: String, action: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = Metrics.touchTargetDp.dp)
        .clickable(role = Role.Button, onClick = action)) { ReleaseText(skin, label) }
}
@Composable
internal fun ReleaseEntry(skin: Skin, entry: ReleaseNotes) {
    ReleaseText(skin, "Parc ${entry.versionName}", true)
    ReleaseText(skin, entry.date, caption = true)
    entry.items.forEach { item ->
        ReleaseText(skin, item.headline, true)
        ReleaseText(skin, item.text)
    }
}
@Composable
internal fun WhatsNewSheet(skin: Skin, entry: ReleaseNotes, dismiss: () -> Unit) {
    // T230: the sheet owns the safe area, so the last line and the dismiss action stay above the
    // navigation bar. Long release notes scroll inside the sheet instead of running off the screen.
    Dialog(onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ParcDialogArea {
            Box(Modifier.fillMaxSize().padding(horizontal = Metrics.screenGutterDp.dp), contentAlignment = Alignment.Center) {
                Column(Modifier.fillMaxWidth()
                    .heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * Metrics.releaseNotesMaxHeightFraction)
                    .background(Color(skin.colors.surface.toInt())).parcCardPadding()) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        ReleaseText(skin, "What's new", true)
                        ReleaseEntry(skin, entry)
                        ReleaseText(skin, "Older changes are in Settings → About → Version history")
                    }
                    Box(Modifier.padding(top = Metrics.lineGapDp.dp)) { ReleaseAction(skin, "Got it", dismiss) }
                }
            }
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
    // T411: tips wait while this dialog is open.
    SideEffect { TipSession.latch.setWhatsNewOpen(visible) }
    if (visible) WhatsNewSheet(skin, entries.first { it.versionCode == BuildConfig.VERSION_CODE }) { visible = false }
}
